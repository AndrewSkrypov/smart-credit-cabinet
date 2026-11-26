# Поставка Assessment R2 на Dev

База реализации `2fa79d291794eb1503ae1a9aeda56000476356b5`. Этот выпуск
добавляет инфраструктуру для того же контракта команд и результатов v1.

| Граница | Контракт |
| --- | --- |
| Manifest | `images.assessment` и `assessment = {"jar_sha256":"…","dev_mode":"disabled\|local\|remote","kafka_image":"…"}`. Образы Assessment и patched Kafka имеют digest одного Registry с остальными образами. Для Assessment проверяются OCI revision и JAR SHA-256. Для Kafka проверяются OCI revision и base digest из `ci/alpha/kafka-image.txt`. |
| Вызов из общего Dev rollout | Под блокировкой общего rollout загрузить проверенный manifest, вызвать `AssessmentDev.open(deployment)` и подключить `compose.assessment.yaml` с `compose_environment()`. Начальная конфигурация Credit всегда локальная. После закрытия входа и остановки старого Credit вызвать `prepare()` после миграции Credit. Только полученное `credit_environment(receipt)` включает удалённый Credit. После запуска Credit общий rollout вызывает `verify_credit(receipt)` до открытия входа. |
| PostgreSQL | Оценка использует отдельный экземпляр с БД `assessment`. Роли `assessment_owner` и `assessment_app` имеют разные автоматически созданные пароли. V1 применяется проверенным JAR под owner. У runtime есть DML на двух таблицах, без DDL и доступа к истории Flyway. |
| Сети и readiness | Четыре внутренние сети Compose раздельно соединяют Assessment с PostgreSQL, брокером, Valkey и ExternalSim. Kafka доступна только Credit и Assessment. Assessment проверяет аутентификацию Valkey и ключ ExternalSim при запуске под профилем prod. Health слушает loopback контейнера. Публичных портов нет. |
| Kafka | `assessment-kafka` использует отдельный KRaft-том и отдельный cluster ID. Поколение `g<hex>`, cluster ID, topics и группы остаются постоянными при повторах и обновлениях. Режимы не зависят от пилота Analytics. |
| Возврат | Перед `remote → local` общий rollout закрывает вход и останавливает Credit. Проверка допускает переключение после закрытия удалённых циклов и подтверждения outbox и offsets. Если осталась работа, та же ревизия Credit возвращается в прежнем режиме за закрытым входом для завершения. `disabled` допустим до первого включения. V24 сохраняет локальный режим у ранее открытых задач. |
| Состояние | Пароли, поколение и cluster ID создаются один раз в отдельном `assessment.env` с правами 0600 в каталоге 0700. Квитанции в том же каталоге связывают manifest, mode, идентичность томов и границы Kafka. Пароли, payload и реквизиты клиентов в квитанции отсутствуют. |
| Резервирование | Согласованная точка содержит дампы Credit и Assessment, холодный снимок тома брокера вместе с KRaft и offsets, защищённый state, manifest и контрольные суммы. Восстановление выполняется в новом изолированном проекте с теми же topic/group IDs, исходные данные сохраняются. |
| Helm | Собственный выключенный по умолчанию блок `assessment`, отдельные Secret refs, StatefulSets PostgreSQL и Kafka с сохраняемыми PVC, Service и NetworkPolicy без внешнего входа. ConfigMap для Credit подключается общим шаблоном после readiness. |

Общие RegistryRelease, publish, start, backup и restore остаются точками
интеграции общего выпуска. Локальные проверки этой ветки ограничены
Python, Bash, YAML и рендерингом Helm.

## Интерфейс общего выпуска

1. Проверить исходники по полной Git-ревизии и целостность пяти или шести JAR
   по списку общего backend-job. Взять `services/assessment/build/libs/app.jar`
   из проверенного инвентаря. Передать его SHA-256 в
   `org.opencontainers.image.jar.sha256` и `assessment.jar_sha256`.
   Передать полную ревизию в `org.opencontainers.image.revision` обоих образов.
   Для patched Kafka проверяется `org.opencontainers.image.base.name` и
   используется тот же сканируемый рецепт, что и у брокера Analytics.
2. Общий `RegistryRelease` принимает `images.assessment` и descriptor целиком.
   `assessment.kafka_image` указывает на `<registry>/<project>/kafka@sha256:…`.
   Привязать сервисы `assessment` и `assessment-migrate` к образу Assessment,
   `assessment-db` к образу PostgreSQL, `assessment-kafka` к
   `assessment.kafka_image`. Общий выпуск получает pinned-образы. Hook
   читает состояние локальных образов и actual OCI ID контейнеров.
3. Перед вычислением списка активных сервисов вызвать
   `AssessmentDev.open(deployment)`. Для включённой конфигурации добавить
   файл `assessment_dev.OVERLAY` к Compose и применить
   `deployment.environment.update(hook.compose_environment())`. Сохранить
   последний параметр `ASSESSMENT_CREDIT_KAFKA_ENABLED=false` до возврата
   `hook.prepare()`. `assessment-migrate` запускается только самим hook.
4. Удерживать блокировку общего rollout, закрыть HTTPS и остановить Credit.
   Подготовить V24 под ролью владельца Credit. Вызвать `hook.prepare()`, применить
   `hook.credit_environment(receipt)` и запустить Credit. Вызвать
   `hook.verify_credit(receipt)` перед открытием HTTPS. Hook записывает
   `binding.json`, неизменяемую квитанцию `receipt-<SHA>.json` и активную
   квитанцию `active.json` в приватном состоянии.

Проверка readiness использует Flyway через тот же образ Assessment под
owner, отдельную проверку прав app, состояние Kafka и health Assessment на
loopback. Shell-обёртка выполняет аутентифицированный Valkey PING и пробу
синтетического ИНН в ExternalSim до запуска JVM. Health порт внутри
контейнера слушает только 127.0.0.1. Kafka принимает plaintext на выделенной
внутренней сети Dev и хранит журнал бессрочно. Размер Kafka-тома контролируется
вместе с хранимыми диапазонами offsets.

Повтор выпуска пользуется тем же `assessment.env` и теми же томами.
При незавершённом запуске повторяется исходный manifest. При переходе в
`local` проверяются открытые удалённые циклы, оба outbox и обе группы.
Если есть незавершённая работа, действующий Credit возвращается в прежнем
режиме за закрытым входом до прохождения проверки. После переключения
используется выпуск с V24 и режимом `local`, при этом хранилища и квитанции
доступны для аудита.

## Резерв и изолированное восстановление

1. Закрыть вход и остановить Credit и Assessment. Дождаться завершения
   процессов. Держать брокер и обе БД доступными для чтения.
   `hook.backup_checkpoint()` фиксирует поколение и cluster ID, начало и
   конец каждого topic, offsets групп и счётчики обеих БД. Допустимы
   необработанные сообщения и незавершённые циклы.
2. Сделать дампы Credit и Assessment под операторскими ролями и сохранить
   manifest, checkpoint, `assessment.env` и квитанции в защищённом резерве.
   Пока писатели остановлены, штатно остановить `assessment-kafka` и сделать
   холодную копию всего Kafka-тома, включая `meta.properties`, KRaft metadata
   и `__consumer_offsets`. Записать SHA-256 дампов, холодной копии и
   метаданных. Все файлы с ключами и данными хранятся в приватном или
   шифрованном контуре общего механизма backup.
3. Создать отдельный `cabinet-dev-restore-<id>` без публичного входа.
   Использовать новые тома проекта. Проверить SHA-256 и восстановить БД под
   их именами с прежними owner/app-ролями. Для новой Assessment БД
   `bootstrap.sql` подготавливает роли из восстановленного ENV, а дамп
   сохраняет V1, историю Flyway и `assessment_ops.identity`. Импортировать
   холодную копию в пустой Kafka-том отдельного проекта. Использовать те же
   cluster ID и поколение из исходного `assessment.env` с правами 0600.
   Оригинальные тома, журнал и квитанции остаются доступными.
4. Запустить только обе БД и Kafka. В новом каталоге состояния сохранить
   восстановленный ENV и вызвать `hook.verify_restore(checkpoint)`.
   Проверка требует точного совпадения счётчиков, диапазонов и offsets
   групп, записывает `restore-verified.json` и позволяет привязать новые
   тома. Активные указатели и тома оригинального проекта хранятся с резервом.
   После сравнения применить тот же проверенный release и readiness hook.
   Клиенты продолжат с сохранённых offsets, а истёкшие lease будут
   перевзяты штатно.
5. В копии повторить одну синтетическую команду и один сохранённый
   результат с исходным ключом UUID заявки. Дождаться подтверждения
   offsets и сопоставить количество исполнения, решений и предложений.
   Для позднего ответа дождаться ручного завершения синтетической заявки
   при остановленном Assessment, запустить Assessment и дождаться
   увеличения offset группы Credit. Проверить, что решение осталось
   единственным. Повторить рестарт с backlog и сверку привязки томов.

## Helm

Подключить `helm/cabinet/values.assessment.yaml` и установить
`assessment.enabled=true`, pinned `images.assessment`,
`images.assessment-kafka`, стабильные `assessment.generation` и
`assessment.clusterId`. Секреты поступают через `assessment.databaseSecret`
с ключами `ASSESSMENT_ADMIN_PASSWORD`, `ASSESSMENT_MIGRATION_PASSWORD` и
`ASSESSMENT_DB_PASSWORD`. `VALKEY_PASSWORD` и `SIM_API_KEY` берутся из
существующего Secret кабинета. Значения паролей в Helm values отсутствуют.

Начальный rollout использует `assessment.creditMode=local`.
`assessment.bootstrapTopics=true` применяется для первой инициализации пустого
брокера. При последующих обновлениях и восстановлении он равен `false`,
и Job проверяет существующие topics. Дождаться успешного Job
`assessment-prepare-…`, готовности Assessment, совпадения режима, идентичности
хранилищ и проверки защищённого входа. Общий шаблон Credit подключает
`assessment-credit-transport` через `envFrom.configMapRef`.
Защищённый rollout принимает подтверждённую квитанцию и передаёт
`assessment.receipt.revision`, `generation` и `sha256` для режима `remote`.
Изменение ConfigMap применяется новой ревизией Pod Credit после подготовки.
При возврате сохраняются PVC, режим меняется на `local` после дренирования
и сравнения из предыдущего раздела.

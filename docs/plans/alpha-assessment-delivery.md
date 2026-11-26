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

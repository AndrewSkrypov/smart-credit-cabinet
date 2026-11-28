# Оповещения и дежурства

## Локальная проверка

Запустите `python3 monitoring/alerts/smoke.py` из корня репозитория. Нужен Docker Compose.
Проверка поднимает отдельную сеть и временные Prometheus, Alertmanager, GoAlert и HTTP fixture.
Порты публикуются на loopback, учётные записи демонстрационные, данные сбрасываются после теста.
Сценарий проверяет `Up → pending → firing`, доставку локального webhook в GoAlert,
`Acknowledge`, переход Prometheus в inactive и закрытие того же инцидента через resolved webhook.
Сценарий проверяет основного дежурного в активной ротации и переход
неподтверждённого инцидента к резервному через одну минуту. Исходящих сообщений нет.

## Production

Alertmanager группирует Telegram по окружению, имени правила и сервису. Критические
оповещения prod дублируются в GoAlert. Телеграм получает critical и warning.
Dev не попадает в production-получатели. Секрет `cabinet-paging` в namespace
мониторинга содержит `telegram-token`, `telegram-chat-id`, `goalert-url` и монтируется
в Alertmanager. Подключайте маршруты после согласования канала Telegram и состава смены.
Проверяйте маршрутизацию с `amtool config routes test` до включения исходящих уведомлений.

GoAlert v0.35.0 можно включить через `helm/observability/goalert-values.yaml`. Выделите
собственную БД PostgreSQL и роль с правом миграций в ней. Используйте проверяемое TLS
соединение, если БД вынесена из доверенной сети, и постоянный ключ шифрования.
Секрет `cabinet-goalert` содержит `db-url` и `data-encryption-key`. При переносе
сохраните ключ вместе с восстановимой резервной копией БД. GoAlert слушает только
внутренний Service, без Ingress. Администратор подключается через
`kubectl -n monitoring port-forward svc/cabinet-goalert 8081:8081` с разрешением RBAC
на port-forward. Ссылка для такого способа доступа — `http://localhost:8081`.

Создайте первую учётную запись администратора через CLI внутри GoAlert.
Подставляйте подтверждённые имя и email оператора. CLI прочитает настройки БД из
переменных окружения контейнера и запросит пароль интерактивно.

```bash
kubectl -n monitoring exec -it deployment/cabinet-goalert -- \
  /usr/bin/goalert add-user --admin --user cabinet-operator --email OPERATOR_EMAIL
```

1. Создайте Schedule с ротацией основного и резервного дежурных, часовой пояс
   `Europe/Moscow`. Проверьте наличие дежурного в каждом интервале.
2. Привяжите Schedule к первой ступени Escalation Policy. Первая ступень уведомляет
   текущего дежурного сразу. Если подтверждения нет 10 минут, вторая ступень уведомляет
   резервного дежурного. Ещё через 15 минут третья ступень уведомляет владельца сервиса.
   Согласуйте контактные точки и режим повтора с командой.
3. Укажите политику в сервисе GoAlert и выпустите ключ типа Prometheus Alertmanager.
   Для webhook из кластера замените origin URL на `http://cabinet-goalert:8081`,
   сохранив путь и параметры. Полный URL занесите в `cabinet-paging/goalert-url`.
4. Настройте согласованный канал доставки дежурному (например, корпоративный SMTP
   или внутренний webhook). Ротация и эскалация функционируют без платного сервиса.
   Голосовая доставка требует провайдера Twilio и остаётся выключенной без отдельного
   согласования.
5. На тестовом инциденте проверьте, что `Acknowledge` останавливает вызов следующего
   дежурного. Доведите исходное условие до нормы и убедитесь, что пришёл resolved и
   закрылась эта же карточка. Для зависшей карточки сверяйте источник перед ручным Close.
   Отдельно оставьте тестовый инцидент без ack до второй ступени и проверьте статус
   доставки оператору в истории GoAlert.

## Отдельная БД и проверка контактов

Для внутреннего PostgreSQL добавьте к Helm-релизу `-f helm/observability/goalert-values.yaml`
и `-f helm/observability/goalert-db-values.yaml`. Перед установкой один раз создайте
парные Secrets сервисной командой с указанным новым kubeconfig:

```bash
python3 helm/observability/provision-goalert-secrets.py --kubeconfig "$PUMP_PROD_KUBECONFIG" \
  --context kubernetes-admin@alfa-cluster-2
```

Команда создаёт пароли через защищённые файлы и при повторе проверяет согласованность.
`goalert_admin` инициализирует `pgcrypto`. Роль `goalert` владеет только своей БД
и выполняет миграции без прав суперпользователя, создания БД и ролей. БД использует
PVC 8 GiB и SCRAM. Сетевой доступ разрешён только GoAlert. DSN внутри изолированной
сети использует `sslmode=disable`. Для ротации пароля существующего PVC обновите роль
в PostgreSQL и соответствующие Secrets в одной согласованной операции.

Для синтетических контактов добавьте `-f helm/observability/goalert-test-contacts-values.yaml`.
Этот режим исключает внешний Alertmanager paging. GoAlert может подключаться к
своей БД, DNS кластера и временному Mailpit. У Mailpit нет наружного маршрута.
Тестовые адреса оканчиваются на `example.invalid`, сообщения хранятся в памяти.

Откройте порт-форварды к `svc/cabinet-goalert 8081:8081` и
`svc/cabinet-goalert-mailpit 8025:8025` в namespace `monitoring`. После создания
тестовой записи администратора по инструкции выше подайте её пароль из файла
с правами `0600`:

```bash
python3 monitoring/alerts/test-contacts.py --admin-user "$GOALERT_TEST_ADMIN_USER" \
  --admin-password-file "$GOALERT_TEST_ADMIN_PASSWORD_FILE"
```

Сценарий подтверждает два синтетических email-контакта, отправляет событие на отдельный
внутренний webhook GoAlert и проверяет сообщения основному и резервному контактам,
ACK и закрытие по resolved. Локальный `smoke.py` отдельно проверяет переходы Prometheus
и доставку Alertmanager. После приёмки удалите синтетические сервис, политику, расписание,
ротацию и контакты через GoAlert, замените временный SMTP настройками согласованных контактов
и уберите тестовый Helm-оверлей перед включением внешних маршрутов.

## Документация

[Нативный webhook GoAlert](https://goalert.me/docs/alerting/alerting-from-prometheus/),
[эксплуатация GoAlert](https://github.com/target/goalert/blob/v0.35.0/docs/getting-started.md),
[Alertmanager](https://prometheus.io/docs/alerting/0.33/configuration/).

## Живая проверка Telegram на dev

Добавьте `monitoring/compose.paging.yml` последним, после `monitoring/compose.ops.yaml`.
Включите `monitoring` в `CABINET_OPTIONAL_PROFILES`. Alertmanager остаётся внутри
сети Compose и использует отдельный том `cabinet-alertmanager:/alertmanager`.
Только `CabinetTelegramSmoke` из dev проходит в согласованный канал. TLS проверяется
системным набором CA. Токен и chat ID не передаются через Compose env.

На хосте `pump-final` используются следующие пути.

- `CABINET_ALERTMANAGER_PAGING_DIR` по умолчанию `/etc/pump/monitoring/cabinet-paging`.
  Каталог принадлежит `root:65534` с режимом `0750`. Файлы `telegram-token` и
  `telegram-chat-id` принадлежат `root:65534` с режимом `0640`. В контейнере путь
  `/etc/alertmanager/secrets/cabinet-paging` доступен UID/GID 65534 только для чтения.
- `CABINET_ALERTMANAGER_RULES_DIR` по умолчанию `/etc/pump/monitoring/live-rules`.
  Файл `cabinet-telegram-smoke.yml` устанавливается в неактивном состоянии и
  читается Prometheus из `/etc/prometheus/live-rules`. Запись разрешена оператору хоста.

Для проверки приложения через loopback нужны `GRAFANA_USER`, `GRAFANA_PASSWORD`,
`GRAFANA_PORT`, `PROMETHEUS_PORT`, `CABINET_SYNTHETIC_AGENT_EMAIL` и
`CABINET_SYNTHETIC_AGENT_PASSWORD`. `deploy/common.py` выставляет
`CABINET_OTLP_ENABLED` и `CABINET_LOG_FILE` для профиля мониторинга.
Для синтетической операции используйте `CABINET_TRACE_SAMPLE_RATE=1.0` при репетиции.

Команды ниже запускаются на dev с существующим проектом и набором Compose-файлов.
Замените `compose` на команду текущего проекта с его `-p`, `--env-file`, `-f` и профилями.

```bash
compose exec -T prometheus promtool query instant http://127.0.0.1:9090 'up{service!=""}'
compose exec -T prometheus promtool query instant http://127.0.0.1:9090 'prometheus_notifications_alertmanagers_discovered'
compose exec -T alertmanager amtool check-config /etc/alertmanager/alertmanager.yml
compose exec -T alertmanager amtool alert query --alertmanager.url=http://127.0.0.1:9093 alertname=CabinetTelegramSmoke
```

Сначала подтвердите, что app targets Up и Prometheus видит один Alertmanager.
Убедитесь, что очередь оповещений не содержит `CabinetTelegramSmoke`. Затем, в согласованное
окно, запустите от имени оператора:

```bash
rules_dir="${CABINET_ALERTMANAGER_RULES_DIR:-/etc/pump/monitoring/live-rules}"
rule="$rules_dir/cabinet-telegram-smoke.yml"
install_smoke_rule() {
  hold="$1"
  temp="$(mktemp "$rules_dir/.cabinet-smoke.XXXXXXXX")"
  sed "s/vector(0)/vector($hold)/" monitoring/alerts/cabinet-telegram-smoke.yml > "$temp"
  chmod 0644 "$temp"
  mv "$temp" "$rule"
  compose kill -s SIGHUP prometheus
}
trap 'install_smoke_rule 0' EXIT
install_smoke_rule 1
```

Проверяйте `pending`, затем `firing` после непрерывного hold 2 минуты.

```bash
compose exec -T prometheus promtool query instant http://127.0.0.1:9090 'ALERTS{alertname="CabinetTelegramSmoke"}'
compose exec -T alertmanager amtool alert query --alertmanager.url=http://127.0.0.1:9093 alertname=CabinetTelegramSmoke
```

В той же оболочке снимите условие после появления FIRING в канале. При сбое доставки
также снимите условие перед диагностикой.

```bash
install_smoke_rule 0
trap - EXIT
```

Дождитесь RESOLVED и проверьте `alertmanager_notifications_failed_total`.

В prod `monitoring/cabinet-paging` хранит те же ключи `telegram-token` и
`telegram-chat-id`. `goalert-url` добавляется после согласования маршрута GoAlert.
`paging-values.yaml` подключается отдельным изменением после готовности целей.

## Изолированный GoAlert на dev

`monitoring/compose.goalert.yml` запускается отдельным проектом `pump-oncall-dev`.
На хосте `pump-final` рабочий каталог `/srv/pump-oncall-dev` имеет режим `0700`.
В нём лежат `compose.goalert.yml`, `alerts/goalert-dev-db.sql`,
`alerts/goalert-dev-access.conf` и скрипты проверки
из репозитория. Конфигурация и файлы с учётными данными имеют режим `0600`.
GoAlert 0.35.0, PostgreSQL 17.12, Mailpit 1.31.6 и Nginx 1.30.7 закреплены digest в Compose.

- `postgres.env` содержит `POSTGRES_PASSWORD` и `GOALERT_DB_APP_PASSWORD`.
- `goalert.env` содержит `GOALERT_DB_URL` для `goalert@postgres:5432/goalert` и
  постоянный `GOALERT_DATA_ENCRYPTION_KEY`. Пароль в DSN соответствует
  `GOALERT_DB_APP_PASSWORD`. Для изолированной сети DSN использует `sslmode=disable`.
- `admin-password` содержит пароль локальной синтетической учётки GoAlert.
  Создавайте учётку через интерактивный `goalert add-user`. Храните её пароль отдельно
  от файлов Compose и не передавайте его аргументом процесса.

Суммарные лимиты памяти контейнеров составляют 928 MiB. БД с SCRAM и ролью
`goalert` хранится в томе `pump-oncall-dev_goalert-dev-postgres`. Для обслуживания
используйте только `docker compose -p pump-oncall-dev -f compose.goalert.yml` из рабочего
каталога. Службы и SMTP остаются во внутренней сети Compose. Nginx публикует только
два фиксированных HTTP-маршрута на loopback и не передаёт трафик из GoAlert наружу.
Внешние провайдеры и маршруты Alertmanager с этим проектом не связаны.

Доступ оператора через SSH-туннель:

```bash
ssh -N -L 18081:127.0.0.1:18081 -L 18025:127.0.0.1:18025 pump-final
```

GoAlert доступен на `http://127.0.0.1:18081`, Mailpit на `http://127.0.0.1:18025`.
Для локальной проверки используйте уже созданную синтетическую учётку и
`test-contacts.py --goalert http://127.0.0.1:18081 --mailpit http://127.0.0.1:18025`

## Доставка Telegram через выделенный relay

На `pump-final` работают отдельные экземпляры `pump-telegram-relay@dev` и
`pump-telegram-relay@prod`. Установите `telegram-relay/relay.py` в
`/usr/local/libexec/pump-telegram-relay.py`, а unit в `/etc/systemd/system`.
Unit работает под динамическим непривилегированным пользователем и перезапускается
при отказе. В каталоге `/etc/pump/telegram-relay` с режимом `0700` храните для каждого
окружения `{dev,prod}.env` и `{dev,prod}.authorization-sha256` с режимом `0600`.
В `.env` укажите `RELAY_BIND_IP`, `RELAY_PORT` и `RELAY_CLIENT_CIDRS` через запятую.
Файл `authorization-sha256` содержит SHA-256 от полного значения заголовка
`Proxy-Authorization` без перевода строки. Создайте независимые значения Basic для
Dev и Prod, передайте их Alertmanager через защищённые конфиги и ограничьте доступ
к операторским копиям `{dev,prod}.client-auth` режимом `0600`.

Dev привязывается к gateway приватной Compose-сети `cabinet-dev_cabinet` на порту
`18328`. Если сеть пересоздана, проверьте её gateway и CIDR перед запуском сервиса.
В `alertmanager-dev-telegram.yml` замените маркеры адреса и авторизации в процессе
провижининга. Готовый файл `/etc/pump/monitoring/cabinet-paging/alertmanager-telegram.yml`
имеет владельца `root:65534` и режим `0640`. В защищённом `cabinet.env` задайте
`CABINET_ALERTMANAGER_CONFIG_PATH=/etc/alertmanager/secrets/cabinet-paging/alertmanager-telegram.yml`.
Развёртывание должно включать `monitoring/compose.paging.yml` с поддержкой этого параметра.
При откате на старую версию overlay добавьте после неё защищённое переопределение
`/etc/pump/monitoring/compose.telegram-live.yaml`, содержащее только команду
Alertmanager с этим `--config.file` и существующим `--storage.path=/alertmanager`.
После подтверждённого CI/CD rollout `reapply-dev.py SHA` проверяет этот overlay
под lock выпуска. `reapply-dev.py SHA --apply` при необходимости пересоздаёт только
Alertmanager. Скрипт проверяет manifest, `amtool check-config` и `/-/ready`.

Prod слушает `18443/TCP` на выделенном IPv4 с TLS после подтверждения правила
Edge. Firewall разрешает доступ только от проверенного SNAT-адреса Prod, а relay
повторно проверяет CIDR, авторизацию и точный адрес `api.telegram.org:443`.
Перед сменой SNAT повторите проверку с каждого воркера. В systemd drop-in для Prod
добавьте `LoadCredential=tls-cert:<путь>` и `LoadCredential=tls-key:<путь>`.
Закрытый ключ остаётся на relay, сертификат проверяется по SAN адреса слушателя.
Оба экземпляра передают Telegram TLS целиком и не ведут журналы запросов.

В Prod используйте `render-prod.py` с текущим нативным конфигом Alertmanager,
защищённым файлом авторизации и HTTPS-адресом relay. `--smoke-only` включает только
тестовый маршрут. Поместите результат под ключ `alertmanager.yaml` в Secret
`monitoring/cabinet-alertmanager-telegram-config`. В `monitoring/cabinet-paging`
добавьте `telegram-relay-ca.pem`, объединив обычные доверенные корни Alertmanager с
сертификатом relay. Клиент использует этот набор для обоих TLS-соединений.
После `amtool check-config` примените `helm/observability/telegram-paging-values.yaml`
дополнительно к текущим values. Этот overlay сохраняет внутренний контур GoAlert
с Mailpit. Его тестовые алерты не совпадают с фильтром Telegram. При откате Helm
сначала верните прежнюю конфигурацию Alertmanager, затем удалите выделенный Secret.

Для периодической проверки создайте `monitoring/cabinet-telegram-relay-ca` как
ConfigMap с публичным сертификатом relay под ключом `relay.pem`. Примените
`helm/observability/telegram-relay-probe-values.yaml` после существующих overlays.
`cabinet-telegram-relay-tls` проверяет TCP и TLS с доверенным сертификатом раз в 60 секунд.
Проверка не отправляет CONNECT, не требует секретов и не создаёт уведомлений.
Следите за `probe_success{target="telegram-relay-tls"}` и
`probe_ssl_earliest_cert_expiry{target="telegram-relay-tls"}`. При смене сертификата
обновите публичный ConfigMap и доверенные сертификаты Alertmanager.

Проверьте `systemctl is-active pump-telegram-relay@dev pump-telegram-relay@prod`.
Запустите `bash telegram-relay/health.sh IP:PORT AUTH_FILE` из сети Dev Alertmanager.
Для проверки из Prod запустите её во временном Pod и добавьте файл доверенного
сертификата последним аргументом. Проверка выполняет только HEAD к Bot API.
Для проверки доставки включите синтетический
`CabinetTelegramSmoke`, дождитесь `pending`, `firing` и подтверждённой отправки,
верните условие в false и дождитесь `resolved`. Отслеживайте завершённые запросы
`alertmanager_notification_requests_total{integration="telegram"}` и вычитайте
`alertmanager_notification_requests_failed_total`. После проверки удалите тестовое
правило или оставьте его неактивным. Операционные маршруты продолжают доставку
состояния, окружения, уровня, известного имени алерта и сервиса без аннотаций.

### Замена получателя Telegram

Dev и Prod отправляют в [Cabinet Alerts](https://t.me/alfacabinetalerts). Перед
сменой канала проверьте через `getMe`, `getChat` и `getChatMember` точное имя канала
и право бота `can_post_messages`. Сохраните прежние значения в отдельной закрытой
копии с режимом `0600`.

На Dev обновите только `telegram-chat-id` в `/etc/pump/monitoring/cabinet-paging`
атомарной заменой файла под `/var/lib/cabinet-dev/deploy.lock`. Сохраните владельца
`root:65534` и режим `0640`. В Prod обновите только ключ `telegram-chat-id` в Secret
`monitoring/cabinet-paging` и дождитесь нового значения в смонтированном файле
Alertmanager. Оба Alertmanager читают файл получателя при каждой отправке.
Для приёмки запустите по одному циклу `CabinetTelegramSmoke` на среду и сохраните
ссылки на `FIRING` и `RESOLVED` в новом канале. В открытом канале допустимы только
обезличенные поля оповещения. Числовой идентификатор получателя остаётся в
защищённой конфигурации.
с аргументами `--admin-user` и `--admin-password-file`. Сценарий проверяет доставку
основному и резервному тестовым контактам, ACK и закрытие по resolved.

### Формат рабочих Telegram-уведомлений

Общий шаблон находится в telegram-relay/message.tmpl. В обоих YAML он расположен
внутри поля message, чтобы для reload не потребовалось новое монтирование.
Синтетическое правило CabinetTelegramSmoke сохраняет прежний маркер и текст.
Рабочее сообщение показывает симптом, известный сервис или публичную цель и ссылку
на панель своего стенда. Окно начинается за 15 минут до первого срабатывания в
группе и заканчивается текущим временем либо моментом завершения последнего алерта.
В сообщении используются только разрешённые имена сервисов и цели. Аннотации,
внутренние адреса и имена узлов в публичный канал не подставляются. Панель задержки
показывает p95 для диагностики, само правило оценивает среднюю задержку.

Для действующего конфига запустите telegram-relay/render-message.py с аргументами
--environment, --input и --output. Подайте нативный защищённый YAML из действующего
источника и задайте новый защищённый файл. Скрипт меняет только parse_mode и message
указанного получателя. Проверьте кандидата командой amtool check-config и выполните
локальный render. На Dev применяйте атомарную замену файла из смонтированного
каталога под deploy lock, сохраняя режим 0640 и группу Alertmanager, затем выполните
reload. В Prod обновляйте только ключ alertmanager.yaml в действующем Secret,
дождитесь синхронизации конфигурации Operator. В обеих средах проверьте /-/ready,
успешный reload и версию активного текста, сверяя только поле message внутри
процесса. При сбое верните прежний защищённый YAML. Для этого изменения отдельная
отправка в Telegram не нужна.

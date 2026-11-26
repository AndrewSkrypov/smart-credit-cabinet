# Servicing

Самостоятельный Spring Boot runtime с PostgreSQL и внутренним gRPC API. Пользовательский HTTP остаётся в Credit. Чистые модели и application engine находятся в `libs:servicing-model` / `libs:servicing-core`; JDBC и транзакция — в `libs:servicing-persistence`, транспорт — в `libs:servicing-grpc` и этом runtime. Используются действующие Gradle service/library conventions и общие security/database/observability компоненты. Граница и ограничения описаны в ADR017 и плане R3.

## Сборка и запуск

```bash
./gradlew :services:servicing:bootJar :libs:servicing-core:test :services:servicing:test
```

Миграция отдельным owner-процессом того же JAR:

```bash
MIGRATION_ONLY=true DB_HOST=localhost DB_PORT=5432 DB_NAME=servicing \
  DB_USER=servicing_owner DB_PASSWORD="$SERVICING_OWNER_PASSWORD" \
  java -jar services/servicing/build/libs/app.jar
```

HTTP runtime использует `servicing_app`, Flyway выключен. Обязательны существующие PEM server certificate/key и CA доверенного Credit client. По умолчанию БД FENCED и финансовые операции закрыты до принятого переноса.

```bash
DB_HOST=localhost DB_PORT=5432 DB_NAME=servicing DB_USER=servicing_app \
  DB_PASSWORD="$SERVICING_APP_PASSWORD" \
  SERVICING_SERVER_CERTIFICATE=/run/tls/server.crt SERVICING_SERVER_KEY=/run/tls/server.key \
  SERVICING_CLIENT_CA=/run/tls/credit-ca.crt SERVICING_GRPC_HOST=0.0.0.0 \
  SERVICING_CLIENT_URI=spiffe://creditcabinet/credit \
  java -jar services/servicing/build/libs/app.jar
```

gRPC порт 9095, HTTP probes 8095. gRPC не публикуется в пользовательском ingress. mTLS client должен иметь точный URI SAN; серверный сертификат должен соответствовать hostname, заданному в Credit. Исходный JWT делегируется отдельно и проверяется на финансовых операциях. Для независимой проверки отзыва сессии задаётся общая `cabinet.identity.url`, как у других runtime.

Credit default `SERVICING_MODE=local`. Remote требует `SERVICING_MODE=remote`, `SERVICING_GRPC_HOST`, `SERVICING_GRPC_PORT`, `SERVICING_CLIENT_CERTIFICATE`, `SERVICING_CLIENT_KEY`, `SERVICING_SERVER_CA` и принятую запись переноса в Credit fence. Некорректный mode, отсутствующие сертификаты или непринятый импорт останавливают startup. Неверный/недоступный владелец баланса даёт 503, без локального fallback.

## Перенос и возврат

Остановить Credit HTTP/pipeline writers и дождаться завершения текущих вызовов АБС. Проверить отсутствие PENDING/CONFLICT opening outbox, сохранить backup обеих БД. `PGSERVICEFILE` содержит owner-подключения к строго этим двум изолированным БД; runtime роли его не получают. Инструмент использует `psql` (путь можно задать `PSQL`).

```bash
python3 services/servicing/cutover/transfer.py remote \
  --credit-service credit_owner --servicing-service servicing_owner --artifacts /secure/r3-forward
```

Инструмент берёт общий advisory lock в Credit, блокирует новые открытия/ack, сохраняет target-before, закрывает обоих writers, экспортирует полный агрегат и журнал идемпотентности, импортирует и сверяет SHA256 полного состояния. Runtime не может менять fence; триггеры действуют и на старый binary. Retired app-role также теряет финансовые SELECT/IUD, поэтому старый образ не может прочитать устаревший баланс. Повторная миграция сохраняет это ограничение. После принятого переноса запустить Credit remote и отдельно проверить mTLS/readiness. Существующие таблицы Credit сохраняются, без удаления схемы.

Возврат требует остановки writers, завершения in-flight RPC и нового обратного переноса. Снимок берётся после fence нового writer и содержит последние платежи; запуск прежнего образа до проверки checksum запрещён.

```bash
python3 services/servicing/cutover/transfer.py local \
  --credit-service credit_owner --servicing-service servicing_owner --artifacts /secure/r3-reverse
```

После успешного reverse и проверки последнего платежа запустить Credit local. Source Servicing остаётся FENCED. При ошибке импорта/контрольной суммы не включать writer: использовать сохранённые `source-fenced.json` и `target-before.json` для разбирательства. Проверяемое продолжение незавершённого переноса использует `--recover-manifest /secure/r3-forward/source-fenced.json` вместо `--artifacts`: оба writers должны оставаться FENCED, checksum исходной БД должен по-прежнему совпадать со снимком. Добавление `--abort` при тех же проверках возвращает право записи неизменившемуся source. Повреждённый manifest и изменившийся source не позволяют активацию. Эти действия выполняет отдельный owner, runtime не получает права восстановления.

## Проверки

`ServicingEngineTest` — чистые domain/usecase сценарии, включая суммы резерва и rollback через тестовый transaction port. `ServicingPostgresIntegrationTest` требует явные `ALPHA_SERVICING_TEST_JDBC_URL` / `ALPHA_SERVICING_TEST_DB_USER` и использует настоящие JDBC/transactions; без URL тесты пропускаются. `ServicingLiveGrpcTest` подключается к независимо запущенному candidate JAR по настоящему mTLS; фазы open/replay разделяет рестарт runtime с той же БД. Эти разные уровни не заменяют друг друга. Credit `LoanOpeningAtomicityIntegrationTest` проверяет настоящий outbox/rollback/late ack и DISBURSEMENT; его transport loss инъекция явно не объявляется живым RPC.

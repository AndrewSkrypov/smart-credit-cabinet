# Перенос Documents

Оба сервиса и их workers останавливаются. Бэкап Credit, Documents и обоих
object stores обязателен. Runtime-роли не владеют схемой и не могут менять
`document_ownership`; применить общие grants и специализированные SQL этого
каталога. Никаких отдельных флагов только чтения или только записи нет.

1. Администратор блокирует writers: в обеих БД ownership `FROZEN`, epoch N+1.
   Переход ждёт удерживаемые shared row locks. Завершить старые процессы.
2. Запустить Documents JAR с
   `--spring.flyway.enabled=false --cabinet.documents.grpc.enabled=false
   --cabinet.documents.migration.enabled=true`. Настроить target datasource
   owner через `DOCUMENTS_DATABASE_{URL,USER,PASSWORD}`, target LOCAL/S3 через
   `cabinet.storage.*`. Источник: `DOCUMENTS_CREDIT_DATABASE_{URL,USER,PASSWORD}`,
   `DOCUMENTS_CREDIT_LOCAL_DIR`, при S3 также
   `DOCUMENTS_CREDIT_S3_{ENDPOINT,REGION,BUCKET,ACCESS_KEY,SECRET_KEY}`.
   `DOCUMENTS_MIGRATION_DIRECTION=forward`. Секреты только в окружении.
3. Успех CLI означает сверку размеров/SHA всех существующих объектов,
   включая удалённые metadata и READY artifacts. Импорт повторяем. Неудача
   оставляет оба writer FROZEN. Файлы, созданные до отката транзакции,
   допустимы только при точном совпадении байтов на повторе.
4. В Credit owner REMOTE epoch N+1; в Documents owner DOCUMENTS epoch N+1.
   Запустить Credit с `cabinet.documents.mode=remote`, тем же epoch и
   TLS credential allowlisted `spiffe://credit-cabinet/credit`; Documents
   runtime с собственным documents_app datasource. Никакого fallback local
   при gRPC/DB/storage сбое. Внешняя маршрутизация к gRPC отсутствует.
5. Для отката остановить оба runtime, оба owner FROZEN с новым epoch.
   CLI `DOCUMENTS_MIGRATION_DIRECTION=reverse` копирует новые bytes и metadata
   в Credit через отдельную административную роль, проверяет checksum,
   восстанавливает локальные fences в той же транзакции. Затем Credit LOCAL,
   Documents остаётся FROZEN. Запустить local Credit с новым epoch.
6. U26 можно применять только после reverse import и активации LOCAL:
   восстановленные FK валидируют все исторические ссылки. U26 сохраняет
   таблицы ownership и immutable input intent, поэтому новые local Credit
   продолжат работать. Для старого JAR проверять совместимость отдельно.

Не смешивать сохранённые packet IDs/уведомления Credit с metadata Documents.
Старые READY links/IDs/inputHash и storage backend сохраняются. CLI не делает
cutover автоматически и не получает права менять бизнес-снимки договора.
Команда `scripts/check-documents-runtime.py` воспроизводит отдельный PostgreSQL,
реальные Documents и Credit JAR, mTLS и HTTP negative-проверки. Квитанции CI
и Dev требуются отдельно от локальной. S3 миграция поддержана кодом, но её
приёмка требует доступного S3 endpoint; LOCAL gate не является S3-доказательством.


## Обязательный S3 профиль

`--storage-backend s3` требует изолированный SeaweedFS 4.55. В cabinet-ci
запускается закреплённый digest образа, локально обязательны
`DOCUMENTS_TEST_S3_BINARY` и `DOCUMENTS_TEST_S3_BINARY_SHA256`. При отсутствии
fixture профиль завершается ошибкой, а не skip. Случайные bucket/credentials
источника и цели различаются. Каждый объект после forward и reverse получает
HEAD (size) и GET (вычисленный SHA-256); квитанция хранит per-file backend map,
размер и SHA, включая новый latest document и READY artifact.
Используемые флаги сверены с [исходником SeaweedFS 4.55](https://github.com/seaweedfs/seaweedfs/blob/4.55/weed/command/mini.go).
LOCAL квитанция всегда явно оставляет S3 непроверенным. Синтетический clamd
в gate проверяет протокол и fail-closed пути; эффективность антивируса
остаётся отдельной проверкой существующего ClamAV regression suite.

GitHub использует отдельный `.github/workflows/documents-runtime.yml` с native
PostgreSQL и native SeaweedFS. `scripts/prepare-documents-s3-native.py` проверяет
закреплённый SHA-256 официального архива 4.55, затем пишет digest извлечённого
binary для обязательной проверки fixture. Ему не нужен Docker endpoint.
Runtime guard для shared-host Docker остаётся строгим и требует cabinet-ci label.

Upload visibility: Credit owns `document_references` (application association only).
V26 backfills every committed legacy row, including deleted history, and records new
LOCAL inserts in the same transaction. Remote upload may durably store metadata/bytes
before Credit commits; all Credit readers filter by committed reference. The reference
and BUSINESS journal (or CHAT upload receipt/audit) share the Credit transaction.
An optional BUSINESS `Idempotency-Key` and the existing mandatory CHAT key derive a
stable ID scoped by company/application/actor/context; a retry returns the original
owner timestamp/backend/path. A different immutable payload returns 409. Without a
BUSINESS key, an abandoned remote row remains invisible and a new request gets a new ID.
Reverse imports only IDs confirmed by Credit with the same application; expected
unreferenced rows never enter legacy `documents`. Logical delivery parity uses that
same subset. Physical backup must retain pending/orphan bytes and metadata as well,
so a timeout retry survives restore. Do not garbage-collect them during cutover.
V26 adds the reference table and `pipeline_jobs.documents_outages`; U26 retains both
as additive history/retry data. No Documents own-schema/protobuf change is required.
Expected transport outages during DATA_CHECK use durable capped 5..300 second backoff,
without consuming business attempts; stale/expired claims cannot reschedule the job.

All remote write entry points (A12 commit-order inventory):
- `DocumentsBoundary.put`: remote bytes/metadata first, then committed business reference
  and BUSINESS journal or CHAT receipt/audit in one Credit transaction. Unconfirmed rows
  remain physically backed up and cannot be listed, opened, attached, or reverse imported.
- `DocumentsBoundary.markDeleted`: only a durable Credit deletion intent in the journal
  transaction. Readers overlay that committed tombstone; direct removed-file reads deny
  access even if Documents is down. `DocumentsDeletionDelivery` sends it independently,
  with bounded retry; Documents delete is idempotent for the exact immutable timestamp.
  Reverse materializes even an undelivered committed deletion before reopening LOCAL.
- `GeneratedArtifacts.prepareRemote`: frozen input and artifact business reference commit
  together. Within an outer signing/business transaction it sends no RPC. Its durable
  publisher reads committed pending inputs outside transactions and uses stable identities;
  duplicate/lost replies converge through Documents' unique frozen identity.
- Artifact retry/download/notice and reverse require the committed
  `(application_id,offer_id,phase,input_hash)` reference. Notice additionally requires the
  exact delivered input and three READY kinds, then notice+notification outbox commit once.
- V26 backfills every legacy generated artifact into immutable
  `document_artifact_references`, preserving historical hashes without weakening the unique
  current FINAL input. LOCAL inserts and newly frozen current inputs record references in
  the same transaction. Scope binds exist solely in Documents and convey no public access.

Logical parity excludes both document and artifact orphans. Physical backup preserves
all metadata, bytes and pending delivery state. A pending deletion may be represented by
Credit's committed intent until delivery/reverse; cutover verifies that state explicitly.

The immutable `document_upload_operations` table is a command-input ledger, not a file
registry: it holds scope/actor, opaque exact-request hash, PG-precision requested time
and requested backend. A separate bounded two-connection pool reserves it without
application FKs or outer transaction locks, avoiding pool starvation and self-deadlock.
It never authorizes public visibility. Retain it in every Credit backup, including
uncommitted operations: REMOTE rollback -> reverse (no orphan import) -> same-key LOCAL
retry -> forward must preserve UUID, requested time, backend, bytes and one journal.
Preflight must validate stores needed by these operations and pending artifact inputs,
even before their first file exists. Missing requested storage fails closed.

A committed artifact input also freezes requested backend. Reverse creates missing
LOCAL PENDING jobs directly from that exact immutable input, including an unsent FINAL
intent; it never derives replacement final terms from the current application. Published
READY metadata remains immutable; only database timestamp precision is normalized for
cross-database comparison, without altering frozen input JSON or existing published values.
Every worker revalidates token and fresh database expiry after locking the artifact row.
Runtime JaCoCo reports consume their own execution data and never pull the unrelated
default database test suite into the fixture task graph; default test/check coverage stays.

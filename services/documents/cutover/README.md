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

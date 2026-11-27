# R3. Отдельный Documents

База: cfb15c4697f2e0c4771f1d4c85b791163a0422da. Credit V26/U26;
Documents V1. По умолчанию работает прежний локальный путь.

## Владение и доверие

Credit владеет заявкой, offer/signing snapshot, бизнес-авторизацией, packet IDs,
чатом и уведомлениями. Documents владеет метаданными, байтами и состоянием
генерации в своей БД. Внутренний versioned gRPC принимает только mTLS SAN
разрешённого Credit. Запрос несёт company/application/document/context;
повтор с другим scope отклоняется. Внешних HTTP document endpoints у Documents нет.
Credit façade сохраняет privateToBank, BUSINESS/CHAT, uploadedAt и packet policy.
Самоприкрепление BUSINESS в чат не даёт новых прав чтения.

## Перенос и переключение

Только остановка document writers, заморозка jobs и явный fencing epoch.
Экспорт включает deleted metadata, LOCAL/S3 backend, immutable stored name,
размер и вычисленный SHA-256, frozen input JSON/hash и готовые артефакты.
Импорт идемпотентен и отвергает конфликт содержимого. Объекты копируются,
не перемещаются; полная сверка каждого размера/SHA до активации.
При cutover старые SQL writers блокируются на уровне БД; все чтения и записи
переключаются вместе. Ошибка remote не разрешает fallback к stale local bytes.
Откат: остановка всех writers и workers, обратный экспорт/импорт, сверка,
новый epoch, затем локальная активация. Старый JAR не допускается к записи
при remote owner. Ни две БД writer, ни независимый флаг только чтения недопустимы.

## Замороженные артефакты

Credit отправляет уже сохранённый ArtifactInput с hash и contract SHA;
Documents не читает текущий каталог и не парсит русский договор.
Идемпотентность по application/offer/kind/phase/template/inputHash.
Job lease и token защищают completion старого worker. READY bytes неизменяемы;
проверка checksum выполняется при чтении. Готовность фиксируется с outbox
в одной транзакции, повтор доставки не создаёт второго уведомления.

## Приёмка

1. Чистая установка собственной БД и обновление Credit с V23; U26 сохраняет данные.
2. gRPC+mTLS: разрешённый Credit проходит; чужие SAN, company/app/doc/context
   и plaintext отклоняются. Internal endpoint извне 404.
3. HTTP клиент/агент/банк, private statement и revoked mandate сохраняют
   текущую семантику; исторический handoff/return/upload/revoke/submit даёт
   404 при download и self chat attach, без attachment row.
4. Ограниченные сообщения/байты/temp files, scanner fail closed, checksum
   mismatch и storage outage не возвращают непроверенные байты.
5. Frozen input переживает retry/restart; конкурентные jobs дают один READY;
   завершение просроченной lease не перезаписывает READY.
6. Forward/reverse cutover с sole-writer fencing и паритетом metadata/bytes;
   текущие readers после cutover не читают stale локальные объекты.
7. Репозиторный CI gate поднимает own DB + Documents + Credit/mTLS,
   требует ноль skips, сохраняет XML/logs/SHA/tree/JAR digests; обе scan
   matrices, inventory, dependency locks и license records включают сервис.

Локальная сборка и runtime, CI и Dev фиксируются отдельно. До доказательства
всех условий remote cutover запрещён; дизайн сам по себе не закрывает R3.

## Реализованный протокол подтверждения

Documents использует gRPC, durable READY/outbox и ограниченный polling Credit.
Kafka для Documents не требуется. Credit хранит committed references и frozen
artifact inputs в своей бизнес-транзакции. Неудачный upload может оставить
непубличный remote orphan: find/list/packet/chat не показывают его, reverse
не переносит его в legacy tables. Физическая резервная копия сохраняет также
orphan bytes, metadata и idempotency state для точного повтора после timeout.

Upload-operation intent фиксирует UUID, точное содержимое запроса, actor/scope,
PG-microsecond uploadedAt и backend независимо от бизнес-транзакции; публичной
видимости он не даёт. Повтор с тем же ключом сохраняет эти значения при
REMOTE → LOCAL → REMOTE. Подтверждённый replay проходит после текущей проверки
чтения/mandate, но до DRAFT-write guard. Иное содержимое даёт conflict.

Deletion intent и journal создаются одной Credit-транзакцией под application
lock. Tombstone сразу закрывает чтение после commit, durable publisher доставляет
удаление повторяемо. Artifact publisher отправляет только committed frozen input;
подтверждение доставки относится к точному ключу/hash. Reverse материализует
ещё не отправленные FINAL inputs без пересчёта условий. Legacy artifact history
backfill сохраняет исходные JSON/hash, READY и опубликованные error records.

Credit runtime лишается legacy document SELECT/DML после cutover, в том числе
после общего regrant. Узкие owner-aware SECURITY DEFINER lock-функции сохраняют
fence/epoch без UPDATE ownership у приложения. Job claim attempt монотонен;
business failure budget отделён от ожидаемой недоступности Documents.

## Локальные доказательства 2026-11-27

Результаты относятся к указанным исходникам; последующий documentation-only
commit их SHA не меняет. Проверки выполнены на собственном native PostgreSQL 16,
реальных standalone Credit/Documents JAR и mTLS. CI/Dev здесь не заявлены.

| Проверка | Исходники | Результат |
| --- | --- | --- |
| Compile, scoped ktlint, bootJar, selected unit | `cf01871c8a918c4c8d7baeefac1e7922ea3776b2`, tree `28b187167e8440c41c5390d8e7bb4c1d13dbf4cc` | PASS, 16 unit tests, 0 skipped |
| Полный LOCAL runtime | тот же `cf01871` | PASS, 10 runtime tests, 0 failures/errors/skips |
| Полный S3 runtime | `f7697094a450d509cabe3d35eff727529d3a7b80`, tree `7c06a2a601bd44ca29353a16d46c56deaf0f2988` | PASS, 10 runtime tests, 0 failures/errors/skips |

Между LOCAL и S3 изменён только `scripts/documents-s3-fixture.py`. Product JAR
inputs одинаковы. SHA-256 JAR: Documents
`7630b67107a957c44b809b30dd104b7ddfcd3e3c110bc73dfa63fb6bfc153795`,
Credit `62cc1d83465b9c59aa4c437954435de19ff0604cc4eca93fafd8a5249227228f`.

Оба профиля проверили forward/repeat/reverse/repeat/U26, конфликт immutable
document/artifact до копирования с сохранением FROZEN и исходных bytes,
реальный upload-process crash до Credit commit и стабильный повтор,
reverse после orphan, LOCAL retry и повторный REMOTE cutover; unsent FINAL
с исходными amount/rate `12.00`; rollback deletion, concurrent delete/submit,
stale worker lease и generic retry/fail; восстановление DATA_CHECK после
outage сверх business maxAttempts; READY/restart/notice dedup. HTTP проверки
включают draft create при недоступном Documents, revoked agent и исторический
hidden BUSINESS self-attach (404, без attachment row), а также banking/chat/
financing после реального запрета legacy SELECT (42501).

S3 fixture использует isolated SeaweedFS 4.55 с проверенным binary SHA-256
`b0e48a0d0bd26d206cc4e96b3edcfebded8086b47d77f619b9a676f4f51abd36`.
Source/target имеют отдельные buckets/credentials; anonymous/cross-bucket denied.
Strict readiness выполняет PUT/HEAD/GET обоих buckets. После последнего reverse
21 committed объект сверены per-file HEAD/GET/size/SHA; physical target inventory
из 26 объектов включает pending/orphans. Резерв диска ограничивает только
синтетический fixture; продуктовые storage/scanner guards сохранены.

Scanner evidence — синтетический fresh clamd protocol (clean/reject/unavailable),
а не проверка эффективности антивируса. Прежние неуспешные S3 fixture попытки
сохранены отдельно и не считаются product PASS.

Durable evidence на проверявшем хосте:
`/var/tmp/alpha-documents-evidence/build-cf01871/receipt.json`,
`/var/tmp/alpha-documents-evidence/local-runtime-cf01871/`,
`/var/tmp/alpha-documents-evidence/s3-runtime-f769709/`.
В runtime-каталогах сохранены receipt, XML, coverage XML, logs, JSON proofs и
cleanup receipt. В обоих профилях подтверждены отсутствие живых owned PID/start
identities, остановка PG, удаление scratch и свободный общий flock.
Репозиторный `scripts/check-documents-runtime.py` воспроизводит оба профиля;
внешний launcher служил только выбору инструментов/каталога evidence.

Открыты общий CI по интеграционному SHA, inventory/scan/license gates и Dev
поставка с репетицией миграции на её данных. Default остаётся LOCAL; локальные
доказательства не разрешают невалидированный production cutover.

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

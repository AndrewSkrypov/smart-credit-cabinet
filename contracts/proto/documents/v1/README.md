# Documents gRPC v1

Внутренний RPC доступен только allowlisted Credit URI SAN по mTLS. Пользователь,
роль, мандат, submitted packet и контекст видимости проверяются в Credit façade.
Передавать JWT пользователя вместо сервисной mTLS идентичности нельзя.
Scope содержит companyId/applicationId и fencing epoch. Другой scope/context
не открывает существующий объект. Document ID и storedName неизменяемы.

Metadata envelope `metadata_version=1` — UTF-8 JSON DTO из documents-model.
Форматы UUID и Instant строгие; backend LOCAL/S3 переносится вместе с записью.
ArtifactInput передаётся как исходная canonical JSON **строка**, не JsonNode:
`BigDecimal` сохраняет scale, SHA считается по точным UTF-8 байтам. Ни сервер,
ни повтор не запрашивают текущий каталог и не извлекают условия из русского текста.
Изменение обязательных JSON полей требует новой metadata_version или proto v2.
Добавление необязательного поля protobuf сохраняет прежние номера полей.

Unary content ограничен 20 MiB. Общий inbound message ограничен 21 MiB,
metadata — 1 MiB; считывание стора — максимум 20 MiB + 1 байт. Оба сервиса
имеют ограниченные timeout, число исполнителей и очередь RPC. Documents допускает
не более двух активных RPC суммарно по всем соединениям и отклоняет избыток
до чтения upload message; cancel/close освобождают слот ровно один раз. Default deadline
30 секунд покрывает default scanner 10 секунд и S3 total timeout 15 секунд.
При увеличении scanner timeout согласовать `cabinet.documents.grpc.deadline-ms`.
Незавершённый/отменённый RPC откатывает metadata; orphan bytes допускаются
только при точном совпадении в идемпотентном повторе.

INVALID_ARGUMENT означает некорректный scope/metadata, NOT_FOUND — отсутствие
объекта в данном application/context, PERMISSION_DENIED — чужой mTLS SAN,
FAILED_PRECONDITION — fencing, lease или недоступные проверенные bytes.
Credit отображает transport failure как 503 без fallback к local.

READY и own readiness outbox коммитятся атомарно. Credit worker опрашивает
через gRPC вне polling thread кредитного конвейера, сохраняет notice и
notification outbox одной транзакцией. Дедупликация включает immutable input hash.
Kafka для этой границы не требуется.

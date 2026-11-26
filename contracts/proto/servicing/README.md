# Servicing v1

`v1/servicing.proto` — внутренний typed gRPC API между Credit и самостоятельным Servicing. Модуль `libs:servicing-contracts` имеет версию 1.0.0; чистые Kotlin DTO и финансовые модели в `libs:servicing-model` не зависят от protobuf или транспорта. Денежные поля графика и проценты передаются точными десятичными строками. Суммы траншей/резерва — целые RUB, без промежуточного double. Даты — ISO LocalDate, timestamps — ISO Instant.

Совместимость: новые поля добавляются с новыми номерами; номера/имена удалённых полей резервируются. Изменение единицы, валюты, семантики idempotency или подтверждения требует v2. Currency v1 строго RUB. `OpenLoan` возвращает прежнюю долговечную квитанцию для идентичного payload; другой accepted-offer cycle, version или payload той же заявки возвращает ABORTED. Клиент не включает автоматические gRPC retries для финансовых команд; повтор producer выполняет долговечный outbox.

mTLS обязателен, сервер требует доверенную цепочку и точный URI SAN Credit peer. `OpenLoan` разрешён только этому producer. Остальные RPC требуют исходный делегированный Bearer JWT. Изменения — только CLIENT и matching user/company; чтение клиента ограничено компанией. AgentSummary возвращает только version/receiptStatus/paymentDate/action. Проверка действующего мандата находится в Credit непосредственно перед вызовом; сервис не хранит устаревающую копию мандатов.

Генерация: `:libs:servicing-contracts:generateProto`. Проверки: `:libs:servicing-core:test`, `:services:servicing:test`, `:services:credit:test --tests '*LoanOpeningAtomicityIntegrationTest'`. PostgreSQL-тесты Servicing требуют явный `ALPHA_SERVICING_TEST_JDBC_URL`; без него они пропускаются и не считаются живым доказательством.

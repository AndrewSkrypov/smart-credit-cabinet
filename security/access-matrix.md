# Матрица ожидаемого доступа

Источник ожиданий — [access-policy.json](access-policy.json). Полнота проверяется по четырём OpenAPI.

- Порядок HTTP-кодов в каждой строке соответствует roles.
- Для коллекций и создания другой клиент работает со своей компанией; для объекта заявки обращается к объекту владельца.
- Для отметки уведомления сотрудники банка получают собственные синтетические уведомления; другой клиент проверяет чужое уведомление владельца.
- 204 — предусмотренный контрактом успешный ответ без тела при удалении документа и отметке уведомления.
- Внешние /ext/ включены в полноту контракта, но закрыты шлюзом для всех пользовательских ролей. Проверки системного ключа выполняются отдельно внутри Docker-сети.

| Сервис | Операция | Без токена | Клиент-владелец | Другой клиент | Менеджер | Андеррайтер | Риск-администратор |
| --- | --- | --- | --- | --- | --- | --- | --- |
| identity | `GET /api/v1/auth/options` | 200 | 200 | 200 | 200 | 200 | 200 |
| identity | `GET /api/v1/auth/session` | 401 | 200 | 200 | 200 | 200 | 200 |
| identity | `POST /api/v1/auth/sign-in` | 200 | 200 | 200 | 200 | 200 | 200 |
| identity | `POST /api/v1/auth/sign-up` | 200 | 200 | 200 | 200 | 200 | 200 |
| identity | `GET /api/v1/companies/me` | 401 | 200 | 200 | 403 | 403 | 403 |
| identity | `GET /api/v1/users/me` | 401 | 200 | 200 | 200 | 200 | 200 |
| credit | `GET /api/v1/analytics/funnel` | 401 | 403 | 403 | 200 | 200 | 200 |
| credit | `GET /api/v1/applications` | 401 | 200 | 200 | 200 | 200 | 200 |
| credit | `POST /api/v1/applications` | 401 | 201 | 201 | 403 | 403 | 403 |
| credit | `GET /api/v1/applications/{id}` | 401 | 200 | 404 | 200 | 200 | 200 |
| credit | `PUT /api/v1/applications/{id}` | 401 | 200 | 404 | 403 | 403 | 403 |
| credit | `POST /api/v1/applications/{id}/documents` | 401 | 201 | 404 | 403 | 403 | 403 |
| credit | `DELETE /api/v1/applications/{id}/documents/{documentId}` | 401 | 204 | 404 | 403 | 403 | 403 |
| credit | `GET /api/v1/applications/{id}/documents/{documentId}` | 401 | 200 | 404 | 200 | 200 | 200 |
| credit | `GET /api/v1/applications/{id}/events` | 401 | 200 | 404 | 200 | 200 | 200 |
| credit | `POST /api/v1/applications/{id}/info-provided` | 401 | 200 | 404 | 403 | 403 | 403 |
| credit | `POST /api/v1/applications/{id}/offer/accept` | 401 | 200 | 404 | 403 | 403 | 403 |
| credit | `POST /api/v1/applications/{id}/offer/decline` | 401 | 200 | 404 | 403 | 403 | 403 |
| credit | `GET /api/v1/applications/{id}/schedule` | 401 | 200 | 404 | 200 | 200 | 200 |
| credit | `POST /api/v1/applications/{id}/sign` | 401 | 200 | 404 | 403 | 403 | 403 |
| credit | `POST /api/v1/applications/{id}/signing/code` | 401 | 200 | 404 | 403 | 403 | 403 |
| credit | `POST /api/v1/applications/{id}/submit` | 401 | 200 | 404 | 403 | 403 | 403 |
| credit | `POST /api/v1/applications/{id}/withdraw` | 401 | 200 | 404 | 403 | 403 | 403 |
| credit | `GET /api/v1/products` | 200 | 200 | 200 | 200 | 200 | 200 |
| credit | `POST /api/v1/products/match` | 200 | 200 | 200 | 200 | 200 | 200 |
| credit | `GET /api/v1/products/purposes` | 200 | 200 | 200 | 200 | 200 | 200 |
| credit | `GET /api/v1/products/{code}` | 200 | 200 | 200 | 200 | 200 | 200 |
| credit | `GET /api/v1/products/{code}/calculation` | 200 | 200 | 200 | 200 | 200 | 200 |
| credit | `GET /api/v1/rules` | 401 | 403 | 403 | 200 | 200 | 200 |
| credit | `POST /api/v1/rules` | 401 | 403 | 403 | 403 | 403 | 201 |
| credit | `GET /api/v1/underwriting/applications/{id}` | 401 | 403 | 403 | 200 | 200 | 200 |
| credit | `POST /api/v1/underwriting/applications/{id}/approve` | 401 | 403 | 403 | 403 | 200 | 403 |
| credit | `POST /api/v1/underwriting/applications/{id}/counter-offer` | 401 | 403 | 403 | 403 | 200 | 403 |
| credit | `POST /api/v1/underwriting/applications/{id}/reject` | 401 | 403 | 403 | 403 | 200 | 403 |
| credit | `POST /api/v1/underwriting/applications/{id}/request-info` | 401 | 403 | 403 | 200 | 200 | 403 |
| engagement | `POST /api/v1/assistant/messages` | 401 | 200 | 404 | 200 | 200 | 200 |
| engagement | `GET /api/v1/assistant/status` | 401 | 200 | 200 | 200 | 200 | 200 |
| engagement | `GET /api/v1/notifications` | 401 | 200 | 200 | 200 | 200 | 200 |
| engagement | `POST /api/v1/notifications/read-all` | 401 | 200 | 200 | 200 | 200 | 200 |
| engagement | `GET /api/v1/notifications/unread-count` | 401 | 200 | 200 | 200 | 200 | 200 |
| engagement | `POST /api/v1/notifications/{id}/read` | 401 | 204 | 404 | 204 | 204 | 204 |
| external-sim | `GET /ext/bank/v1/clients/{inn}` | 404 | 404 | 404 | 404 | 404 | 404 |
| external-sim | `GET /ext/bureau/v1/reports/{inn}` | 404 | 404 | 404 | 404 | 404 | 404 |
| external-sim | `POST /ext/core/v1/loans` | 404 | 404 | 404 | 404 | 404 | 404 |
| external-sim | `GET /ext/core/v1/loans/{applicationId}` | 404 | 404 | 404 | 404 | 404 | 404 |
| external-sim | `GET /ext/fns/v1/companies/{inn}/summary` | 404 | 404 | 404 | 404 | 404 | 404 |
| external-sim | `GET /ext/registry/v1/companies/{inn}` | 404 | 404 | 404 | 404 | 404 | 404 |
| external-sim | `POST /ext/signing/v1/sessions` | 404 | 404 | 404 | 404 | 404 | 404 |
| external-sim | `GET /ext/signing/v1/sessions/{id}` | 404 | 404 | 404 | 404 | 404 | 404 |
| external-sim | `POST /ext/signing/v1/sessions/{id}/confirm` | 404 | 404 | 404 | 404 | 404 | 404 |
| external-sim | `GET /sim/control` | 200 | 200 | 200 | 200 | 200 | 200 |
| external-sim | `POST /sim/control/reset` | 200 | 200 | 200 | 200 | 200 | 200 |
| external-sim | `PUT /sim/control/sources/{source}` | 200 | 200 | 200 | 200 | 200 | 200 |

# Переключение currentStatuses

Публичный API Credit сохраняет маршрут и авторизацию банковских ролей.
По умолчанию `currentStatuses` вычисляется в PostgreSQL Credit. Допуск к
переключению подтверждается по матрице приёмки после начальной загрузки.

| Служба | Настройка | Назначение |
| --- | --- | --- |
| Credit | `ANALYTICS_STATUS_READ_ENABLED=true` | Проверять доступность проекции перед запросом поля |
| Credit | `ANALYTICS_STATUS_READ_URL` | Внутренний origin Analytics, например `http://analytics:8080` |
| Credit | `ANALYTICS_STATUS_READ_TIMEOUT_MS` | Дедлайн HTTP, по умолчанию 500 мс |
| Analytics | `ANALYTICS_STATUS_READ_ENDPOINT_ENABLED=true` | Открыть `GET /internal/v1/analytics/current-statuses` внутри сети служб |
| Analytics | `ANALYTICS_STATUS_READ_MAX_LAG_RECORDS` | Суммарный бюджет отставания всех партиций, по умолчанию 0 |
| Analytics | `ANALYTICS_STATUS_READ_KAFKA_TIMEOUT_MS` | Дедлайн проверки Kafka, по умолчанию 250 мс |
| Обе | `ANALYTICS_STATUS_READ_TOKEN` | Выделенный общий секрет длиной от 32 символов, передаётся в `X-Analytics-Read-Token` |
| Обе | `APPLICATION_STATUS_KAFKA_TOPIC` | Уникальное поколение потока, совпадает с маркером начальной загрузки |

Analytics проверяет на каждой операции маркер загрузки, смещения
целевой группы по всем партициям и отсутствие записей в DLQ текущего
поколения. Kafka-идентичность Analytics должна читать метаданные его группы,
основного топика и DLQ. Не публикуйте внутренний маршрут через внешний шлюз.

Готовый ответ содержит `generation` и `currentStatuses`. Запрос без
правильного сервисного токена получает `401`. При неготовности ответ
`503` содержит причину в `X-Analytics-Readiness`. Credit проверяет outbox
своего поколения до и после запроса и выбирает SQL при сбое или отставании.

В случае отката выставьте `ANALYTICS_STATUS_READ_ENABLED=false` на всех
репликах Credit. Метрика `cabinet.analytics.current.statuses.read` показывает
выбранный источник и причину. Метрика `cabinet.analytics.projection.readiness`
показывает результат проверки на стороне Analytics.

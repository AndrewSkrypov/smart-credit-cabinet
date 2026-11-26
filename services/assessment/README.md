# Assessment

Assessment запускается отдельно с JDK 21 и своей PostgreSQL. Spring Flyway
применяет миграции из `services/assessment`, начиная с V1. Коды ошибок и счётчики
обработки доступны через состояние своей БД и журнал. HTTP API приложения
выключен. Исходные системы опрашивает только worker активного Kafka поколения.

```bash
./gradlew :services:assessment:bootJar
java -jar services/assessment/build/libs/app.jar
```

Задайте `ASSESSMENT_DB_URL`, `ASSESSMENT_DB_USER` и
`ASSESSMENT_DB_PASSWORD`. Используйте отдельную БД, владельца и роль.
Kafka включается явным `CABINET_ASSESSMENT_KAFKA_ENABLED=true` в обоих
сервисах. Настройки должны совпадать:

| Настройка | Пример для изолированного прогона |
| --- | --- |
| `CABINET_ASSESSMENT_KAFKA_BOOTSTRAP_SERVERS` | `127.0.0.1:19092` |
| `CABINET_ASSESSMENT_KAFKA_ENVIRONMENT` | `synthetic` |
| `CABINET_ASSESSMENT_KAFKA_GENERATION` | `g001` |
| `CABINET_ASSESSMENT_SOURCES_URL` | Закрытый адрес источников для Assessment |
| `CABINET_ASSESSMENT_SOURCES_API_KEY` | Ключ доступа к источникам для Assessment |

Подготовьте topics `alpha.synthetic.assessment.v1.g001.commands` и
`alpha.synthetic.assessment.v1.g001.results`. Отключите автоматическое создание
topics на брокере. Ключ записи — UUID заявки. Группы потребителей включают
окружение, поколение, версию контракта и сторону обмена. Текущая конфигурация
адаптера рассчитана на изолированный Kafka listener. Для эксплуатации
финансовых данных нужны проверенные сетевые политики, аутентификация Kafka,
шифрование и разрешения на topics с командами и результатами.

Режим Credit по умолчанию локальный. Долговечная команда хранит исходный
снимок и принадлежность цикла. Переключение затрагивает новые задачи
обогащения. Под старым поколением результаты больше не применяются после
смены конфигурации. Ожидающие задачи завершаются ручным рассмотрением по
дедлайну. Для отключения транспорта сначала завершите открытые циклы.
U24 отклоняет откат, пока существует открытый удалённый цикл заявки в
обогащении или скоринге.

В Assessment выполните агрегированную проверку ожидания и доставки:

```sql
select stream, completed_at is null as waiting, last_error, count(*)
from assessment_execution group by 1,2,3;
select stream, sent_at is null as pending, last_error, count(*)
from assessment_exchange_outbox group by 1,2,3;
```

В Credit причина ожидания записывается в `pipeline_jobs.last_error`, а
отдельный `assessment_exchange_outbox` показывает повторы доставки команд.
При восстановлении транспорта попытки возобновляются по `available_at`.
Обычные уведомления обрабатываются своей очередью.

## Локальные проверки

Проверка миграций требует локальных исполняемых файлов PostgreSQL 16.
Сквозной сценарий использует дистрибутив Kafka 4.3.2, JDK 21 и собранные JAR.
Сценарий запускает процессы и временные БД на loopback, повторно доставляет
команду и результат, проверяет рестарт Assessment и Kafka, ручное
рассмотрение при неполных фактах и обработку позднего результата.

```bash
e2e/alpha-assessment/verify-migrations.sh "$PG_BIN"
./gradlew :services:credit:bootJar :services:assessment:bootJar
python3 e2e/alpha-assessment/verify_exchange.py \
  --credit services/credit/build/libs/app.jar \
  --assessment services/assessment/build/libs/app.jar \
  --pg-bin "$PG_BIN" --kafka-home "$KAFKA_HOME"
```

Обязательная проверка GitLab и GitHub вызывает `e2e/alpha-assessment/check-ci.sh`
с портом временного PostgreSQL 16 раннера. Она создаёт отдельные пары БД,
проверяет SHA-512 дистрибутива и запускает тест отката входящей транзакции через
`assessmentKafkaTest`. Проверка требует один выполненный тест без пропусков.
Логи сохраняются в `ci-artifacts/assessment/`.

[Границы и матрица R2](../../docs/plans/alpha-r2-assessment-service.md)

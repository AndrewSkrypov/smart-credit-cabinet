# Изолированный пилот Alpha

[План](plans/alpha-hexagonal-refactor.md) · [Матрица приёмки](plans/alpha-hexagonal-acceptance.md) · [Защищённая выкладка на Dev](alpha-dev-rollout.md)

## Проверка инфраструктуры

На локальном изолированном Docker daemon с Compose v2 задайте
`ALPHA_PILOT_KAFKA_IMAGE` со значением `apache/kafka:<version>@sha256:<digest>`
для выбранной JVM-сборки Apache Kafka с KRaft и CLI в `/opt/kafka/bin`.
Зафиксируйте точную ссылку в квитанции прогона. PostgreSQL закреплён тем же
upstream @sha256, который указан в [Dockerfile проекта](../docker/postgres/Dockerfile).
Из корня чистого checkout кандидата запустите:

```bash
mkdir -p ci-artifacts
bash ci/alpha/smoke.sh > ci-artifacts/alpha-infrastructure.tsv
```

Скрипт выделяет новый проект Compose, создаёт временные пароли, проверяет
DDL от `analytics_owner`, права `analytics_app`, два поколения Kafka с отдельными DLQ
и соответствие запущенных образов закреплённым ссылкам. При завершении
скрипт удаляет ресурсы этого прогона. Результат подтверждает зависимости
для A10 и их идентичность для A11. Для полной приёмки нужен интегрированный
прогон с образами Credit и Analytics и синтетическими бизнес-сценариями.

Для отдельного прогона с приложениями задайте три разных синтетических
пароля в `ALPHA_PILOT_PG_ADMIN_PASSWORD`, `ALPHA_PILOT_PG_OWNER_PASSWORD` и
`ALPHA_PILOT_PG_APP_PASSWORD`, затем выделите новый проект Compose:

```bash
alpha_project="cabinet-alpha-$(python3 -c 'import secrets; print(secrets.token_hex(8))')"
alpha_compose=(docker compose --env-file /dev/null -f ci/alpha/compose.yaml
  -p "$alpha_project" --profile alpha-pilot)
"${alpha_compose[@]}" up --wait --wait-timeout 150
```

Внутри приватной сети проекта адреса зависимостей — `kafka:9092` и
`jdbc:postgresql://analytics-db:5432/analytics`. Подключайте к сети только
контейнеры синтетического теста. У Credit остаётся собственный PostgreSQL.
Миграции Analytics используют `analytics_owner`, приложение — `analytics_app`.
Сеть и том Analytics принадлежат одному проекту. Для проверки отказа брокера
используйте `stop kafka` и `start kafka` с тем же Compose-проектом. Топики
хранятся в файловой системе контейнера и переживают stop/start этого
контейнера. После сохранения доказательств остановите тестовые приложения и
выполните `"${alpha_compose[@]}" down --volumes` в этом проекте.

## Подключение к GitLab CI

Корневой pipeline подключает `ci/alpha/gitlab.yml` при `CABINET_ALPHA_PILOT=1`.
Выделенный pipeline runner-canary выполняется отдельно. В изолированном CI
задайте `CABINET_ALPHA_PILOT=1`. Smoke использует закреплённый Kafka digest из репозитория. Оба задания используют существующий `.quality`.
Инфраструктурное задание запускает зависимости, отдельное задание
`alpha-pilot-analytics-jar` собирает Analytics на интегрированном SHA.

Для локальной сборки используйте отдельное чистое рабочее дерево кандидата:

```bash
bash ci/alpha/build-analytics.sh
(cd ci-artifacts/alpha-analytics && sha256sum -c app.jar.sha256)
```

Скрипт передаёт `-PalphaAnalytics=true` только вызову
`:services:analytics:bootJar` и сохраняет JAR, его SHA-256 и Git SHA в
`ci-artifacts/alpha-analytics/`. Штатный backend проверяет ровно четыре
`app.jar`. Запускайте его в своём чистом рабочем дереве, чтобы glob не
захватил оставшийся от пилотной сборки JAR Analytics. Храните результаты
миграций и манифест пилота отдельно от штатного `release.json`.

## Возврат к SQL и восстановление

1. Выберите режим SQL на всех экземплярах Credit и подтвердите выдачу
   `currentStatuses` при остановленном потребителе. На синтетическом
   источнике остановите writers, публикатор Kafka и consumer перед
   переключением поколения. Сохраните SHA, digest, backup и старые
   topic/group для сверки. Обычный возврат на SQL выполняется сменой режима
   чтения. Для возврата версии приложения действует штатная
   [проверка совместимости образа со схемой](../deploy/README.md#репетиция-отката).
2. Если нужен откат данных, используйте только синтетический backup.
   Штатные [backup и restore](deployment.md#восстановление) охватывают
   PostgreSQL платформы и документы. Restore сверяет выпуск с manifest,
   проверяет архив, требует новый пустой `cabinet-prod-restore*` и сам
   запускает штатный стек. Проверьте до restore, что целевой стек
   запускается с чтением из SQL и выключенным Kafka-пилотом. Helm rollback
   возвращает конфигурацию и образы, а восстановление PostgreSQL
   выполняется отдельной процедурой. [Инструкция Helm](../helm/cabinet/README.md)
3. Выделите новую пустую БД Analytics в отдельном проекте и примените
   миграции. Выделите topic, DLQ и group с идентификатором поколения,
   ранее не применявшимся к текущему источнику:

   ```bash
   generation="g$(python3 -c 'import secrets; print(secrets.token_hex(8))')"
   topic="alpha.synthetic.credit.application-status-changed.v1.$generation"
   dlq="$topic.dlq"
   group="alpha-synthetic-analytics-$generation"
   for generation_topic in "$topic" "$dlq"; do
     "${alpha_compose[@]}" exec -T kafka /opt/kafka/bin/kafka-topics.sh \
       --bootstrap-server kafka:9092 --create --topic "$generation_topic" \
       --partitions 3 --replication-factor 1
   done
   ```

   Передайте `APPLICATION_STATUS_KAFKA_TOPIC=$topic` обеим сторонам,
   `ANALYTICS_KAFKA_GROUP_ID=$group` и `ANALYTICS_KAFKA_DLQ_TOPIC=$dlq`
   потребителю. Consumer подписывается на точный topic и начинает с начала
   нового потока. Готовность привяжите
   к текущему поколению. Оставьте offsets и том старой проекции как
   доказательство. Проверьте восстановленные записи Kafka-outbox на
   принадлежность поколению до включения публикатора. Если реализация
   не может гарантировать изоляцию этих записей от нового потока,
   оставьте SQL и остановите восстановление проекции.
4. При остановленных синтетических writers перенесите согласованный
   снимок `(id, version, status)`. После запуска публикатора и потребителя
   проверьте строки и счётчики против SQL, принятые offsets против
   контрольных end offsets по каждой партиции, отсутствие неподтверждённых
   Kafka-outbox и предел лага кандидата. Проверьте сценарий, в котором
   старый topic содержит `N+2`, восстановленная проекция остаётся на `N`,
   а законное событие нового поколения обновляет её до `N+1`. До окончания
   сверки HTTP обслуживает SQL. Допуск к переключению определяется A09
   и A11 в матрице.

## Повтор доставки после FAILED

Пока есть необработанные строки outbox, Credit выдаёт `currentStatuses` из SQL.
После восстановления брокера сверьте БД Credit, среду и поколение потока.
Запишите причину отказа и идентификаторы строки outbox и события журнала в
квитанцию. Для восстановленного из backup источника используйте процедуру
создания нового поколения выше.

На изолированном стенде для повтора в том же поколении выполните следующий
запрос в операторской сессии PostgreSQL. Передайте переменные psql
`failed_outbox_id` и `journal_event_id` с UUID из квитанции. Операция затрагивает
только одну запись в `FAILED`, сохраняя ключ, payload и UUID события.

```sql
UPDATE application_status_outbox
SET state = 'PENDING',
    attempts = 0,
    next_attempt_at = statement_timestamp(),
    updated_at = statement_timestamp()
WHERE id = :'failed_outbox_id'::uuid
  AND event_key = :'journal_event_id'
  AND state = 'FAILED'
RETURNING id, event_key, state;
```

Проверьте изменение одной строки, затем broker ack, состояние `DELIVERED`,
offset потребителя и паритет проекции с SQL. Неуспешная сверка оставляет чтение
в режиме SQL и критерий A07 открытым.

## Квитанция

Запишите local, CI и dev отдельными строками с полным SHA, временем,
командой, кодом и ссылкой на артефакт. Для A11 сохраните хеш манифеста,
закреплённые registry digest, ID запущенных контейнеров и соответствие
разрешённых image config ID. Для собственных образов отдельно сверьте
`org.opencontainers.image.revision` с checkout и хеш собранного JAR.
Добавьте замеры лага, Kafka-outbox, ошибок и ответ SQL при возврате.
Для штатных образов уже действуют валидатор манифеста
`ci/gitlab/release-manifest.py` и `deploy/verify-release.py`.
Сохраняйте только нужные структурированные поля без паролей, полных
конфигураций Docker и SQL-дампов.

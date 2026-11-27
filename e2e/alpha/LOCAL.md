# Локальный прогон Alpha

Запускайте из корня checkout одного интегрированного кандидата. Понадобятся его готовые артефакты Credit и Analytics, отдельный PostgreSQL 16+, Python 3.11+, клиентские программы PostgreSQL и локальный Compose-проект Kafka из [инструкции поставки](../../docs/alpha-pilot-operations.md). Рецепт ниже использует loopback или частный Unix-сокет и синтетические PostgreSQL БД. Указывайте фактический SHA артефакта при каждом HTTP-снимке.

## Подготовка

- В отдельном PostgreSQL создайте `alpha_qa_credit`, пустую `alpha_qa_restored`, `alpha_qa_analytics_old` и `alpha_qa_analytics_new`. Выполните штатные миграции Credit и обеих БД Analytics. В `alpha_qa_restored` восстановится уже мигрированный backup. Для полного A02 подготовьте отдельный исходный Credit на базовом SHA и отдельную пустую мигрированную БД с такой же фикстурой.
- Задайте локальные `PGHOST`, `PGPORT`, `PGUSER` и при необходимости парольный файл. Для [bootstrap](../../services/analytics/ops/README.md) создайте локальный `PGSERVICEFILE` с профилями `alpha-qa-credit`, `alpha-qa-restored`, `alpha-qa-old`, `alpha-qa-new` для этих БД. Два профиля Analytics должны принадлежать владельцу с правом на TEMP. Наблюдения ниже выполняются через отдельные TCP или Unix-соединения.
- Запустите Kafka по инструкции поставки с именем Compose-проекта `cabinet-alpha-…` и экспортируйте его как `ALPHA_QA_COMPOSE_PROJECT`. Создайте два source topic и два DLQ с одинаковым числом партиций. Разрешите Credit и Analytics доступ в тестовую сеть Kafka. Для HTTP выделите отдельные локальные порты процессов. Приватный порт Analytics открывайте только на loopback тестового хоста. Оставьте обычный pipeline выключенным (`PIPELINE_ENABLED=false`) во время сверки фикстуры.

```bash
set -euo pipefail
umask 077
qa_run=$(mktemp -d /tmp/alpha-qa-run.XXXXXXXX)
qa_sha=$(git rev-parse HEAD)
export PYTHONDONTWRITEBYTECODE=1
python3 e2e/alpha/local-env.py > "$qa_run/session.env"
source "$qa_run/session.env"
export ANALYTICS_STATUS_READ_TOKEN=$(python3 -c 'import secrets; print(secrets.token_hex(32))')
export ALPHA_QA_ANALYTICS_READ_TOKEN="$ANALYTICS_STATUS_READ_TOKEN"
qa_suffix=${qa_run##*.}
qa_old_topic="alpha.synthetic.qa.$qa_suffix.old"
qa_new_topic="alpha.synthetic.qa.$qa_suffix.new"
qa_old_group="alpha-qa-$qa_suffix-old"
qa_new_group="alpha-qa-$qa_suffix-new"
database=(bash e2e/alpha/recovery-db.sh)
broker=(bash e2e/alpha/kafka-evidence.sh)
recovery=(python3 e2e/alpha/recovery.py)
funnel=(python3 e2e/alpha/funnel.py)
bootstrap=(bash services/analytics/ops/bootstrap-status.sh)

qa_compose=(docker compose --env-file /dev/null -f ci/alpha/compose.yaml
  -p "$ALPHA_QA_COMPOSE_PROJECT" --profile alpha-pilot)
for topic in "$qa_old_topic" "$qa_old_topic.dlq" "$qa_new_topic" "$qa_new_topic.dlq"; do
  "${qa_compose[@]}" exec -T kafka /opt/kafka/bin/kafka-topics.sh \
    --bootstrap-server kafka:9092 --create --topic "$topic" --partitions 3 --replication-factor 1
done
```

При отдельном нативном Kafka 3.9 установите `ALPHA_QA_KAFKA_BIN` в абсолютный
путь к `bin`, а `ALPHA_QA_KAFKA_BOOTSTRAP` в `127.0.0.1:<порт>`.
Вместо команды Compose запускайте `"$ALPHA_QA_KAFKA_BIN/kafka-topics.sh"
--bootstrap-server "$ALPHA_QA_KAFKA_BOOTSTRAP"` с теми же аргументами.
`kafka-evidence.sh` использует эти настройки для публикации, записи broker offsets
и отдельного диагностического consumer. Его доказательства можно сохранять в `/var/tmp`.

Синтетические сессии рассчитаны на тестовый Credit с этим новым `JWT_SECRET` и пустым `IDENTITY_URL`. При действующем тестовом Identity задайте его токены вместо этих сессий. Для `withdraw` клиенты должны представлять компании из фикстуры с UUID, оканчивающимися на `000005` и `000010`. Токены, профиль PostgreSQL и `session.env` остаются в тестовой среде. Перед передачей артефактов уберите их и файлы дампов.

Для Credit выставьте `APPLICATION_STATUS_EVENTS_ENABLED=true` и `APPLICATION_STATUS_KAFKA_BOOTSTRAP_SERVERS` из тестового стека. Для Analytics используйте те же bootstrap servers, `ANALYTICS_KAFKA_ENABLED=true` и `ANALYTICS_KAFKA_GROUP_ID`/ `ANALYTICS_KAFKA_DLQ_TOPIC` нужного поколения. `APPLICATION_STATUS_KAFKA_TOPIC` должен совпадать у обоих экземпляров в рамках одного поколения. Значения для переключения и ограничения доступа приведены в [операционном контракте P5](../../services/analytics/ops/status-read.md). В командах ниже `qa_credit_url`, `qa_restored_url`, `qa_analytics_url` и `qa_baseline_url` означают origin соответствующих процессов с явными loopback-портами.

## Начальная загрузка и паритет

На время импорта оставьте writers и consumer выключенными. Повторный bootstrap используйте с тем же файлом. Маркер, строки и версии должны сохраниться.

```bash
"${database[@]}" seed alpha_qa_credit
"${database[@]}" credit alpha_qa_credit > "$qa_run/sql.json"
CREDIT_SNAPSHOT_SERVICE=alpha-qa-credit ANALYTICS_SNAPSHOT_SERVICE=alpha-qa-old \
    "${bootstrap[@]}" "$qa_old_topic" "$qa_run/old.csv"
"${database[@]}" generation alpha_qa_analytics_old > "$qa_run/old-generation.json"
"${recovery[@]}" wait-projection alpha_qa_analytics_old --checkpoint initial > "$qa_run/projection.json"
"${funnel[@]}" verify --sql "$qa_run/sql.json" --projection "$qa_run/projection.json" --snapshot-versions
CREDIT_SNAPSHOT_SERVICE=alpha-qa-credit ANALYTICS_SNAPSHOT_SERVICE=alpha-qa-old \
    "${bootstrap[@]}" "$qa_old_topic" "$qa_run/old.csv"
"${database[@]}" generation alpha_qa_analytics_old | cmp "$qa_run/old-generation.json" -
"${database[@]}" projection alpha_qa_analytics_old | cmp "$qa_run/projection.json" -
```

В отдельной исходной БД повторите `seed` и `credit` через `recovery-db.sh` и сравните выгрузку с `sql.json` через `cmp`. Запустите исходный Credit с теми же синтетическими ролями и снимите исходный контракт. В кандидате запустите Kafka consumer и Credit с выключенным переключателем чтения, оставив запись Kafka включённой. В тени пройдите тот же публичный маршрут.

```bash
"${funnel[@]}" capture --url "$qa_baseline_url" \
  --sha e5bce6d79e478c7f20fc33fff6d1820c85ee2f63 --mode sql > "$qa_run/baseline.json"
"${funnel[@]}" capture --url "$qa_credit_url" --sha "$qa_sha" --mode shadow > "$qa_run/shadow.json"
"${funnel[@]}" verify --sql "$qa_run/sql.json" --projection "$qa_run/projection.json" --snapshot-versions \
  --baseline "$qa_run/baseline.json" --candidate "$qa_run/shadow.json"
```

## HTTP, роли и возврат к SQL

Выполните этот этап на неподвижной исходной фикстуре, до проверки восстановления. Снимайте счётчики на том же экземпляре Credit до и после запроса. На время этих проб избегайте параллельных запросов воронки. Из проверки ролей и периода ровно пять запросов должны дойти до выбора источника. Секрет маршрута передаётся только прямо в Analytics.

```bash
capture_read() {
  local label=$1 mode=$2 source=$3 reason=$4
  curl --noproxy '*' -fsS --max-time 10 "$qa_credit_url/actuator/prometheus" > "$qa_run/$label-before.prom"
  "${funnel[@]}" capture --url "$qa_credit_url" --sha "$qa_sha" --mode "$mode" > "$qa_run/$label.json"
  curl --noproxy '*' -fsS --max-time 10 "$qa_credit_url/actuator/prometheus" > "$qa_run/$label-after.prom"
  "${funnel[@]}" verify-source --before "$qa_run/$label-before.prom" --after "$qa_run/$label-after.prom" \
    --source "$source" --reason "$reason"
}
```

1. Перезапустите Credit с `ANALYTICS_STATUS_READ_ENABLED=true`, адресом Analytics и общим сервисным токеном. В Analytics включите `ANALYTICS_STATUS_READ_ENDPOINT_ENABLED=true`, consumer и параметры его старого поколения. Выполните `"${funnel[@]}" probe-private --url "$qa_analytics_url" --sha "$qa_sha" --expect ready --generation "$qa_old_topic" --snapshot "$qa_run/sql.json"`. Ожидаются `401` для обычного банковского JWT, отсутствующего и неверного токена, `200` для сервиса. Затем выполните `capture_read projected projected projection ready`.
2. Остановите только Analytics, оставив Credit, PostgreSQL и Kafka работающими. Выполните `capture_read refused fallback sql remote_unavailable`. Для отдельной тестовой сетевой задержки ожидается `timeout` и используется отдельный снимок. Возобновите Analytics и повторите `capture_read recovered projected projection ready`. Убедитесь, что ответ Credit остаётся в пределах синтетического HTTP-бюджета, согласованного для кандидата.
3. Оставьте Analytics готовым и перезапустите Credit с `ANALYTICS_STATUS_READ_ENABLED=false`. Выполните `capture_read off off sql disabled`. На тестовом шлюзе проверьте `404` для пути `/internal/v1/analytics/current-statuses` без передачи сервисного токена. Сохраните результат и конфигурацию границы сети.
4. Дополните A09 отдельными снимками с текущим недоставленным outbox, контролируемым лагом и недоступностью Kafka. Для доступного, но неготового Analytics используйте `probe-private --expect unready --reason "$reason"`. Сохраняйте исходные и повторные наблюдения в разных каталогах. Причины P5 включают `broker_unavailable`, `lag`, `offset_unavailable`, `snapshot_pending` и `dlq`. Недоставленный outbox текущего поколения проверяет Credit с причиной `outbox_pending`. Снимки сравнивайте со SQL той же фазы.

```bash
"${funnel[@]}" verify --sql "$qa_run/sql.json" --baseline "$qa_run/baseline.json" \
  --candidate "$qa_run/projected.json" --candidate "$qa_run/refused.json" \
  --candidate "$qa_run/recovered.json" --candidate "$qa_run/off.json"
```

## Восстановление с записью старого поколения

Сохраните старый Analytics и его Kafka-группу работающими. Перезапустите исходный Credit с `APPLICATION_STATUS_EVENTS_WORKER_ENABLED=false`, оставив запись outbox включённой. Отзыв заявки № 10 создаёт запись `PENDING` старого поколения. После отзыва остановите writers исходного Credit. Заявка № 5 остаётся на `v1`.

```bash
"${recovery[@]}" withdraw --url "$qa_credit_url" --application stuck > "$qa_run/stuck-http.json"
"${database[@]}" credit-evidence alpha_qa_credit > "$qa_run/backup-credit.json"
"${database[@]}" dump alpha_qa_credit "$qa_run/credit.dump"
sha256sum "$qa_run/credit.dump" > "$qa_run/credit.dump.sha256"

"${recovery[@]}" emit-old | "${broker[@]}" publish "$qa_old_topic"
"${recovery[@]}" wait-projection alpha_qa_analytics_old --checkpoint old > "$qa_run/old-projection.json"
"${database[@]}" analytics-evidence alpha_qa_analytics_old > "$qa_run/old-receipts.json"
"${broker[@]}" capture "$qa_old_topic" "$qa_old_group" "$qa_run/old"
"${broker[@]}" offsets "$qa_old_topic.dlq" > "$qa_run/old-dlq-offsets.txt"

"${database[@]}" restore alpha_qa_restored "$qa_run/credit.dump"
"${database[@]}" credit-evidence alpha_qa_restored > "$qa_run/restored-before-credit.json"
"${database[@]}" credit alpha_qa_restored > "$qa_run/restored-sql.json"
cmp "$qa_run/backup-credit.json" "$qa_run/restored-before-credit.json"
```

Старый topic содержит синтетическое `v3 REJECTED` для № 5 и второй ID, отсутствующий в дампе. Перед инициализацией новой проекции запустите восстановленный Credit в SQL-режиме, сделайте `capture --mode sql` на `qa_restored_url` и проверьте его с `verify --fixture restored --sql "$qa_run/restored-sql.json" --baseline <файл>`. Повторите при включённом P5 со здоровым Credit и недоступным новым Analytics. Зафиксируйте выбор SQL и причину по `verify-source` на экземпляре Credit, который обслужил запрос.

Импортируйте восстановленный снимок в **пустую** новую БД Analytics, сохраняя writers остановленными. Проверьте, что старый topic всё ещё содержит `v3`. Первоначальная новая проекция должна содержать `v1` и отсутствие второго ID.

```bash
CREDIT_SNAPSHOT_SERVICE=alpha-qa-restored ANALYTICS_SNAPSHOT_SERVICE=alpha-qa-new \
    "${bootstrap[@]}" "$qa_new_topic" "$qa_run/restored.csv"
"${recovery[@]}" wait-projection alpha_qa_analytics_new --checkpoint restored > "$qa_run/new-initial-projection.json"
"${database[@]}" analytics-evidence alpha_qa_analytics_new > "$qa_run/new-initial-receipts.json"
"${database[@]}" generation alpha_qa_analytics_new > "$qa_run/new-initial-generation.json"
```

Теперь запустите consumer и worker восстановленного Credit с новым topic, новой группой и новым DLQ. Восстановленный worker должен оставить старую строку `PENDING` с нулём попыток. После положительной готовности новой проекции отзовите заявку № 5 из восстановленного Credit. Kafka и Analytics должны получить `v2 WITHDRAWN`.

```bash
"${recovery[@]}" withdraw --url "$qa_restored_url" --application recovered > "$qa_run/restore-http.json"
"${recovery[@]}" wait-projection alpha_qa_analytics_new --checkpoint recovered > "$qa_run/new-final-projection.json"
"${database[@]}" analytics-evidence alpha_qa_analytics_new > "$qa_run/new-final-receipts.json"
CREDIT_SNAPSHOT_SERVICE=alpha-qa-restored ANALYTICS_SNAPSHOT_SERVICE=alpha-qa-new \
    "${bootstrap[@]}" "$qa_new_topic" "$qa_run/restored.csv"
"${database[@]}" projection alpha_qa_analytics_new | cmp "$qa_run/new-final-projection.json" -
"${database[@]}" generation alpha_qa_analytics_new > "$qa_run/new-final-generation.json"
"${database[@]}" credit alpha_qa_restored > "$qa_run/recovered-sql.json"
"${database[@]}" credit-evidence alpha_qa_restored > "$qa_run/recovered-credit.json"
"${broker[@]}" capture "$qa_new_topic" "$qa_new_group" "$qa_run/new"
"${broker[@]}" offsets "$qa_new_topic.dlq" > "$qa_run/new-dlq-offsets.txt"
"${recovery[@]}" verify "$qa_run" --old-topic "$qa_old_topic" --new-topic "$qa_new_topic" \
  --old-group "$qa_old_group" --new-group "$qa_new_group"
```

`verify` сверяет SHA-256 обоих снимков с маркерами, версии и счётчики, SQL с проекцией, журналы Credit, Kafka outbox, брокерные сообщения, receipt и позиции по партициям. Если capture пришёлся между коммитом проекции и ack/подтверждением outbox, дождитесь zero lag и `DELIVERED` в пределах дедлайна, сохраните повторные наблюдения и снова выполните `verify`. Повтор той же загрузки после применения `v2` должен сохранить новое значение. Для дополнительной проверки пересечения в отдельном прогоне сохраните CSV через `export-status-snapshot.sql`, выполните переход до импорта и затем примените тот же CSV и накопленное событие. Для A06/A07 разрыв на границе commit/ack проверяется отдельно тестовым барьером.

## Доказательства A01–A13

| ID | Что приложить по интегрированному SHA |
| --- | --- |
| A01 | `python3 contracts/events/credit/check_contract.py`, реальная запись Kafka из outbox, прочитанный ключ и payload, rejection/DLQ для невалидной записи |
| A02 | Снимки одинаковых HTTP-запросов и OpenAPI базы и кандидата, код `funnel.py verify` |
| A03 | Согласованный SQL и независимый экспорт проекции, версии из снимка/событий, пустая карта, скрытые статусы и исчезновение последнего ключа |
| A04 | Коды по ролям у живого Credit в тени, при чтении проекции, fallback и выключенном флаге |
| A05 | JUnit-отчёт `ApplicationStatusOutboxIntegrationTest`, rollback и повтор с проверкой версии, журнала, обеих outbox и pipeline job |
| A06 | Реальные Kafka offsets и PostgreSQL receipts для повторов, старых версий и рестарта между commit БД и ack |
| A07 | Бизнес-операция и полученное уведомление при остановленном брокере, outbox и метрики, повтор при разрыве ack/подтверждение, redrive после FAILED |
| A08 | Повтор загрузки, пересечение снимка с записью, хеши, маркеры, offsets/DLQ, backup-сценарий и наблюдения восстановления выше |
| A09 | Частный ответ и отказ по роли, доказательство сетевой границы, дельты источника и причин, успешный SQL-ответ, включение проекции после восстановления |
| A10 | Отчёты Gradle и миграций, тесты владельца и runtime-роли на изолированном стеке, совместимость прежнего образа с новой схемой |
| A11 | Пилотные задания GitLab, исходный SHA, хеш JAR и registry digests запущенных образов, отдельные протоколы local, CI и dev |
| A12 | Независимое ревью полного diff, проверка исходного SHA образов и привязка перечисленных доказательств с причинами открытых пунктов |
| A13 | JUnit и Playwright по [существующей команде регрессии](README.md#a12a13-ревью-и-регрессии), доставка уведомлений в тестовом получателе |

До интеграции можно выполнить статические проверки и прогнать существующие SQL-фикстуры, контракт и захват SQL-ветки воронки. Для уже работающего изолированного стека можно использовать `credit-evidence`, `analytics-evidence` и `kafka-evidence.sh capture`. Команды bootstrap и generation требуют V2 Analytics, доказательство поколения outbox — V20 Credit, `probe-private` и `verify-source` требуют P5. Наличие файлов и отдельный SQL-прогон оставляют сквозные проверки открытыми.

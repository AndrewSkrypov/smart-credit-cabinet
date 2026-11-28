# Именное демо на ATF

Приглашения открывают `https://dev.credit-cabinet.ru/demo/jury`. Стенд работает в Compose-проекте `cabinet-jury-atf` с отдельными PostgreSQL, Valkey, томами и JWT-секретом. Dev Caddy завершает TLS и направляет этот префикс в gateway стенда. Маршрут `/demo/` продолжает обслуживать публичный проект.

## Подготовить стек

Запускайте на Dev-хосте из чистого checkout проверенного коммита. Используйте новый каталог состояния с правами 0700 и свежие синтетические данные.

```bash
install -d -m 0700 /var/lib/cabinet-jury-atf
JURY_ENV=/var/lib/cabinet-jury-atf/cabinet.env
python3 deploy/configure.py --domain dev.credit-cabinet.ru --output "$JURY_ENV"
python3 - "$JURY_ENV" <<'PY'
from pathlib import Path
from sys import argv

path = Path(argv[1])
values = dict(line.split("=", 1) for line in path.read_text().splitlines())
values.update(EDGE_SUBNET="172.30.43.0/28", CADDY_EDGE_IP="172.30.43.2",
              GATEWAY_EDGE_IP="172.30.43.3")
path.write_text("".join(f"{key}={value}\n" for key, value in values.items()))
PY
```

Сверьте свободные подсети и память с конфигурацией хоста. При первом запуске создайте внутреннюю сеть. При обновлении проверьте её через `docker network inspect`.

```bash
docker network create --internal --subnet 172.30.42.0/28 cabinet-dev-jury-edge
export GATEWAY_CONFIG="$PWD/deploy/runtime/cabinet-jury-atf/gateway.conf"
export SOURCE_REVISION="$(git rev-parse HEAD)"
export COMPOSE_PROFILES=
python3 deploy/set-release.py "$SOURCE_REVISION" --env-file "$JURY_ENV"
python3 deploy/render-gateway.py --caddy-ip 172.30.42.2 --output "$GATEWAY_CONFIG"
jury_compose=(docker compose --env-file "$JURY_ENV" -p cabinet-jury-atf
  -f docker-compose.yml -f deploy/compose.yaml -f deploy/compose.jury-atf.yaml
  --profile prod)
"${jury_compose[@]}" config --quiet
"${jury_compose[@]}" up -d --build --wait --wait-timeout 300
```

Скопируйте закрытый JSON со списком участников в каталог состояния и задайте права
0600. Ключи JSON перечислены в `SEATS` в `deploy/jury-invites.py`. Имена и выданные
карточки хранятся в каталоге состояния оператора.

```bash
python3 -m venv /var/lib/cabinet-jury-atf/operator-venv
/var/lib/cabinet-jury-atf/operator-venv/bin/pip install bcrypt==5.0.0 qrcode==8.2
/var/lib/cabinet-jury-atf/operator-venv/bin/python deploy/jury-invites.py \
  --roster /var/lib/cabinet-jury-atf/roster.json \
  --output /var/lib/cabinet-jury-atf/first-issue --hours 48
```

Скрипт выбирает контейнеры только по меткам Compose проекта `cabinet-jury-atf`
и переименовывает восемь существующих синтетических записей. При перевыпуске укажите
новый каталог `--output` и нужную позицию через `--only`. Предыдущее неиспользованное
приглашение этой позиции отзывается. Передайте каждому участнику только его HTML-карточку.
Для проверки полного входа используйте контрольное приглашение и перевыпустите его
перед передачей, поскольку обмен расходует ссылку.

## Подключить Dev Caddy

Разместите `deploy/jury-atf-api.caddy` в файле, доступном Caddy только для чтения. Задайте его абсолютный путь через `CABINET_JURY_CADDY_ROUTES`. Добавьте `deploy/compose.dev-jury-edge.yaml` последним к фактически запущенному набору оверлеев `cabinet-dev`, включая `compose.dev-demo-edge.yaml`. Используйте настройки самого Dev для этой команды.

Проверьте `compose config --quiet` и `caddy validate --config /etc/caddy/Caddyfile` в одноразовом контейнере с теми же оверлеями. Под lock выкладки пересоздайте только Caddy командой `up -d --no-deps caddy`. Сохраните оверлей в операторском вызове для следующих выкладок, пока стенд открыт для жюри.

Проверьте загрузку SPA и ассетов под `/demo/jury/`, одноразовый обмен ссылки и открытие кабинета. Повтор обмена и анонимное чтение данных должны завершаться отказом. Проверьте, что `/demo/__ready` продолжает видеть публичный контур.

Для отключения исключите оверлей и пересоздайте только Caddy. Остановите `cabinet-jury-atf` с тем же набором Compose-файлов. Тома сохраняются для проверок.

# Docker-стенд PUMP

## ВМ организаторов

На Linux с Docker Engine, Compose v2.24.4+ и 8 GiB RAM распакуйте переданный исходный выпуск
и из корня запустите отдельный синтетический проект. Если порты заняты, выберите другой локальный порт.

```sh
GATEWAY_PORT=18081 docker compose -p cabinet-jury-local -f docker-compose.yml -f deploy/compose.jury.yaml \
  up -d --build --wait --wait-timeout 300
```

Откройте `http://localhost:18081`. Для проверки на изолированной ВМ используйте SSH-туннель
`ssh -L 18081:127.0.0.1:18081 <хост>`. В этом проекте включены демонстрационные данные. Войдите
как `client@demo.test`, `agent@demo.test` или `underwriter@demo.test` с синтетическим паролем из
[основного README](../README.md). У агента есть мандат на компанию клиента. Запуск не требует
GitLab, платного API или GPU. Docker загружает публичные базовые образы и зависимости сборки.
Опциональный профиль AI по умолчанию выключен. Остановка `docker compose -p cabinet-jury-local -f docker-compose.yml -f deploy/compose.jury.yaml stop`
сохраняет данные.

## Dev по HTTPS

На выделенной ВМ скрипт запускается от выделенного пользователя с доступом к Docker. В каталоге
`/var/lib/cabinet-dev` остаются настройки, состояние выпусков, учётки жюри и согласованные копии.
Пользователь должен владеть каталогом с правами 0700. Создайте `cabinet.env` один раз на сервере.

```sh
python3 deploy/configure.py --domain <dev-FQDN> \
  --release-manifest release.json --output /var/lib/cabinet-dev/cabinet.env
```

Используйте отдельное DNS-имя, направленное на ВМ, и входящие TCP 80/443 для Caddy. Для первой
выдачи сертификата нужны рабочие DNS и ACME. Чтобы передать Caddy адрес владельца для регистрации ACME, добавьте `--email <ACME-email>`. Дополнительные профили задаются
`CABINET_OPTIONAL_PROFILES=monitoring` в настройках хоста. Python 3.11+ и пакет `bcrypt`
используются при первом создании синтетических учётных записей.

Защищённый GitLab job размещает код и `release.json` в одном новом каталоге
`/srv/cabinet-dev/releases/<полный-SHA>`. В файле есть `revision` и объект `images` с семью
компонентами `identity`, `credit`, `engagement`, `external-sim`, `frontend`, `postgres`,
`swagger`. Каждая ссылка содержит `@sha256:<digest>` и указывает в тот же проект Registry.
Для доступа к Registry используйте постоянный защищённый Docker credential helper или
защищённый Docker config пользователя, с правом только `read_registry`.

```sh
python3 "/srv/cabinet-dev/releases/$SHA/deploy/dev-release.py" \
  --state-dir /var/lib/cabinet-dev \
  --release-manifest "/srv/cabinet-dev/releases/$SHA/release.json" \
  --host-memory-gib 32
```

Скрипт сериализует операции файловой блокировкой, сохраняет старый выпуск и снимает согласованную
копию перед изменением схемы. Перед остановкой сервисов `start.py` скачивает образ по digest
и сверяет OCI revision. Постоянный проект `cabinet-dev` сохраняет тома PostgreSQL, Valkey,
документов и Caddy. Каталоги предшествующих выпусков нужны для проверки и восстановления.
Общие демонстрационные учётки выключены. Отдельные синтетические учётки и активный клиентский мандат
создаются один раз. Пароли остаются в `/var/lib/cabinet-dev/jury.json` с правами 0600.
Отдавайте жюри только назначенные учётные данные через закрытый канал.

Добавьте `--verify-only` к команде для повторной проверки текущего выпуска и входа трёх ролей.
JSON-отчёт показывает digest манифеста, OCI revision и локальные ID работающих образов,
SHA-256 JavaScript по HTTPS, статусы входа, мандат агента и проверки запрета доступа.
Повтор того же защищённого job продолжает выпуск при временном сбое. Для независимой
проверки ролей используйте `deploy/verify-jury.py --url https://<FQDN> --credentials <закрытый-json>`.

## Репетиция отката

Сделайте отдельный рабочий каталог, локальный env и уникальную сеть Docker. Для примера
ниже укажите каталог старого выпуска с его `release.json`, путь согласованной копии
из прогона dev и запустите от отдельного оператора с `read_registry`.

```sh
python3 deploy/configure.py --local --release-manifest release.json --output /private/restore.env
```

Задайте в созданном файле отдельные `EDGE_SUBNET`, `CADDY_EDGE_IP`, `GATEWAY_EDGE_IP` и
`HTTP_PORT`, `HTTPS_PORT`, `PUBLIC_CABINET_URL`; согласуйте порты HTTPS и URL.
Если копия использовала S3, укажите также `--storage s3` при создании env и сохраните
имя бакета из manifest копии. После этого, из старого выпуска:

```sh
python3 deploy/restore.py /private/backups/<backup> --project cabinet-prod-restore-dev \
  --env-file /private/restore.env --release-manifest release.json --local
python3 deploy/verify.py --project cabinet-prod-restore-dev --env-file /private/restore.env
python3 deploy/verify-release.py --project cabinet-prod-restore-dev --env-file /private/restore.env \
  --expected-revision "$OLD_SHA" --release-manifest release.json --local
```

Сравните отчёт восстановления и повторите вход сохранённых синтетических учёток с
`--ca deploy/runtime/cabinet-prod-restore-dev/local-ca.crt`. Если новая схема обратно совместима,
можно запустить защищённую операцию с предыдущим каталогом и manifest. Если миграция оборвалась
или схема несовместима, сначала проверьте восстановление старой копии в отдельном проекте,
затем планируйте перенос трафика. Сохраняйте исходные тома.

### Необязательный GPU bridge в Dev

CI/CD включает `CABINET_DEV_OLLAMA_SSH=true` в защищённом Dev env после
приёмки Grafana, отдельным выпуском под `resource_group: cabinet-dev`.
Флаг по умолчанию выключен. Включение доступно для `cabinet-dev` с Registry
manifest и без локального профиля `ai`. В manifest нужен `ssh-bridge`
с OCI digest. Wrapper проверяет OCI revision и запущенный образ так же,
как для engagement. Для существующего SHA повторный запуск `dev-release.py`
выполняет проверку и сам по себе не применяет смену флага.

Dev-оператор готовит `/etc/pump/engagement-ollama-ssh`, который монтируется
только в bridge. Владелец каталога и файлов — root, группа — 10002, режимы
каталога — 0750, файлов `id_ed25519` и `known_hosts` — 0440. Скрипт
копирует файлы в свой tmpfs с режимом 0600. Используется отдельный Dev-ключ,
разрешённый на GPU с исходного IP Dev. Prod Secret в Dev не монтируется.

SSH bridge устанавливает исходящий TCP-сеанс к `45.145.191.121:43423`.
`known_hosts` закрепляет ED25519 под алиасом `gpu-vm3-pump-model`; проверенный
отпечаток `SHA256:d+E5zEPdZyHp7D2PoRekxCnt43372dmVkIy1Ov5QJoA`.
На GPU разрешён только прямой проброс в `127.0.0.1:11434`.
В Compose bridge использует сетевой namespace engagement и слушает только
loopback. Порты наружу не публикуются. Для запуска процесса engagement
доступность GPU не требуется.

Перед включением в Dev на изолированном проекте проверьте ожидаемый digest
через `/api/tags`, реальный ответ `MODEL` через `/api/v1/assistant/messages/stream`
с синтетическим клиентом и ответ `RULES` на вопрос о персональном решении
банка. На том же изолированном проекте остановите только bridge, подтвердите
переход на правила и восстановление модели без рестарта engagement.
После защищённого rollout проверьте Dev, Grafana и метрики assistant.

При откате под тем же lock сначала удалите только `ollama-ssh-bridge` через
`docker compose rm --stop --force ollama-ssh-bridge` с текущим набором
Compose-файлов, включая GPU overlay. Установите `CABINET_DEV_OLLAMA_SSH=false`
и `OLLAMA_URL=` в защищённом Dev env и примените ранее проверенный выпуск
своим штатным rollback job. Смена флага на той же ревизии требует явного
пересоздания engagement под тем же lock. Проверьте ответ `RULES` и HTTPS
Grafana.

### Публичное демо

Используйте выделенную ВМ для `demo.dev.credit-cabinet.ru`, пустой Compose-проект
`cabinet-dev-demo` и для первого запуска образы Registry из manifest
`e5bce6d79e478c7f20fc33fff6d1820c85ee2f63`. Для TLS DNS должен указывать на эту
ВМ, Caddy должен принимать TCP 80/443. Запускайте эти команды из рабочей копии,
в которой есть демо-оверлей. Для обновлений используйте проверенный манифест нового выпуска. Секреты, резервные копии и тома обычного Dev и Prod
сюда не переносятся. Docker на этой ВМ получает отдельный доступ `read_registry`.

```sh
sudo install -d -m 0700 /var/lib/cabinet-dev-demo
sudo python3 deploy/configure.py --domain demo.dev.credit-cabinet.ru --email <ACME-email> \
  --release-manifest release.json --output /var/lib/cabinet-dev-demo/cabinet.env
sudo python3 - /var/lib/cabinet-dev-demo/cabinet.env <<'PY'
from pathlib import Path
import sys
path = Path(sys.argv[1])
network = {
    "EDGE_SUBNET": "172.30.41.0/28",
    "CADDY_EDGE_IP": "172.30.41.2",
    "GATEWAY_EDGE_IP": "172.30.41.3",
}
lines = [key + "=" + network.get(key, value) for key, value in
         (line.split("=", 1) for line in path.read_text().splitlines())]
path.write_text("\n".join([*lines, "CABINET_ISOLATED_DEMO=true"]) + "\n")
PY
sudo docker volume ls --filter label=com.docker.compose.project=cabinet-dev-demo
sudo python3 deploy/start.py --project cabinet-dev-demo \
  --env-file /var/lib/cabinet-dev-demo/cabinet.env --release-manifest release.json --no-build
```

Перед первым запуском список томов проекта должен быть пуст. В закрытом env
`DEMO_ACCOUNTS=false` и `SIM_CONTROL_ENABLED=false` сохраняются. Демо-оверлей
включает общие записи только для Identity и её миграции. На демо отключены
внешняя почта и модель. Caddy закрывает регистрацию, приглашения, смену пароля
и профиля общих учёток. По HTTPS проверьте `/api/v1/auth/options` и вход по демо-кнопкам,
а также отказ на маршрутах регистрации и изменения пароля. Для остановки сохраните
тома и выполните `deploy/stop.py` с теми же параметрами проекта, env и manifest.
Не восстанавливайте резервную копию другого контура в проект демо.

На обычном Dev после приёмки демо можно включить `CABINET_DEV_DEMO_LINK=true` в
закрытом env. Caddy ответит 204 на GET `/_dev/demo-enabled` и перенаправит GET
`/_dev/demo` на фиксированный открытый адрес. Для отката выключите флаг и
пересоздайте только Caddy с обычным набором Compose-файлов под блокировкой
деплоя. Повторная передача текущего SHA в `dev-release.py` не применяет флаг.

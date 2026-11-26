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

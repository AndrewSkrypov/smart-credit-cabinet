# Переключение GitLab CI на Vault

Dev переносит два защищённых файловых параметра GitLab CI из scope `dev` в KV v2 `pump/data/dev/ci-deploy`: `CABINET_DEV_SSH_KEY` и `CABINET_DEV_KNOWN_HOSTS`. Чтение 15 полей приложения через AppRole на `pump-final` проверялось отдельно. GitLab CE выдаёт `id_tokens`, Vault проверяет подпись через JWT API. [GitLab ID tokens](https://docs.gitlab.com/ci/secrets/id_token_authentication/) · [Vault JWT API](https://developer.hashicorp.com/vault/api-docs/auth/jwt)

## Перед Dev

1. Сверить live protected SHA `feat/final-platform-release`, активные jobs и scopes. Перенести кандидат на этот SHA и проверить project CI lint с обычным и Alpha-контекстом. Подтвердить сохранность `prod-deploy`, `release-images`, quality gates, Alpha и артефактов продвижения. Согласовать один точный защищённый SHA с GitLab и CI/CD.
2. Из контекста выделенного CD01 (runner ID 2) доказать разрешение `vault.pump.internal` и TCP 8200 до Vault с проверкой CA/SNI. Предпочтительная частная цель — `10.129.0.33`. Ранее CD01 наблюдался на GitLab как `10.128.0.37`; подтвердить источник для Vault с учётом возможного NAT. Если существующий ingress не пропускает job, согласовать только измеренный источник, сначала для CD01. Из Vault проверить HTTPS 443 к `gitlab.credit-cabinet.ru`, OIDC discovery и объявленный `/oauth/discovery/keys` с обновлением ключей. Разрешённый путь от `pump-final` обслуживает его AppRole. Текущий Dev SSH требует ключ ещё до подключения, а forced key отключает forwarding.
3. Подготовить root-owned `/etc/pump-vault-ci/client.json` с адресом `https://vault.pump.internal:8200` и `ca.pem` с проверенной CA. Дать выделенному runner право чтения. При отсутствии private DNS согласовать mapping только внутри выделенной службы раннера с сохранением SNI; проверить из реального network и mount context job.
4. Подтвердить mount `pump-gitlab`, exact issuer и RS256. Импортировать два файла без записи в лог через краткоживущего writer с create/read только на точную запись, CAS 0 для новой записи и byte parity. Legacy переменные сохраняются. Политика `pump-ci-dev` читает точную запись и разрешает `auth/token/revoke-self`. Подготовить только canary role командой `python3 ci/gitlab/vault/render-role.py pump-ci-dev-canary <protected-merge-sha>`. Применить её согласованным каналом и сверить read-back. Общий bootstrap может переустановить роли и для этого handoff не используется.

## Dev canary, деплой и возврат

- `dev-vault-canary` доступен вручную на точной защищённой ветке в pipeline типов push/API/web. Environment action `verify` не создаёт deploy record. Job в `cabinet-dev` сверяет два файла в памяти, отзывает Vault токен и выдаёт только статус `2/2`. Дочерней команды и Dev-аттестации нет. Даже при зелёном pipeline нужен фактический успех этого job. [GitLab environment actions](https://docs.gitlab.com/ci/yaml/#environmentaction)
- Проверить реальные подписанные project ID/path, ref/path/type, protected claim, environment/action, runner ID, pipeline source, SHA и exact audience. `job_id` служит аудиту, wrapper сверяет его с текущим job. Role `pump-ci-dev-canary` разрешает `verify`, role `pump-ci-dev` — `start`. Подтвердить реальные deny для другой action, роли, ref/SHA и окружения без выдачи значений. JWT ограничивает job, а доступ людей к ручному play остаётся политикой GitLab.
- Согласовать окно Alpha и отсутствие второго writer. Перед одним штатным `dev-deploy` проверить активные deploy jobs, отсутствие ручной выкладки, состояние `pending.json` и свободный `/var/lib/cabinet-dev/deploy.lock`. Сверить SHA, digest `release.json`, OCI refs и предыдущую принятую версию. После canary применить отдельно отрендеренную роль `pump-ci-dev`. Оператор CD01 атомарно устанавливает root-owned `/etc/pump-vault-ci/dev-active-sha` с ровно согласованным полным SHA. Настройка GitLab разрешает переопределение pipeline variables с роли Developer, поэтому переключатель располагается на выделенном раннере. Проверить Vault audit read/revoke, успешный job, прежний digest и реальный Dev smoke.
- При отсутствии файла активации работает прежний путь. При несовпадении SHA или ошибке Vault job завершается до вызова скрипта выкладки. Для возврата после отказа до выкладки дождаться terminal, дать оператору снять файл активации, сверить legacy variables, lock и pending, затем согласовать одну штатную попытку того же релиза. Если deploy уже начался, использовать существующий механизм отката под тем же lock. Файл активации сам по себе состояние приложения не меняет.

## Отдельная приёмка Prod

1. После успешной Dev-выкладки через Vault подтвердить в том же pipeline точный protected SHA, immutable `release.json`, OCI digests и Dev-аттестацию. Подтвердить TCP 8200 и доверенный TLS из service context CD03, runner ID 4, с измеренным источником `10.128.0.23`. Не запускать canary, пока владелец CI/CD не подтвердит окно и отсутствие параллельного writer.
2. Использовать только `pump/data/prod/ci-deploy` с полем `CABINET_PROD_VALUES`. Для начальной записи нужен отдельный краткоживущий writer на этот путь и CAS 0. Проверить byte parity и сохранить исходную переменную. Scoped JIT kubeconfig и jury-файл остаются в GitLab.
3. Офлайн отрендерить `python3 ci/gitlab/vault/render-role.py pump-ci-prod-canary <protected-sha>`. После разрешения применить только эту роль, сверить exact read-back и запустить `prod-vault-canary` до загрузки JIT kubeconfig. Job ждёт прежние release и Dev jobs, проверяет их checksum и provenance, читает только Prod запись, сверяет 1/1 и отзывает сессию. Он использует GitLab environment `production`, action `verify`, не запускает Helm и не выдаёт deploy record. Подтвердить реальные deny для CD01, Dev, неверного SHA и action `start`.
4. Для реальной выкладки отдельно согласовать роль `pump-ci-prod` на тот же SHA с action `start`, fresh scoped kubeconfig и одного Helm writer. Оператор CD03 атомарно ставит root-owned `/etc/pump-vault-ci/prod-active-sha` с этим полным SHA. `prod-deploy` сверяет Dev checksum до вызова wrapper. Без файла остаётся legacy-путь, при несовпадении SHA или ошибке Vault wrapper останавливается до Helm. Зафиксировать Vault audit read/revoke, immutable refs и реальный smoke.
5. Возврат после Vault-отказа до Helm: дождаться terminal, снять только `prod-active-sha`, сверить исходную переменную, срок scoped kubeconfig и отсутствие writer, согласовать один retry того же релиза. Если Helm начал менять выпуск, сначала применить штатный rollback с оператором. Dev marker отдельный. Legacy-копии сохранять до подтверждённых фактических deploy и отдельного согласования отзыва.

## Выпуск с защищённой ветки dev

Профиль `dev-branch-release` использует ту же Dev KV-запись и доверенный Vault.
Роль `pump-ci-dev-branch` ограничена защищённой веткой `dev`, API/web pipeline,
окружением `dev`, действием `start` и runner #2. Её конфигурацию можно
сгенерировать офлайн командой
`python3 ci/gitlab/vault/render-role.py pump-ci-dev-branch`.

Существующий `dev-deploy` проверяет контекст защищённого API/web pipeline,
runner #2 и действие `start` в окружении `dev`. Пока runner не активировал
Vault через `/etc/pump-vault-ci/dev-active-sha`, используются существующие
защищённые файловые переменные GitLab со scope `dev`. При совпадении marker
с SHA выпуска wrapper сверяет точные SHA, pipeline и job в JWT и берёт файлы
из Vault. Несовпадение SHA или ошибка Vault останавливают job.

Перед SSH сверяется актуальная вершина `dev`. Диагностика использует действие
`verify` с проверкой той же вершины и дерева второго родителя merge-коммита.

# GitLab PUMP

- Группа и проект: [PUMP](https://gitlab.credit-cabinet.ru/pump), [smart-credit-cabinet](https://gitlab.credit-cabinet.ru/pump/smart-credit-cabinet).
- Registry проекта: `registry.credit-cabinet.ru/pump/smart-credit-cabinet`.
- Исходный репозиторий: [GitHub](https://github.com/AndrewSkrypov/smart-credit-cabinet). Историческая ветка по умолчанию — `main`. Стартовый SHA защищённой ветки для проверки релиза `feat/final-platform-release` — `1ae8ab1ad08692745cd5c680a08b6d1d54ab7490`.
- GitLab CE установлен пакетом Omnibus на `gitlab-alfa`. TLS завершается на встроенном NGINX. Для работы нужны TCP 443 и 22; TCP 80 используется для HTTP-01 и перенаправления на HTTPS.
- Fingerprint SSH ED25519 для `gitlab-alfa`: `SHA256:SRrQvG9iQ3MI2ZFxGw0CwPlZRQuAQ4ZLCpujVMp+nvs`.

## Раннеры

- `cabinet-ci` — сборка, без доступа к production. Пилотный `ci-build-node-04` использует отдельный Docker Rootless под `pump-ci` и отдельный файловый каталог.
- `cabinet-release` — защищённый раннер на `ci-build-node-01` с собственным Docker Rootless под `pump-release`.
- `cabinet-deploy` — защищённый CD. Пилотный `cd-deploy-node-01` выполняет задания под `pump-cd`. Учётные данные для кластера выдаются только этому проекту и целевому окружению.
- Служба `gitlab-runner-alfa` и конфигурация `/etc/gitlab-runner-alfa/config.toml` принадлежат PUMP. Существующие `gitlab-runner.service` и `gitlab-runner-main.service` обслуживают другие проекты. Лимит параллелизма — один job на службу. Для Compose используйте `COMPOSE_PROJECT_NAME=pump-${CI_PIPELINE_ID}-${CI_JOB_ID}`.

## Подтверждённые проверки

- Импортировано 26 веток и один аннотированный тег. `main` и `feat/final-platform-release` защищены. Прямой push и force push отключены, merge требует зелёный pipeline и закрытые обсуждения.
- Четыре учётные записи — `dmitriy`, `igor`, `daniel`, `andrew` — имеют роль Developer. Открытая регистрация и email отключены. Первый вход требует смены пароля.
- [Pipeline №13](https://gitlab.credit-cabinet.ru/pump/smart-credit-cabinet/-/pipelines/13) проверил все три тега и push/pull Registry по digest `sha256:63befd8f98039aa3e870b28811e00f69bfd9118fff60c2c5ab2812c20e680444`.
- Лимит CI-артефактов — 512 МБ, срок хранения по умолчанию — 14 дней. Политика Registry ежедневно рассматривает только теги формата `ci-<число>`, сохраняет десять новых тегов и исключает теги релизов из очистки.
- Для dev используются защищённые переменные окружения `dev`. Read-only credential Registry находится в отдельном Docker config пользователя `cabinet-deploy`.

## Проверка и откат

1. Сравните списки heads и tags до появления веток, специфичных для GitLab. На обоих URL выполните `git ls-remote --refs --heads --tags <url> | LC_ALL=C sort`. Сравнивайте SHA самого тега и SHA веток. Открытые и закрытые PR остаются доступны в GitHub. Для их отдельного импорта нужен авторизованный штатный GitHub importer.
2. Проверьте `gitlab-ctl status`, HTTPS UI, API и локальные `/-/health` и `/-/readiness`. Из тестового job проверьте авторизованные push и pull одного уникального тега Registry и сравните digest.
3. Создайте бэкап данных командой `sudo gitlab-backup create`, конфигурации — `sudo gitlab-ctl backup-etc`. Для восстановления держите защищённую копию вне ВМ. Проверьте контрольные суммы, соответствие версии GitLab CE и наличие файла GitLab secrets в защищённом конфигурационном архиве.
4. При остановке миграции поставьте раннеры PUMP на паузу в GitLab и остановите только `gitlab-runner-alfa` и выделенные `pump-ci-docker` и `pump-release-docker`. Сохраните архивы и обратитесь к Edge для отката DNS. GitHub остаётся доступен по исходному URL. При восстановлении используйте отдельную ВМ с точно той же версией CE и архивы данных и конфигурации.

# Изолированная приёмка R3

`run.py` создаёт собственный PostgreSQL cluster, сертификаты и артефакты. Не использует Docker и не обращается к существующим кластерам. По завершении останавливает только собственный runtime и PG. Подготовленный PostgreSQL binary tree передаётся явно; нужны `bin/initdb`, `bin/pg_ctl`, `bin/psql` под `usr/lib/postgresql/<version>` и его `usr/lib/x86_64-linux-gnu` библиотеки. JDK21 и Gradle8.10.2 передаются существующими путями. Большие outputs/cache задаются в `/tmp`.

```bash
python3 services/servicing/acceptance/run.py \
  --pg-root /path/to/native/postgres/root --pg-version 16 \
  --java-home /tmp/alpha-servicing-tools/jdk \
  --gradle /tmp/alpha-servicing-tools/gradle/bin/gradle \
  --gradle-user-home /tmp/alpha-servicing-gradle \
  --read-only-dependency-cache /home/itech/.gradle/caches \
  --artifacts /tmp/alpha-servicing-acceptance-UNIQUE \
  --receipt-copy /var/tmp/alpha-servicing-evidence/UNIQUE.json
```

Harness сам берёт `/tmp/alpha-heavy-jvm.lock` с ограниченным ожиданием. Его не нужно оборачивать вторым flock. Ни один дочерний JVM/PG не наследует lock descriptor. `--write-locks` допустим только при подготовке собственных Gradle lock-файлов до окончательного кандидата; финальную приёмку повторяют без него на чистом tree.

Порядок: сборка и core tests; fresh migrations двумя owner-процессами JAR; Credit domain/authorization/atomicity integration tests с PostgreSQL; настоящий local-domain legacy aggregate; fenced forward export/import; Servicing PG concurrency/rollback tests; самостоятельный bootJar и mTLS live open; restart/replay/последний платеж; реальная Creditoutbox→gRPC→Servicing доставка с producer rollback до ack commit; fenced reverse с последним платежом и checksum. Проверяется ненулевое число JUnit cases без skips, failures и errors. Любая ошибка оставляет failed receipt и журналы, а не ready-метку.

Legacy seed и injected CoreBanking находятся в явно обозначенных тестовых fixtures. gRPC, оба хранилища, транзакции, миграции и отдельный Servicing process в live-цепочке реальные. Проверка самого внешнего банковского HTTP/CoreBanking simulator относится к общей бизнес-приёмке и не подменяется этим harness.


CI использует отдельный mandatory job `servicing-integration` из `ci/gitlab/quality.yml`. Режим `--ci-postgres-image <reviewed postgres16.15 digest> --pg-bin "$(pg_config --bindir)"` не требует runner initdb/pg_ctl: он создаёт один новый container с loopback port и labels job/project/component через явно заданный `CABINET_CI_DOCKER_HOST`. Проверяются CI identity, Unix socket и `cabinet-ci=true` label; default Docker socket никогда не используется. Точный container ID сохраняется в ci-resource.json, штатное завершение и отдельный after_script cleanup удаляют только проверенные ресурсы этого job. Чужие контейнеры и dependency images не удаляются. Native local режим сохраняется.

Оба режима создают отдельные owner/app роли с разными случайными паролями, SCRAM hash и отклонением неверного пароля. Runtime запускается только как app. Env дочерних процессов составляется из allowlist инструментальных PATH/locale/temp/certificate paths и явных synthetic properties. DB URLs, SPRING_APPLICATION_JSON, MIGRATION_ONLY, PGSERVICE, чужие JWT/Java options не наследуются. Proxy env передаётся только при явном `--inherit-proxy-env`. SOURCE_REVISION берётся из проверяемого Git HEAD. CI публикует receipt, phase logs и JUnit results; TLS keys, password files и PGservice config в artifacts не входят.

Для ограниченного локального диска допускаются `--outputs-dir <own build outputs>` и `--project-cache-dir <own task cache>`: повторно используются только собственные outputs, выбранные tests принудительно запускаются, их skips запрещены. `/tmp` quota учитывается отдельно от df. Устаревшие source/code результаты не принимаются как финальный gate: требуется чистый tree и неизменившийся HEAD.


Forward содержит явный crash checkpoint сразу после target activation и до Credit marker/receipt. Storage fixture проводит новый target payment, active abort должен отказать, повторный resume завершает только metadata и сохраняет этот платеж. Затем live gRPC выполняет обычный draw по импортированному legacy счёту: scheduled UUID меняются при тех же payment numbers. Reverse проверяет новые UUID, paid history и checksum полного состояния. Эти сценарии защищают от повторного import поверх уже активного владельца и unique-key конфликта при замене графика.

Reverse также прерывается до target activation; abort прерывается сразу после source reactivation. После нового source payment повтор abort должен только завершить Credit marker, не импортировать старые данные; затем полный reverse сохраняет этот платёж.

V27/V2 включают `loan_operations` в тот же writer fence, snapshot/checksum,
forward/reverse import и retired-runtime regrant. Fixture выполняет локальный
транш с ключом до forward, повторяет его через реальный gRPC без изменения
текущего счёта, создаёт новый remote-транш и после reverse повторяет его через
LOCAL engine. В обоих направлениях возвращается первый immutable result;
новые платежи и остатки сохраняются. Gate сравнивает все operation keys,
fingerprints, results и timestamps и проверяет запрет чтения retired copies
после общего regrant, в том числе на checkpoint отказавшего переноса.

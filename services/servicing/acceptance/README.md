# Изолированная приёмка R3

`run.py` создаёт собственный PostgreSQL cluster, сертификаты и артефакты. Не использует Docker и не обращается к существующим кластерам. По завершении останавливает только собственный runtime и PG. Подготовленный PostgreSQL binary tree передаётся явно; нужны `bin/initdb`, `bin/pg_ctl`, `bin/psql` под `usr/lib/postgresql/<version>` и его `usr/lib/x86_64-linux-gnu` библиотеки. JDK21 и Gradle8.10.2 передаются существующими путями. Большие outputs/cache задаются в `/tmp`.

```bash
python3 services/servicing/acceptance/run.py \
  --pg-root /path/to/native/postgres/root --pg-version 16 \
  --java-home /tmp/alpha-servicing-tools/jdk \
  --gradle /tmp/alpha-servicing-tools/gradle/bin/gradle \
  --gradle-user-home /tmp/alpha-servicing-gradle \
  --read-only-dependency-cache /home/itech/.gradle/caches \
  --artifacts /tmp/alpha-servicing-acceptance-UNIQUE
```

Harness сам берёт `/tmp/alpha-heavy-jvm.lock` с ограниченным ожиданием. Его не нужно оборачивать вторым flock. Ни один дочерний JVM/PG не наследует lock descriptor. `--write-locks` допустим только при подготовке собственных Gradle lock-файлов до окончательного кандидата; финальную приёмку повторяют без него на чистом tree.

Порядок: сборка и core tests; fresh migrations двумя owner-процессами JAR; Credit domain/authorization/atomicity integration tests с PostgreSQL; настоящий local-domain legacy aggregate; fenced forward export/import; Servicing PG concurrency/rollback tests; самостоятельный bootJar и mTLS live open; restart/replay/последний платеж; реальная Creditoutbox→gRPC→Servicing доставка с producer rollback до ack commit; fenced reverse с последним платежом и checksum. Проверяется ненулевое число JUnit cases без skips, failures и errors. Любая ошибка оставляет failed receipt и журналы, а не ready-метку.

Legacy seed и injected CoreBanking находятся в явно обозначенных тестовых fixtures. gRPC, оба хранилища, транзакции, миграции и отдельный Servicing process в live-цепочке реальные. Проверка самого внешнего банковского HTTP/CoreBanking simulator относится к общей бизнес-приёмке и не подменяется этим harness.

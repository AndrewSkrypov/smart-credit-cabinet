# Локальный стенд финального агентского сценария

Для синтетического прогона на Linux нужен Python 3.11+, JDK 21, PostgreSQL 16,
Nginx и Valkey. Нативный запуск использует новый каталог состояния,
отдельные роли БД, отдельные процессы и порты. HTTP-шлюз слушает только
`127.0.0.1`. Сервисы по умолчанию слушают `127.77.0.1`, также loopback.
Скрипт фиксирует PID и время начала каждого своего процесса. Команда
`down` останавливает их и сохраняет данные и журналы.

Миграции запускаются штатными JAR под отдельными ролями владельцев.
Nginx берёт маршруты API и ограничения из Git выбранной ревизии и
отдаёт собранный React с того же origin. Генерируются отдельные
пароли и ключи. Почта и локальная модель выключены. Работают
синтетические аккаунты и внешние симуляторы. Без указанного ClamAV
загрузка файлов закрыта. Для сценариев со счётом и выпиской задайте
`--clamd` и `--clam-db` с действующим комплектом сигнатур. Проверка
подлинности и свежести файлов выполняется штатным Credit.

## Сборка и запуск

В отдельном чистом checkout выбранного полного SHA выполните:

```bash
export JAVA_HOME=/home/itech/.jdks/corretto-21.0.9
export PATH="$JAVA_HOME/bin:$PATH"
export GRADLE_USER_HOME="$(mktemp -d /tmp/alpha-final-gradle.XXXXXXXX)"
export npm_config_cache="$(mktemp -d /tmp/alpha-final-npm.XXXXXXXX)"
artifact_dir="$(mktemp -d /tmp/alpha-final-artifacts.XXXXXXXX)"
revision="$(git rev-parse HEAD)"

./gradlew --no-daemon --max-workers=2 \
  :services:identity:bootJar :services:credit:bootJar \
  :services:engagement:bootJar :services:external-sim:bootJar
(cd frontend && npm ci && npm run build)
for service in identity credit engagement external-sim; do
  cp "services/$service/build/libs/app.jar" "$artifact_dir/$service.jar"
done
cp -a frontend/dist "$artifact_dir/frontend"
printf '%s\n' "$revision" > "$artifact_dir/source-revision.txt"
```

Далее из worktree со скриптом выполните команды ниже. Переменные
`pg_bin`, `pg_libs`, `nginx_bin` и `valkey_bin` указывают на
ваши локальные двоичные файлы или каталоги. Для системного PostgreSQL
значение `pg_libs` можно оставить пустым.

```bash
runtime_root="$(mktemp -d /tmp/alpha-final-runtime.XXXXXXXX)"
run_state="$runtime_root/state"
python3 scripts/final-agent-runtime.py --state-dir "$run_state" up \
  --revision "$revision" --artifacts "$artifact_dir" \
  --java "$JAVA_HOME/bin/java" --pg-bin "$pg_bin" --native-libs "${pg_libs:-}" \
  --nginx "$nginx_bin" --valkey "$valkey_bin" --base-port 18680

python3 scripts/final-agent-runtime.py --state-dir "$run_state" status
python3 scripts/final-agent-runtime.py --state-dir "$run_state" smoke \
  > "$runtime_root/smoke.json"
```

Интерфейс и API доступны на `http://127.0.0.1:18680`. Проверка
`smoke` открывает SPA, запрашивает публичный каталог и входит под
синтетическими ролями агента, клиента и менеджера. Она проверяет
портфель, воронку, ответы `401/403` и закрытие `/internal/*`.
`state.json` содержит Git SHA и SHA-256 JAR и файлов SPA. Журналы
приложений и миграций находятся в `$run_state`. Для второго экземпляра
выберите свободный диапазон из восьми портов и новый каталог состояния.

## Интегрированный кандидат и Playwright

Чтобы проверить интегрированный пакет, соберите в отдельном checkout
все четыре сервиса и SPA одного полного SHA. Запустите их в новом
каталоге состояния с тем же `--revision`. Сценарные SQL-фикстуры
интегрированного кандидата применяйте по инструкции QA только к
синтетической БД. Её административный сокет находится в
`$run_state/pg-socket`, порт PostgreSQL равен базовому порту плюс пять,
локальный администратор — `cabinet`.

Команды для тестов из чистого checkout того же SHA:

```bash
cd e2e
npm ci
npm run typecheck
CABINET_URL=http://127.0.0.1:18680 PLAYWRIGHT_CHANNEL=chrome npm test -- --project=desktop
```

По завершении выполните из корня worktree со скриптом:

```bash
python3 scripts/final-agent-runtime.py --state-dir "$run_state" down
```

[Штатный запуск в Compose](deployment.md) · [Проверки Playwright](../e2e/README.md)

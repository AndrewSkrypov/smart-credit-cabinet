# Умный кредитный кабинет предпринимателя

Прототип цифрового кредитного конвейера для малого и микробизнеса (Альфа Хак 2026).
Предприниматель подбирает продукт, подаёт заявку и видит её путь до результата; банк
обрабатывает заявку в том же продукте: проверка полноты, данные из реестров и БКИ, скоринг,
решение, оффер, подписание и выдача.

> **Статус: каркас (этап 1 из 9).** Работают регистрация компании по ИНН, вход по ролям,
> единая точка входа, документация API и CI. Заявки и конвейер — следующие этапы, см.
> [PLAN.md](PLAN.md).

## Документы

| Документ | О чём |
| --- | --- |
| [docs/research.md](docs/research.md) | Персоны, JTBD, карта историй, приоритеты MoSCoW/RICE |
| [docs/process-model.md](docs/process-model.md) | Границы процесса, этапы, данные, скоринг и кредитная логика |
| [docs/state-machine.md](docs/state-machine.md) | Статусы заявки, переходы, инварианты |
| [docs/architecture.md](docs/architecture.md) | Сервисы, взаимодействие, безопасность |

## Запуск

Нужны Docker с Compose v2 и ~4 ГБ свободной памяти.

```bash
docker compose up --build -d
```

Первая сборка занимает 5–10 минут (скачиваются зависимости Gradle и npm). Затем:

| Адрес | Что |
| --- | --- |
| http://localhost:8080 | Кабинет |
| http://localhost:8080/swagger/ | Swagger UI всех сервисов |

Остановить: `docker compose down` (данные сохраняются в томах), начисто: `docker compose down -v`.

### Тестовые учётные записи

Все данные синтетические. Пароль у всех — `Demo2026`. На странице входа есть кнопки демо-доступа.

| Email | Роль |
| --- | --- |
| client@demo.test | Предприниматель (ИП, ИНН 000100000004) |
| manager@demo.test | Кредитный менеджер |
| underwriter@demo.test | Андеррайтер |
| risk@demo.test | Риск-администратор |

ИНН демо-компаний начинаются с `00`: региона с таким кодом нет, поэтому они не совпадут с
реальным бизнесом.

### Переменные окружения

Все имеют рабочие значения по умолчанию; пример — [.env.example](.env.example).

| Переменная | По умолчанию | Назначение |
| --- | --- | --- |
| `GATEWAY_PORT` | 8080 | Порт кабинета на машине |
| `POSTGRES_USER`, `POSTGRES_PASSWORD` | cabinet / cabinet | Учётная запись PostgreSQL |
| `JWT_SECRET` | dev-значение | Ключ подписи токенов, не короче 32 байт; с профилем `prod` dev-значение не запустится |
| `DEMO_ACCOUNTS` | true | `false` — демо-учётные записи создаются заблокированными |

## Разработка

```bash
# Postgres для интеграционных тестов (порт 55432, чтобы не мешать локальному Postgres)
docker run -d --name cabinet-test-pg -p 55432:5432 -e POSTGRES_USER=cabinet -e POSTGRES_PASSWORD=cabinet -e POSTGRES_DB=cabinet postgres:16-alpine
for db in identity credit engagement; do docker exec cabinet-test-pg psql -U cabinet -c "create database $db"; done

DB_PORT=55432 ./gradlew build          # компиляция, ktlint, тесты всех сервисов
./gradlew ktlintFormat                 # автоисправление стиля Kotlin
cd frontend && npm ci && npx tsc --noEmit && npm run lint && npx vitest run
```

Нужен JDK 21 (Gradle берёт версию из toolchain) и Node.js 20+.

## Стек

| Часть | Технологии |
| --- | --- |
| Бэкенд | Kotlin 2.1, Spring Boot 3.5, Spring Security (JWT), Spring Data JPA, Flyway, PostgreSQL 16 |
| Фронтенд | React 18, TypeScript, MUI 5, Vite |
| Инфраструктура | Docker Compose, nginx (gateway), Swagger UI, GitHub Actions, Trivy |

Версии зафиксированы: `gradle/libs.versions.toml` и BOM Spring Boot для бэкенда,
`frontend/package-lock.json` для фронтенда, теги образов в `docker-compose.yml`.

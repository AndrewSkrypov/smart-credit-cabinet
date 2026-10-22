# Умный кредитный кабинет предпринимателя

Прототип цифрового кредитного конвейера для малого и микробизнеса (Альфа Хак 2026).
Предприниматель подбирает продукт, подаёт заявку и видит её путь до результата; банк
обрабатывает заявку в том же продукте: проверка полноты, данные из реестров и БКИ, скоринг,
решение, оффер, подписание и выдача.

> **Статус: этапы 0–7 и 9 готовы.** Весь кредитный путь работает в интерфейсе с обеих сторон:
> гость видит лендинг с калькулятором и продуктами; клиент подбирает продукт, подаёт заявку и
> видит её путь до выдачи; банк ведёт очередь, принимает решения, меняет пороги правил и смотрит
> воронку. Уведомления приходят в кабинет, помощник объясняет продукты и решения. Интерфейс — по
> стандарту банка ([docs/design-tokens.md](docs/design-tokens.md)). Дальше — комплект материалов
> для сдачи, см. [PLAN.md](PLAN.md).

## Документы

| Документ | О чём |
| --- | --- |
| [docs/research.md](docs/research.md) | Персоны, JTBD, карта историй, приоритеты MoSCoW/RICE |
| [docs/process-model.md](docs/process-model.md) | Границы процесса, этапы, данные, скоринг и кредитная логика |
| [docs/state-machine.md](docs/state-machine.md) | Статусы заявки, переходы, инварианты |
| [docs/architecture.md](docs/architecture.md) | Сервисы, взаимодействие, безопасность |
| [docs/design-tokens.md](docs/design-tokens.md) | Цвета, шрифты, отступы и скругления интерфейса |
| [docs/integrations.md](docs/integrations.md) | Симуляторы ЕГРЮЛ/ЕГРИП, ФНС, БКИ, данных банка, подписания и АБС; тестовые компании |
| [docs/demo.md](docs/demo.md) | Сценарий демонстрации на 7 минут |
| [docs/data-model.md](docs/data-model.md) | Таблицы, связи, ключи и границы неизменности данных |
| [docs/api.md](docs/api.md) | API, роли, идемпотентность и ошибки |
| [docs/test-scenarios.md](docs/test-scenarios.md) | Ручная проверка и команды автотестов |
| [docs/adr/](docs/adr/) | Восемь архитектурных решений с альтернативами и последствиями |
| [contracts/README.md](contracts/README.md) | OpenAPI, проверка расхождений и Postman/Newman |
| [THIRD_PARTY_NOTICES](THIRD_PARTY_NOTICES) | Версии, лицензии библиотек, образов, шрифта и модели |
| [docs/reliability.md](docs/reliability.md) | Измеренная нагрузка, падение, сбои источников и гонки |
| [docs/security.md](docs/security.md) | Модель угроз, матрица прав, сканирование и план Boot 4 |
| [docs/operations.md](docs/operations.md) | Метрики, готовый дашборд и диагностика сбоев |
| [docs/deployment.md](docs/deployment.md) | HTTPS, секреты, бюджет памяти, backup и проверенное восстановление |
| [docs/demo-history.md](docs/demo-history.md) | 120 настоящих синтетических заявок и безопасный сброс демо-стенда |
| [docs/quality.md](docs/quality.md) | Измеренное покрытие, порог domain и отчёты CI |

## Запуск

Нужны Docker с Compose v2 и ~4 ГБ свободной памяти.

```bash
docker compose up --build -d
```

Первая сборка занимает 5–10 минут (скачиваются зависимости Gradle и npm). Затем:

| Адрес | Что |
| --- | --- |
| http://localhost:8080 | Лендинг и кабинет |
| http://localhost:8080/swagger/ | Swagger UI всех сервисов |
| http://localhost:8080/sim/ | Пульт симуляторов: тестовые компании, отключение источников |

Остановить: `docker compose down` (данные сохраняются в томах), начисто: `docker compose down -v`.

### ИИ-помощник (необязательно)

Помощник в кабинете клиента объясняет продукты, статус заявки и решение банка. Он работает
всегда: без модели отвечает по правилам из справки и данных заявки. С моделью с открытыми
весами (Qwen2.5 3B) через Ollama ответы становятся свободнее:

```bash
docker compose --profile ai up -d
```

При первом запуске скачиваются образ Ollama (около 2,5 ГБ, 7 ГБ на диске) и веса модели (около
1,9 ГБ) — нужен интернет, лучше сделать заранее; дальше всё работает локально. На процессоре ноутбука
ответ занимает 10–25 секунд. Модель меняется переменной `ASSISTANT_MODEL`.

**Лицензия модели.** Qwen2.5 3B распространяется по Qwen Research License: использование для
исследований и некоммерческих целей, коммерческое — по отдельному соглашению с правообладателем.
Для прототипа на хакатоне это допустимо; для промышленного запуска в банке — заменить модель на
одну из Apache 2.0: `ASSISTANT_MODEL=qwen2.5:1.5b` (ответы проще, неточности ловят
проверки ниже) или `qwen2.5:7b` (нужно больше памяти). Сравнение на одних и тех же вопросах:
3B отвечает заметно точнее 1.5B при сопоставимом времени; Qwen3 4B на Ollama 0.6.8 рассуждала вслух
вместо ответа.

Модель объясняет понятия; всё, где ошибка стоит дорого, идёт мимо неё. Вопросы о своей заявке
(почему отказ, какие условия, что загрузить) — по фактам заявки правилами; правила с порогами и
шагами (документы, платёж, ставка, подписание, сроки) — дословно из справки. Ответ модели не
показывается, а заменяется ответом по правилам, если в нём есть цифра не из данных, утверждение
против справки («овердрафт без процентов», «одобрение гарантировано»), другая письменность или он
слишком длинный.

### Тестовые учётные записи

Все данные синтетические. Пароль у всех — `Demo2026`. На странице входа есть кнопки демо-доступа.

| Email | Роль |
| --- | --- |
| manager@demo.test | Кредитный менеджер |
| underwriter@demo.test | Андеррайтер |
| risk@demo.test | Риск-администратор |
| client@demo.test | ИП Котова — автоодобрение |
| mebel@demo.test | ООО «Северная мебель» — контрпредложение |
| logistic@demo.test | ООО «Вектор Логистик» — ручное рассмотрение |
| romashka@demo.test | ООО «Ромашка-Трейд» — отказ: ликвидация |
| gromov@demo.test | ИП Громов — отказ: просрочка 90+ |
| belova@demo.test | ИП Белова — отказ: высокий риск |
| technopark@demo.test | ООО «Технопарк Плюс» — запрос документов |
| agro@demo.test | ООО «Дальний Восток Агро» — БКИ недоступно |
| novikov@demo.test | ИП Новиков — отказ: бизнесу меньше 3 месяцев |
| stroygrad@demo.test | ООО «СтройГрад» — отрасль высокого риска |

Параметры заявки для каждого сценария и ожидаемый результат — в
[docs/integrations.md](docs/integrations.md). Зарегистрировать свою компанию можно с любым
корректным ИНН, начинающимся с `00`: региона с таким кодом нет, поэтому синтетические данные
не совпадут с реальным бизнесом; симулятор реестра создаст для него типичную компанию.

### Проверка сценариев

Все десять сценариев можно прогнать одной командой — скрипт подаёт заявки от тестовых
компаний и печатает ожидаемый и полученный результат (нужен только Python 3):

```bash
python3 scripts/run-scenarios.py
```

Через Swagger UI (http://localhost:8080/swagger/) путь проходится вручную: `POST /api/v1/auth/sign-in`
→ «Authorize» с токеном → `POST /api/v1/applications` → `POST /api/v1/applications/{id}/submit` →
`GET /api/v1/applications/{id}` (статус меняется сам за несколько секунд) → `offer/accept` →
`sign` с кодом из `process.signing.code`. Решения андеррайтера — раздел `Underwriting` в сервисе credit.

### Переменные окружения

Все имеют рабочие значения по умолчанию; пример — [.env.example](.env.example).

| Переменная | По умолчанию | Назначение |
| --- | --- | --- |
| `GATEWAY_PORT` | 8080 | Порт кабинета на машине |
| `POSTGRES_USER`, `POSTGRES_PASSWORD` | cabinet / cabinet | Учётная запись PostgreSQL |
| `JWT_SECRET` | dev-значение | Ключ подписи токенов, не короче 32 байт; с профилем `prod` dev-значение не запустится |
| `DEMO_ACCOUNTS` | true | `false` — демо-учётные записи создаются заблокированными |
| `SIM_API_KEY` | dev-значение | Ключ, с которым сервисы обращаются к симуляторам внешних систем |
| `SIM_CONTROL_ENABLED` | true | `false` — пульт симуляторов не может отключать источники |
| `INFO_REQUEST_TTL` | PT30M | Срок ответа клиента на запрос документов (в продакшене — P14D) |
| `OFFER_TTL` | PT30M | Срок действия предложения банка (в продакшене — P5D) |
| `ASSISTANT_MODEL` | qwen2.5:3b | Модель помощника в Ollama (профиль `ai`); лицензия — см. «ИИ-помощник» |
| `SOURCE_TIMEOUT` | PT3M | Сколько ждать молчащий источник, прежде чем отдать заявку андеррайтеру (в продакшене — PT1H) |

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

### Как запустить автотесты

Бэкенд и проверки фронтенда — команды выше. На запущенном отдельном демо-стенде:

```bash
cd e2e && npm ci && npx playwright install chromium && npm test
cd ../contracts/postman && npm ci && npm test && npm run test:recovery
```

Playwright проверяет desktop/mobile, Newman — полный путь API и решения банка.
Тесты создают заявки и версии правил: не запускайте их на стенде с рабочими данными.
Селекторы и UI-подписи описаны в [e2e/README.md](e2e/README.md), все ручные шаги,
предусловия, отчёты и проверка OpenAPI — в [сценариях](docs/test-scenarios.md).
Нагрузка k6, несколько экземпляров, принудительная остановка и конкурентные действия —
команды [load/README.md](load/README.md), включая API/SQL-проверки и условия изоляции.
`./gradlew build` проверяет порог JaCoCo 75% для каждого пакета `domain`;
`cd frontend && npm run test:coverage` строит покрытие Vitest. Отчёты и измеренные
ограничения — [quality.md](docs/quality.md); оба отчёта сохраняются в CI.

### Фиксация зависимостей и лицензии

Gradle проверяет разрешённые версии по `gradle.lockfile` каждого модуля;
`buildscript-gradle.lockfile` и `settings-gradle.lockfile` фиксируют зависимости плагинов.
Включённая сборка `build-logic` имеет свои lock-файлы. Обычная сборка их не обновляет.
После осознанного изменения каталога/BOM обновите обе сборки и проверьте diff:

```bash
python scripts/update-dependency-locks.py
DB_PORT=55432 ./gradlew build
```

Для npm используется `npm ci` с lock-файлом каждого проекта. Обновление списка лицензий
(перед запуском нужен JDK 21 и установленные npm-зависимости):

```bash
npm ci --prefix frontend && npm ci --prefix e2e
npm ci --prefix contracts/postman && npm ci --prefix scripts/license-tools
node scripts/generate-notices.mjs
node scripts/generate-notices.mjs --skip-gradle --check
```

Первая команда генерации вызывает Gradle License Report 3.1.4 для основной и включённой
сборки, затем license-checker 25.0.1 для npm. `--check` проверяет совпадение с сохранёнными
`THIRD_PARTY_NOTICES` и `docs/licenses/inventory.json`; `--skip-gradle` использует уже
выгруженные отчёты `build/reports/dependency-license/`. Ошибка или неизвестная лицензия
останавливает генерацию. Два маркера Gradle дополнены точными ссылками на исходники
в `scripts/license-tools/gradle-markers.json`. Состав образов/ресурсов указан отдельно
в `scripts/license-tools/components.json`; его обновляют при изменении Compose/Dockerfile.

Матрица прав, негативные проверки, npm audit и Trivy — [security/README.md](security/README.md).
Модель угроз, результаты и план Boot 4 — [docs/security.md](docs/security.md).

### Мониторинг

```bash
docker compose --profile monitoring up --build -d
python monitoring/verify.py --require-activity
```

Перед проверкой проведите заявку до решения; для пустого стенда уберите
`--require-activity`. Prometheus — http://localhost:9090, Grafana — http://localhost:3000,
дашборд «Кредитный конвейер». Вход и смена параметров — [operations.md](docs/operations.md).
Оба порта доступны только с localhost. Без модели и мониторинга базовый запуск прежний.

### Развёртывание и восстановление

Комплект `deploy/` включает prod-профиль, Caddy HTTPS, генерацию секретов, отдельные
учётки банка, ограничения для VPS 4 GiB, согласованные копии БД/документов и restore
в новый пустой проект. [Инструкция и локальная приёмка](docs/deployment.md).
Сервер/домен/публичный сертификат проверяет владелец; локальная проверка использует
собственный CA клиента и не меняет доверие ОС.
Синтетическая история и сброс отдельного локального демо-проекта —
[demo-history.md](docs/demo-history.md). Скрипт создаёт данные через API и не подходит
для производственного стенда.

## Стек

| Часть | Технологии |
| --- | --- |
| Бэкенд | Kotlin 2.1, Spring Boot 3.5, Spring Security (JWT), Spring Data JPA, Flyway, PostgreSQL 16 |
| Фронтенд | React 18, TypeScript, MUI 5, Vite |
| Инфраструктура | Docker Compose, nginx (gateway), Swagger UI, GitHub Actions, Trivy |

Версии зафиксированы: `gradle/libs.versions.toml` и BOM Spring Boot для бэкенда,
lock-файлы Gradle для разрешённых транзитивных версий, `frontend/package-lock.json`
для фронтенда, теги образов в `docker-compose.yml`.

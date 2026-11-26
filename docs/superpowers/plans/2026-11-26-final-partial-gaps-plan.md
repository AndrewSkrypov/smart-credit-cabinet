# Закрытие инженерных недочётов финала (Подход A) — План реализации

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Закрыть инженерные «жёлтые» пункты финала (`2.4` rollback бизнес-миграций `V13–V16`, `6.1–6.3` негативные и граничные автотесты + каталог тест-кейсов, `2.1` завершение гексагональной архитектуры + ArchUnit-тесты границ во всех трёх сервисах) в изолированном worktree `.worktrees/final-partial-gaps`.

**Architecture:** Все изменения выполняются в ветке `feat/final-partial-gaps` (от `main` `3e039dd`) без изменения публичных HTTP-маршрутов, OpenAPI-контрактов, фронтенда и применённых `V*`-миграций. Архитектурные границы закрепляются через `com.tngtech.archunit:archunit-junit5:1.4.0` в `testImplementation` с проверкой чистоты `domain`, изоляции `controller` от `infrastructure` и независимости `application` от веб-аннотаций.

**Tech Stack:** Kotlin 2.1.21, Spring Boot 3.5.16, JUnit 5, ArchUnit 1.4.0, PostgreSQL 16 (локальный инстанс `127.0.0.1:55432`), Python 3, Gradle Kotlin DSL + lockfiles, ktlint.

**Spec:** `docs/superpowers/specs/2026-11-26-final-partial-gaps-design.md`

## Global Constraints

- Работать строго внутри `.worktrees/final-partial-gaps` (ветка `feat/final-partial-gaps`); не трогать основную рабочую директорию, `.worktrees/final-integration`, `docs/research.md`, `docs/jury-feedback-report.md`, `docs/plans/final-roadmap-2026-11-26.md`, `frontend/`, `contracts/openapi/`.
- Не редактировать существующие Flyway-миграции (`V1..V18` credit, `V1..V8` identity, `V1..V5` engagement) и не добавлять новые `V*`-миграции.
- Не удалять скрипты оценки ИИ (`scripts/assistant-*.py` / `*.json`).
- Все сообщения коммитов — на русском языке.
- Перед каждым коммитом прогонять профильные тесты и `ktlintCheck` (для JVM) или `python3 scripts/check-docs.py` (для документации).

## Review Focus

1. Откат `U13__product_lifecycle.sql` не должен падать из-за триггера `product_versions_no_update` и должен корректно восстанавливать `NOT DEFERRABLE` у `products_code_current_version_fkey`.
2. Откат `U14__agent_channel_and_preapproved.sql` должен удалять внешний ключ `applications.pre_approved_offer_id` до `drop table pre_approved_offers`, иначе PostgreSQL отклонит удаление таблицы.
3. При переносе `ManualReviewEvents` и `AdviceController` в `credit/advice/{application,controller,infrastructure}` имена Spring-бинов, канал outbox `"assistant-manual-review"` и путь `/internal/v1/underwriting/applications/{id}/advice` должны остаться идентичными.
4. При разносе `engagement/assistant` по слоям `domain/application/controller/infrastructure` аннотации `@Value`, `@Component`, `@RestController` и `@EventListener` должны остаться в `application`/`controller`/`infrastructure`, а `domain` не должен тянуть `org.springframework.web` или `RestClient`.
5. `HexagonalArchitectureTest` должен реально проверять production-классы (`ImportOption.DoNotIncludeTests`), а не пустой набор классов (`allowEmptyShould(false)` по умолчанию в ArchUnit).

---

### Task 1: Rollback-скрипты `U13–U16` для бизнес-миграций `credit` и репетиция отката

**Files:**
- Create: `db/rollback/credit/U16__outbox_trace_parent.sql`
- Create: `db/rollback/credit/U15__loan_servicing_and_tranches.sql`
- Create: `db/rollback/credit/U14__agent_channel_and_preapproved.sql`
- Create: `db/rollback/credit/U13__product_lifecycle.sql`
- Create: `deploy/check-business-rollback.py`
- Modify: `db/rollback/README.md`

**Interfaces:**
- Produces: четыре SQL-скрипта отката `U13–U16` и автономный скрипт проверки `deploy/check-business-rollback.py`, не изменяющий `deploy/check-schema-rollback.py`.

- [ ] **Step 1: Создать `U16__outbox_trace_parent.sql`, `U15__loan_servicing_and_tranches.sql`, `U14__agent_channel_and_preapproved.sql`, `U13__product_lifecycle.sql`**
  - `U16`: `alter table delivery_outbox drop column if exists trace_parent;`
  - `U15`: `drop table if exists loan_payments; drop table if exists loan_tranches; drop table if exists loan_accounts;`
  - `U14`: `drop index if exists idx_applications_pre_approved; drop index if exists idx_applications_agent; alter table applications drop column if exists agent_commission_status, drop column if exists agent_commission_amount, drop column if exists agent_commission_rate_bps, drop column if exists pre_approved_offer_id, drop column if exists agent_name, drop column if exists agent_id; drop table if exists pre_approved_offers;`
  - `U13`: `alter table products alter constraint products_code_current_version_fkey not deferrable; alter table product_versions drop constraint if exists product_versions_effective_window, drop column if exists agent_commission_rate_bps, drop column if exists effective_to, drop column if exists effective_from, drop column if exists lifecycle_status;`

- [ ] **Step 2: Создать `deploy/check-business-rollback.py`**
  - Создаёт временную БД `cabinet_biz_rollback_<uuid>` на основе дампа схемы локальной БД `credit` (или пустой БД с прогоном `V1..V18`), снимает снимок таблиц и колонок (`information_schema.columns`), прогоняет `U18 → U17 → U16 → U15 → U14 → U13`, проверяет отсутствие удалённых таблиц/колонок (`loan_accounts`, `pre_approved_offers`, `lifecycle_status`, `trace_parent`), затем повторно накатывает `V13 → V14 → V15 → V16 → V17 → V18`, проверяет совпадение снимка таблиц и колонок с исходным и удаляет временную БД.

- [ ] **Step 3: Обновить `db/rollback/README.md`**
  - Добавить строку про `credit V13–V16` (бизнес-миграции финального этапа) и команду запуска `deploy/check-business-rollback.py`, сохранив предупреждение о том, что в production основной способ отката — восстановление из снимка.

- [ ] **Step 4: Запустить проверку отката и документации**
  - Run: `PGPASSWORD=cabinet python3 deploy/check-business-rollback.py --pg-bin "$(pg_config --bindir)" --port 55432 --user cabinet --source-db credit --output /tmp/business-rollback.json && python3 scripts/check-docs.py`
  - Expected: `"passed": true` в JSON-отчёте и `0` ошибок в `check-docs.py`.

- [ ] **Step 5: Коммит**
  - `git add db/rollback/credit/U13__product_lifecycle.sql db/rollback/credit/U14__agent_channel_and_preapproved.sql db/rollback/credit/U15__loan_servicing_and_tranches.sql db/rollback/credit/U16__outbox_trace_parent.sql deploy/check-business-rollback.py db/rollback/README.md`
  - `git commit -m "db: добавить rollback-скрипты U13–U16 для бизнес-миграций credit и скрипт репетиции"`

---

### Task 2: Негативные и граничные автотесты + каталог тест-кейсов с трассировкой

**Files:**
- Create: `services/credit/src/test/kotlin/ru/creditcabinet/agent/AgentNegativeAndBoundaryIntegrationTest.kt`
- Create: `services/credit/src/test/kotlin/ru/creditcabinet/servicing/LoanServicingBoundaryIntegrationTest.kt`
- Modify: `docs/test-scenarios.md`

**Interfaces:**
- Consumes: существующие контроллеры `AgentController`, `ApplicationController`, `LoanServicingController`, `AgentAccessPort`, `LoanServicingService`.
- Produces: 2 новых интеграционных тестовых набора (негативные, граничные, IDOR, fail-closed `503`) и структурированный каталог `TC-01..TC-24` в `docs/test-scenarios.md`.

- [ ] **Step 1: Написать `AgentNegativeAndBoundaryIntegrationTest.kt`**
  - Покрыть через `MockMvc` + реальную PostgreSQL (`55432`) и мок `AgentAccessPort`:
    1) `amount <= 0`, `amount < minAmount`, `amount > maxAmount`, `termMonths < minTerm`, `termMonths > maxTerm`, несуществующий `productCode`, отрицательная выручка → `400`.
    2) Подтверждение заявки клиентом без согласия БКИ → `400`/`409`.
    3) IDOR по документам и чату агента: `GET/POST /api/v1/agent/applications/{id}/documents` и `GET/POST /api/v1/agent/applications/{id}/messages` от второго агента (`agent2`) и после отзыва мандата → `404`.
    4) Сбой `identity` (`AgentAccessPort` кидает `ServiceUnavailableException`) → `503` на `/api/v1/agent/applications`.
    5) Попытка агента подтвердить, подписать или запросить андеррайтинг → `403`.

- [ ] **Step 2: Написать `LoanServicingBoundaryIntegrationTest.kt`**
  - Покрыть через `MockMvc` + реальную PostgreSQL (`55432`):
    1) Выборка транша: `amount <= 0` → `400`; сумма выше доступного лимита → `409`/`400`; выборка на неревольверном кредите → `409`; выборка на погашенном кредите → `409`.
    2) Платежи: досрочное погашение с `amount <= 0` → `400`; повторная оплата уже оплаченного платежа → `409`.
    3) IDOR и RBAC: чтение/выборка/платёж по чужому кредиту → `404`; попытка агента вызвать `POST /api/v1/applications/{id}/loan/tranches` или `payments` → `403`.

- [ ] **Step 3: Запустить новые тесты и `ktlintCheck`**
  - Run: `DB_PORT=55432 ./gradlew :services:credit:test --tests "ru.creditcabinet.agent.AgentNegativeAndBoundaryIntegrationTest" --tests "ru.creditcabinet.servicing.LoanServicingBoundaryIntegrationTest" :services:credit:ktlintCheck`
  - Expected: все тесты PASS, `ktlintCheck` PASS.

- [ ] **Step 4: Обновить `docs/test-scenarios.md`**
  - Добавить раздел «Каталог тест-кейсов и трассировка требований» с таблицей `TC-01`..`TC-24` (ID, требование/замечание жюри, категория: позитивный/негативный/граничный/безопасность/восстановление, автотест/команда).
  - Run: `python3 scripts/check-docs.py`
  - Expected: 0 ошибок.

- [ ] **Step 5: Коммит**
  - `git add services/credit/src/test/kotlin/ru/creditcabinet/agent/AgentNegativeAndBoundaryIntegrationTest.kt services/credit/src/test/kotlin/ru/creditcabinet/servicing/LoanServicingBoundaryIntegrationTest.kt docs/test-scenarios.md`
  - `git commit -m "test: добавить негативные и граничные интеграционные тесты и каталог тест-кейсов"`

---

### Task 3: Подключение ArchUnit и гексагональные границы в `identity`

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `services/identity/build.gradle.kts`, `services/credit/build.gradle.kts`, `services/engagement/build.gradle.kts` (+ `gradle.lockfile` в трёх сервисах)
- Create: `services/identity/src/main/kotlin/ru/creditcabinet/user/application/UserService.kt`
- Create: `services/identity/src/main/kotlin/ru/creditcabinet/company/application/CompanyService.kt`
- Modify: `services/identity/src/main/kotlin/ru/creditcabinet/user/controller/UserController.kt`
- Modify: `services/identity/src/main/kotlin/ru/creditcabinet/company/controller/CompanyController.kt`
- Create: `services/identity/src/test/kotlin/ru/creditcabinet/architecture/HexagonalArchitectureTest.kt`

**Interfaces:**
- Produces: зависимость `libs.archunit.junit5` (1.4.0) в трёх сервисах, `UserService`, `CompanyService` и зелёный `HexagonalArchitectureTest` в `identity`.

- [ ] **Step 1: Подключить `archunit-junit5` 1.4.0 и обновить lockfile**
  - Добавить `archunit = "1.4.0"` и `archunit-junit5 = { module = "com.tngtech.archunit:archunit-junit5", version.ref = "archunit" }` в `gradle/libs.versions.toml`.
  - Добавить `testImplementation(libs.archunit.junit5)` в `services/{identity,credit,engagement}/build.gradle.kts`.
  - Run: `./gradlew :services:identity:dependencies :services:credit:dependencies :services:engagement:dependencies --write-locks`

- [ ] **Step 2: Выделить `UserService` и `CompanyService` в `identity`**
  - `UserService(private val users: UserDataPort)`: методы `list(ids, companyId, role)`, `get(id)`, `activeCredentialsVersion(id)`.
  - `CompanyService(private val companies: CompanyDataPort, private val users: UserDataPort)`: методы `mine(companyId)`, `get(id)`.
  - `InternalUserController`, `CompanyController`, `InternalCompanyController` делегируют в `UserService`/`CompanyService` без прямого обращения к `UserDataPort`/`CompanyDataPort`.

- [ ] **Step 3: Добавить `HexagonalArchitectureTest.kt` в `services/identity`**
  - Проверить для `ru.creditcabinet` (`ImportOption.DoNotIncludeTests`):
    1) `..domain..` не зависит от `..controller..`, `..infrastructure..`, `org.springframework.web..`, `jakarta.persistence..`, `org.springframework.jdbc..`.
    2) `..controller..` не зависит от `..infrastructure..`, `jakarta.persistence..`, `org.springframework.jdbc..`.
    3) `..application..` не зависит от `..controller..`, `org.springframework.web.bind.annotation..`.

- [ ] **Step 4: Прогнать тесты и `ktlintCheck` сервиса `identity`**
  - Run: `DB_PORT=55432 ./gradlew :services:identity:test :services:identity:ktlintCheck` (исключая только Docker-зависимые тесты Valkey, если они есть)
  - Expected: все тесты `identity` (включая `HexagonalArchitectureTest`) PASS, `ktlintCheck` PASS.

- [ ] **Step 5: Коммит**
  - `git add gradle/libs.versions.toml services/*/build.gradle.kts services/*/gradle.lockfile services/identity/src`
  - `git commit -m "refactor(identity): выделить application-слой для user/company и добавить ArchUnit-тесты"`

---

### Task 4: Доразнос `credit/advice` и `credit/integration` + ArchUnit-тесты в `credit`

**Files:**
- Create: `services/credit/src/main/kotlin/ru/creditcabinet/advice/application/AdviceService.kt`
- Create: `services/credit/src/main/kotlin/ru/creditcabinet/advice/application/ManualReviewEvents.kt`
- Create: `services/credit/src/main/kotlin/ru/creditcabinet/advice/controller/AdviceController.kt`
- Create: `services/credit/src/main/kotlin/ru/creditcabinet/advice/infrastructure/OutboxManualReviewEvents.kt`
- Delete: `services/credit/src/main/kotlin/ru/creditcabinet/advice/AdviceController.kt`, `services/credit/src/main/kotlin/ru/creditcabinet/advice/ManualReviewEvents.kt`
- Move: `services/credit/src/main/kotlin/ru/creditcabinet/integration/ExternalPorts.kt` → `integration/domain/ExternalPorts.kt`
- Move: `services/credit/src/main/kotlin/ru/creditcabinet/integration/SimulatorClients.kt` → `integration/infrastructure/SimulatorClients.kt`
- Create: `services/credit/src/test/kotlin/ru/creditcabinet/architecture/HexagonalArchitectureTest.kt`
- Modify: импорты в потребителях `advice` и `integration` в `services/credit/src/{main,test}`.

**Interfaces:**
- Consumes: `libs.archunit.junit5` из Task 3.
- Produces: гексагонально чистые модули `credit/advice` и `credit/integration` + `HexagonalArchitectureTest` в `services/credit`.

- [ ] **Step 1: Разнести `credit/advice` на `application`, `controller`, `infrastructure`**
  - Сохранить точные URL `/api/v1/underwriting/applications/{id}/advice` и `/internal/v1/underwriting/applications/{id}/advice`, канал outbox `"assistant-manual-review"` и `SCHEMA_VERSION = 1`.
- [ ] **Step 2: Разнести `credit/integration` на `domain` (`ExternalPorts.kt`) и `infrastructure` (`SimulatorClients.kt`)**
  - Обновить импорты во всех файлах `services/credit/src/{main,test}`.
- [ ] **Step 3: Добавить `HexagonalArchitectureTest.kt` в `services/credit`**
  - Проверить три инварианта (`domain` изолирован от `controller`/`infrastructure`/веб/JPA/JDBC; `controller` не ходит в `infrastructure`/JPA; `application` не зависит от `controller`/веб-аннотаций).
- [ ] **Step 4: Прогнать тесты и `ktlintCheck` сервиса `credit`**
  - Run: `DB_PORT=55432 ./gradlew :services:credit:test -x :services:credit:test --tests "*Clam*" ...` (прогнать все тесты `credit`, исключая Docker-зависимые `ClamDocumentScannerTest`, `DocumentScanIntegrationTest`, `S3FileStoreIntegrationTest`) + `:services:credit:ktlintCheck`.
  - Expected: все тесты `credit` (включая `ManualReviewAdviceContractIntegrationTest` и `HexagonalArchitectureTest`) PASS, `ktlintCheck` PASS.
- [ ] **Step 5: Коммит**
  - `git add services/credit/src`
  - `git commit -m "refactor(credit): разнести advice и integration по гексагональным слоям и добавить ArchUnit"`

---

### Task 5: Гексагональный разнос `engagement/assistant` + ArchUnit-тесты в `engagement` и финальная сверка

**Files:**
- Refactor: `services/engagement/src/main/kotlin/ru/creditcabinet/assistant/*` → `assistant/{domain,application,controller,infrastructure}/*`
- Create: `services/engagement/src/test/kotlin/ru/creditcabinet/architecture/HexagonalArchitectureTest.kt`
- Modify: импорты в `services/engagement/src/test/kotlin/ru/creditcabinet/assistant/*`

**Interfaces:**
- Consumes: `libs.archunit.junit5` из Task 3.
- Produces: гексагонально разнесённый `engagement/assistant` + `HexagonalArchitectureTest` в `services/engagement`.

- [ ] **Step 1: Прочитать оставшиеся файлы `engagement/assistant/` и разнести их по слоям**
  - `assistant/domain/`: чистые модели, `Knowledge.kt`, `ProductPicker.kt`, доменные интерфейсы (`LanguageModel`, `AssistantReferenceCache`, порты чтения фактов).
  - `assistant/application/`: `AssistantService.kt`, `ManualReviewSummaries.kt` (бизнес-логика прогрева), `AssistantNextSteps.kt` (оркестрация подсказок и напоминаний).
  - `assistant/controller/`: `AssistantController.kt` и внутренний контроллер приёма событий ручной проверки (если выделен в `ManualReviewSummaries.kt`).
  - `assistant/infrastructure/`: `OllamaModel.kt`, `AssistantMetrics.kt`, HTTP/Valkey-клиенты (`ApplicationFacts`, кэш справок).
- [ ] **Step 2: Добавить `HexagonalArchitectureTest.kt` в `services/engagement`**
  - Проверить те же три гексагональных инварианта (`domain`, `controller`, `application`) на `ru.creditcabinet`.
- [ ] **Step 3: Прогнать тесты и `ktlintCheck` сервиса `engagement`**
  - Run: `DB_PORT=55432 ./gradlew :services:engagement:test :services:engagement:ktlintCheck` (исключая Docker-зависимый Valkey-тест при отсутствии Docker).
  - Expected: все тесты `engagement` (включая `ManualReviewSummariesTest`, `AssistantApiTest`, `HexagonalArchitectureTest`) PASS, `ktlintCheck` PASS.
- [ ] **Step 4: Коммит и финальная сквозная верификация всех трёх сервисов + документации**
  - `git add services/engagement/src`
  - `git commit -m "refactor(engagement): разнести assistant по гексагональным слоям и добавить ArchUnit"`
  - Запустить финальную проверку `git status`, `python3 scripts/check-docs.py`, `deploy/check-business-rollback.py` и все три `HexagonalArchitectureTest`.

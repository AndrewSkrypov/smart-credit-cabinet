# Agent Channel & Final Product Core Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Реализовать сквозной агентский канал продаж (роль `AGENT`, мандаты клиентов, подтверждение клиента, строгий RBAC, вознаграждение), предодобренные предложения, жизненный цикл продуктов (`DRAFT/ACTIVE/ARCHIVED`), поствыдачный контур кредита с потраншевыми выдачами и мобильную полировку UI поверх существующего 4-сервисного стека.

**Architecture:** Все изменения укладываются в текущие 4 Spring Boot сервиса (`identity`, `credit`, `engagement`, `external-sim`), библиотеку `libs/platform-common` и React 18 SPA без выделения 5-го физического сервиса. Агентский трафик изолирован под единым префиксом `/api/v1/agent/**`, проверка мандата `agent_clients` в `identity` работает fail-closed (`503` при сбое, `404` для чужих сущностей), а существующие append-only таблицы (`product_versions`) и применённые миграции (`V1..V12` / `V1..V5`) не нарушаются.

**Tech Stack:** Kotlin 2.1, Spring Boot 3.5, Spring Security (JWT), Spring Data JPA / JDBC, Flyway, PostgreSQL 16, React 18, TypeScript, MUI 5, Vitest, JUnit 5, JaCoCo.

**Spec:** [docs/superpowers/specs/2026-11-25-agent-channel-and-final-design.md](../specs/2026-11-25-agent-channel-and-final-design.md)

## Global Constraints

- Не редактировать существующие миграции `V1..V12` (`credit`), `V1..V5` (`identity`), `V1..V3` (`engagement`); новые изменения схемы — только новыми миграциями (`V6` в `identity`, `V13` и `V14` в `credit`).
- Не ослаблять триггер неизменности `product_versions` (из `V5__product_versions.sql`) и не ломать 10 существующих сценариев.
- Порог покрытия JaCoCo >= 75% по каждому пакету `domain` обязателен (`./gradlew build`).
- Вся работа проходит строго внутри веб-кабинета на синтетических данных без внешних SMS/почты/звонков и без платных LLM API.
- `ApplicationStateMachine` должен построчно совпадать с `docs/state-machine.md` (проверяется `ApplicationStateMachineTest`).
- Fail-closed безопасность: чужие компании/заявки/документы для `AGENT` возвращают `404`; действия вне полномочий (`SUBMIT`, `CONFIRM_AND_SUBMIT`, `ACCEPT_OFFER`, `DECLINE_OFFER`, `SIGN`, `tranches`, `payments`) — `403`.

## Review Focus

1. **Отозванный мандат агента mid-flight:** агент создал заявку, затем его связь в `agent_clients` переведена в `REVOKED` — любой последующий запрос агента к этой заявке, её документам или чату обязан вернуть `404` (тест в Task 5).
2. **Обход подтверждения клиента:** клиент или агент пытается вызвать прямой `POST /api/v1/applications/{id}/submit` для агентской заявки (`agentId != null`) — обязан получить `409 Conflict` / `403 Forbidden`, не обходя `PENDING_CLIENT_CONFIRMATION` (тест в Task 2 и Task 5).
3. **Выход за лимиты предодобрения в черновике:** агент или клиент создал заявку из `pre_approved_offer`, затем в `DRAFT` увеличил сумму выше `offer.maxAmount` — `preApprovedOfferId` обязан отвязаться, оффер вернуться в `ACTIVE`, а заявка пойти по обычному скорингу (тест в Task 4).
4. **Превышение лимита при доборе транша и конкурентный добор:** попытка запросить транш больше `approved_limit - disbursed_amount` или по неревольверному кредиту (`revolving = false`) обязана вернуть `400`/`409` без изменения баланса; вызов от `AGENT` — `403` (тест в Task 6).
5. **Невидимость `DRAFT` и `ARCHIVED` продуктов в публичном каталоге при сохранности снимков:** перевод продукта в `ARCHIVED` скрывает его из `GET /api/v1/products`, но ранее поданная заявка на этот продукт продолжает читаться и доходить до выдачи по своему снимку версии (тест в Task 3).

---

### Task 1: Роль `AGENT`, мандаты `agent_clients`, API и демо-учётки (`platform-common` + `identity`)

**Files:**
- Modify: `libs/platform-common/src/main/kotlin/ru/creditcabinet/common/security/TokenClaims.kt`
- Modify: `services/identity/src/main/kotlin/ru/creditcabinet/user/domain/User.kt`
- Create: `services/identity/src/main/resources/db/migration/V6__agent_role_and_clients.sql`
- Create: `services/identity/src/main/kotlin/ru/creditcabinet/agent/domain/AgentMandate.kt`
- Create: `services/identity/src/main/kotlin/ru/creditcabinet/agent/application/AgentClientService.kt`
- Create: `services/identity/src/main/kotlin/ru/creditcabinet/agent/infrastructure/AgentClientJdbcStore.kt`
- Create: `services/identity/src/main/kotlin/ru/creditcabinet/agent/controller/AgentClientController.kt`
- Modify: `services/identity/src/main/kotlin/ru/creditcabinet/auth/controller/AuthController.kt` (если нужен список ролей/валидация при входе)
- Test: `services/identity/src/test/kotlin/ru/creditcabinet/agent/AgentClientIntegrationTest.kt`

**Interfaces:**
- Consumes: `TokenClaims`, `CurrentCaller`, `UserRole`, `companies` table.
- Produces:
  - `Roles.AGENT = "AGENT"`, `TokenClaims.isAgent: Boolean`
  - `UserRole.AGENT`
  - `GET /api/v1/agent/clients` -> `List<AgentClientSummary(companyId, inn, name, legalForm, assignedAt)>` (only `AGENT`, else `403`)
  - `GET /internal/v1/agents/{agentId}/companies` -> `List<UUID>`
  - `GET /internal/v1/agents/{agentId}/companies/{companyId}/access` -> `AgentAccessResponse(allowed: Boolean, company: CompanyResponse?)`
  - Demo accounts: `agent@demo.test` (4 companies: Котова, Северная мебель, Вектор Логистик, Технопарк Плюс) and `agent2@demo.test` (1 company: Дальний Восток Агро), password `Demo2026`.

- [ ] **Step 1: Write failing unit & integration tests for `UserRole.AGENT` and `AgentClientController`**

Создать `AgentClientIntegrationTest.kt`: проверить вход `agent@demo.test` (роль `AGENT`, `companyId == null`, `canSign == false`), `GET /api/v1/agent/clients` возвращает ровно 4 компании, `GET /api/v1/agent/clients` от `CLIENT` возвращает `403`, `/internal/v1/agents/{id}/companies/{companyId}/access` возвращает `allowed = true` для закреплённой компании и `allowed = false` для `stroygrad@demo.test` и после отзыва мандата (`status = 'REVOKED'`).

- [ ] **Step 2: Run tests to verify failure**

Run: `OUTBOX_WORKER_ENABLED=false DB_PORT=55432 ./gradlew :libs:platform-common:test :services:identity:test --tests "*AgentClient*"`
Expected: FAIL (missing `Roles.AGENT`, `UserRole.AGENT`, migration `V6`).

- [ ] **Step 3: Implement `Roles.AGENT`, `UserRole.AGENT`, migration `V6` and `agent` package in `identity`**

1. В `TokenClaims.kt` добавить `Roles.AGENT = "AGENT"` и `val isAgent: Boolean get() = role == Roles.AGENT`.
2. В `User.kt` добавить `UserRole.AGENT`; инвариант `require(role == UserRole.CLIENT || companyId == null)` сохраняет запрет `companyId` и `canSign` для `AGENT`.
3. В `V6__agent_role_and_clients.sql` создать таблицу `agent_clients` с частичным уникальным индексом `(agent_user_id, company_id) where status = 'ACTIVE'` и засеять `agent@demo.test`, `agent2@demo.test` с их привязками (используя тот же bcrypt-хэш `Demo2026`, что и `V2/V3`).
4. Реализовать `AgentMandate`, `AgentClientService`, `AgentClientJdbcStore`, `AgentClientController` (публичный + внутренний `/internal/v1/agents/**`).

- [ ] **Step 4: Run identity & platform-common build + tests**

Run: `OUTBOX_WORKER_ENABLED=false DB_PORT=55432 ./gradlew :libs:platform-common:check :services:identity:check`
Expected: PASS (включая ktlint и JaCoCo >= 75%).

- [ ] **Step 5: Commit**

```bash
git add libs/platform-common services/identity
git commit -m "feat(identity): add AGENT role, agent_clients mandates, internal access API and demo agents"
```

---

### Task 2: Конечный автомат и доменная модель агентской заявки (`credit/application/domain` + `docs/state-machine.md`)

**Files:**
- Modify: `services/credit/src/main/kotlin/ru/creditcabinet/application/domain/ApplicationStatus.kt`
- Modify: `services/credit/src/main/kotlin/ru/creditcabinet/application/domain/ApplicationStateMachine.kt`
- Modify: `services/credit/src/main/kotlin/ru/creditcabinet/application/domain/LoanApplication.kt`
- Modify: `services/credit/src/main/kotlin/ru/creditcabinet/application/service/Callers.kt`
- Modify: `docs/state-machine.md`
- Test: `services/credit/src/test/kotlin/ru/creditcabinet/application/domain/ApplicationStateMachineTest.kt`
- Test: `services/credit/src/test/kotlin/ru/creditcabinet/application/domain/AgentApplicationDomainTest.kt`

**Interfaces:**
- Consumes: `Roles.AGENT`, `TokenClaims.isAgent`.
- Produces:
  - `ActorType.AGENT`
  - `ApplicationStatus.PENDING_CLIENT_CONFIRMATION` (non-terminal, `visibleToBank = false`, in `WITHDRAWABLE`)
  - `ApplicationEvent`: `REQUEST_CLIENT_CONFIRMATION(setOf(AGENT))`, `CONFIRM_AND_SUBMIT(setOf(CLIENT))`, `RETURN_TO_AGENT(setOf(CLIENT))`, `AGENT_CANCEL(setOf(AGENT))`, updated `INFO_PROVIDED(setOf(CLIENT, AGENT))`
  - `CommissionStatus` (`NONE`, `EXPECTED`, `ACCRUED`, `CANCELLED`)
  - `LoanApplication` fields: `agentId: UUID?`, `agentName: String?`, `preApprovedOfferId: UUID?`, `agentCommissionRateBps: Int?`, `agentCommissionAmount: Long?`, `agentCommissionStatus: CommissionStatus`
  - Domain methods: `requestClientConfirmation(product, actor, now)`, `confirmAndSubmit(product, bureauConsent, actor, now)`, `returnToAgent(actor, comment, now)`, `agentCancel(actor, now)`, `visibleToClient: Boolean` (`agentId == null || status != DRAFT`).

- [ ] **Step 1: Write failing domain tests for new transitions and guards**

В `AgentApplicationDomainTest.kt` и `ApplicationStateMachineTest.kt` проверить:
- `DRAFT -> PENDING_CLIENT_CONFIRMATION` (`AGENT`) требует валидных параметров (кроме `bureauConsent`), отклоняет `CLIENT`;
- `PENDING_CLIENT_CONFIRMATION -> SUBMITTED` (`CLIENT`) требует `bureauConsent = true`, отклоняет `AGENT`;
- прямой `submit()` на заявке с `agentId != null` бросает `ConflictException` (Review Focus #2);
- `visibleToClient` возвращает `false` для `agentId != null && status == DRAFT` и `true` в `PENDING_CLIENT_CONFIRMATION`;
- `visibleToBank` возвращает `false` для `PENDING_CLIENT_CONFIRMATION`;
- `AGENT_CANCEL` работает только из `DRAFT` и `PENDING_CLIENT_CONFIRMATION`;
- `INFO_PROVIDED` разрешён для `AGENT` и `CLIENT`.

- [ ] **Step 2: Run domain tests to verify failure**

Run: `OUTBOX_WORKER_ENABLED=false DB_PORT=55432 ./gradlew :services:credit:test --tests "*ApplicationStateMachineTest" --tests "*AgentApplicationDomainTest"`
Expected: FAIL.

- [ ] **Step 3: Implement state machine, domain model, `Callers.kt` and update `docs/state-machine.md`**

Обновить `ApplicationStatus.kt`, `ApplicationStateMachine.kt`, `LoanApplication.kt`, `Callers.kt` (добавить `Roles.AGENT -> Actor(ActorType.AGENT, userId)` и проверку `visibleToClient` в `canSee`) и синхронно обновить таблицу T1–T25 и Mermaid-диаграмму в `docs/state-machine.md`.

- [ ] **Step 4: Run credit domain tests**

Run: `OUTBOX_WORKER_ENABLED=false DB_PORT=55432 ./gradlew :services:credit:test --tests "*application.domain*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add services/credit/src/main/kotlin/ru/creditcabinet/application services/credit/src/test docs/state-machine.md
git commit -m "feat(credit): extend application state machine and domain for AGENT workflow"
```

---

### Task 3: Жизненный цикл продуктов (`DRAFT/ACTIVE/ARCHIVED`, даты действия, комиссия агента)

**Files:**
- Create: `services/credit/src/main/resources/db/migration/V13__product_lifecycle.sql`
- Modify: `services/credit/src/main/kotlin/ru/creditcabinet/product/domain/Product.kt`
- Modify: `services/credit/src/main/kotlin/ru/creditcabinet/product/domain/ProductVersions.kt`
- Modify: `services/credit/src/main/kotlin/ru/creditcabinet/product/application/ProductEditor.kt`
- Modify: `services/credit/src/main/kotlin/ru/creditcabinet/product/infrastructure/ProductJdbcCatalog.kt`
- Modify: `services/credit/src/main/kotlin/ru/creditcabinet/product/controller/ProductEditorController.kt`
- Test: `services/credit/src/test/kotlin/ru/creditcabinet/product/ProductLifecycleIntegrationTest.kt`

**Interfaces:**
- Consumes: `Product`, `ProductVersions`, `ProductEditor`, `CurrentCaller.requireRole(Roles.RISK_ADMIN)`.
- Produces:
  - `enum class ProductLifecycleStatus { DRAFT, ACTIVE, ARCHIVED }`
  - `Product` fields: `lifecycleStatus: ProductLifecycleStatus = ACTIVE`, `effectiveFrom: Instant? = null`, `effectiveTo: Instant? = null`, `agentCommissionRateBps: Int = 150`; `val active: Boolean get() = lifecycleStatus == ProductLifecycleStatus.ACTIVE`
  - Endpoints: `POST /api/v1/bank/products` (create in `DRAFT`), `POST /api/v1/bank/products/{code}/publish`, `POST /api/v1/bank/products/{code}/archive`, `POST /api/v1/bank/products/{code}/restore` (все с `expectedVersion` и `reason`, append-only в `product_versions`).

- [ ] **Step 1: Write failing integration test `ProductLifecycleIntegrationTest`**

Проверить:
- создание нового продукта `POST /api/v1/bank/products` создаёт ревизию `v1` в `DRAFT`, которой НЕТ в `GET /api/v1/products`;
- `publish` создаёт `v2` в `ACTIVE`, продукт появляется в `GET /api/v1/products`;
- `archive` создаёт `v3` в `ARCHIVED`, продукт исчезает из `GET /api/v1/products`, но ранее поданная заявка по `v2` продолжает читаться по своему снимку (Review Focus #5);
- `restore` возвращает продукт в `ACTIVE` (`v4`);
- `effectiveFrom > effectiveTo` и `agentCommissionRateBps !in 0..1000` отклоняются с `400`;
- триггер неизменности `product_versions` продолжает работать.

- [ ] **Step 2: Run test to verify failure**

Run: `OUTBOX_WORKER_ENABLED=false DB_PORT=55432 ./gradlew :services:credit:test --tests "*ProductLifecycle*"`
Expected: FAIL.

- [ ] **Step 3: Implement `V13__product_lifecycle.sql` and update `credit/product`**

Добавить колонки `lifecycle_status`, `effective_from`, `effective_to`, `agent_commission_rate_bps` в `product_versions` (с backfill существующих строк: `ACTIVE` если `active = true`, иначе `ARCHIVED`), обновить `Product`, `ProductVersions`, `ProductEditor`, `ProductJdbcCatalog`, `ProductEditorController`.

- [ ] **Step 4: Run product tests**

Run: `OUTBOX_WORKER_ENABLED=false DB_PORT=55432 ./gradlew :services:credit:test --tests "*product*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add services/credit/src/main/resources/db/migration/V13__product_lifecycle.sql services/credit/src/main/kotlin/ru/creditcabinet/product services/credit/src/test/kotlin/ru/creditcabinet/product
git commit -m "feat(credit): add product lifecycle (DRAFT/ACTIVE/ARCHIVED), effective dates and agent commission bps"
```

---

### Task 4: Предодобренные предложения, схема агентских полей (`V14`) и интеграция со скорингом

**Files:**
- Create: `services/credit/src/main/resources/db/migration/V14__agent_channel_and_preapproved.sql`
- Create: `services/credit/src/main/kotlin/ru/creditcabinet/preapproved/domain/PreApprovedOffer.kt`
- Create: `services/credit/src/main/kotlin/ru/creditcabinet/preapproved/application/PreApprovedOfferService.kt`
- Create: `services/credit/src/main/kotlin/ru/creditcabinet/preapproved/infrastructure/PreApprovedOfferJdbcStore.kt`
- Create: `services/credit/src/main/kotlin/ru/creditcabinet/preapproved/controller/PreApprovedOfferController.kt`
- Modify: `services/credit/src/main/kotlin/ru/creditcabinet/application/infrastructure/ApplicationEntity.kt`
- Modify: `services/credit/src/main/kotlin/ru/creditcabinet/assessment/domain/CreditPolicy.kt` (или `PipelineSteps.kt`)
- Test: `services/credit/src/test/kotlin/ru/creditcabinet/preapproved/PreApprovedOfferTest.kt`

**Interfaces:**
- Consumes: `LoanApplication`, `ProductCatalog`, `CreditPolicy`, `ApplicationStatus`.
- Produces:
  - `V14__agent_channel_and_preapproved.sql`: колонки `agent_id`, `agent_name`, `pre_approved_offer_id`, `agent_commission_rate_bps`, `agent_commission_amount`, `agent_commission_status` в `applications`; таблица `pre_approved_offers` + сиды для ИП Котова, ООО «Северная мебель», ООО «Вектор Логистик».
  - `PreApprovedOfferService`: `listForCompany(companyId)`, `listForCompanies(companyIds)`, `applyForClient(offerId)`, `applyForAgent(offerId, agentId, companyIds)`, `detachIfOutOfBounds(application, terms)`.
  - `GET /api/v1/pre-approved-offers`, `POST /api/v1/pre-approved-offers/{id}/apply`.
  - В `CreditPolicy`: при наличии активного `preApprovedOffer` в лимитах и отсутствии жёстких стоп-факторов — автоматическое `APPROVED` по ставке `offer.rate`.

- [ ] **Step 1: Write failing tests for pre-approved offers and scoring override**

В `PreApprovedOfferTest.kt` проверить:
- клиент видит только активные предодобренные предложения своей компании;
- `apply` создаёт `DRAFT` с предзаполненными суммой/сроком/продуктом и переводит оффер в `USED`;
- при изменении суммы в `DRAFT` выше `offer.maxAmount` привязка `preApprovedOfferId` снимается и оффер возвращается в `ACTIVE` (Review Focus #3);
- при скоринге заявки с валидным `preApprovedOffer` и без стоп-факторов `CreditPolicy` выпускает `APPROVED` по ставке предодобрения; при стоп-факторе (ликвидация/просрочка 90+) — всё равно `REJECTED`.

- [ ] **Step 2: Run test to verify failure**

Run: `OUTBOX_WORKER_ENABLED=false DB_PORT=55432 ./gradlew :services:credit:test --tests "*PreApprovedOfferTest"`
Expected: FAIL.

- [ ] **Step 3: Implement `V14`, `preapproved` package, `ApplicationEntity` mapping and `CreditPolicy` hook**

Создать миграцию `V14`, обновить `ApplicationEntity` под новые колонки, реализовать `preapproved` (domain/application/infrastructure/controller), добавить обработку `preApprovedOffer` в `CreditPolicy` / `PipelineSteps`.

- [ ] **Step 4: Run tests**

Run: `OUTBOX_WORKER_ENABLED=false DB_PORT=55432 ./gradlew :services:credit:test --tests "*PreApproved*" --tests "*CreditPolicy*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add services/credit/src/main/resources/db/migration/V14__agent_channel_and_preapproved.sql services/credit/src/main/kotlin/ru/creditcabinet/preapproved services/credit/src/main/kotlin/ru/creditcabinet/application services/credit/src/main/kotlin/ru/creditcabinet/assessment services/credit/src/main/kotlin/ru/creditcabinet/pipeline services/credit/src/test
git commit -m "feat(credit): add pre-approved offers, agent application columns and scoring integration"
```

---

### Task 5: Сквозной агентский бэкенд, RBAC-проверка мандатов, прокси документов/чата и уведомления (`credit` + `engagement`)

**Files:**
- Create: `services/credit/src/main/kotlin/ru/creditcabinet/agent/domain/AgentAccessPort.kt`
- Create: `services/credit/src/main/kotlin/ru/creditcabinet/agent/infrastructure/IdentityAgentAccessClient.kt`
- Create: `services/credit/src/main/kotlin/ru/creditcabinet/agent/application/AgentApplicationService.kt`
- Create: `services/credit/src/main/kotlin/ru/creditcabinet/agent/controller/AgentController.kt`
- Create: `services/credit/src/main/kotlin/ru/creditcabinet/agent/controller/AgentDtos.kt`
- Modify: `services/credit/src/main/kotlin/ru/creditcabinet/application/service/ApplicationService.kt` (методы `confirmByClient`, `returnToAgentByClient`, блокировка прямого `submit` для `agentId != null`)
- Modify: `services/credit/src/main/kotlin/ru/creditcabinet/application/controller/ApplicationController.kt` (`POST /{id}/confirm`, `POST /{id}/return-to-agent`)
- Modify: `services/credit/src/main/kotlin/ru/creditcabinet/application/service/TransitionNotices.kt` (уведомления клиенту при `REQUEST_CLIENT_CONFIRMATION` и агенту при смене статуса его заявок)
- Modify: `services/credit/src/main/kotlin/ru/creditcabinet/chat/application/ChatService.kt` (поддержка роли `AGENT` через мандат)
- Modify: `services/credit/src/main/kotlin/ru/creditcabinet/document/infrastructure/...` (или `DocumentService` — доступ `AGENT` к документам закреплённых агентских заявок)
- Modify: `docker/gateway/nginx.conf` (добавить маршруты `/api/v1/agent/clients` -> `identity`, `/api/v1/agent/` -> `credit`, `/api/v1/pre-approved-offers` -> `credit`, `/api/v1/loans` -> `credit`)
- Test: `services/credit/src/test/kotlin/ru/creditcabinet/agent/AgentWorkflowIntegrationTest.kt`

**Interfaces:**
- Consumes: `Identity` internal API `/internal/v1/agents/{agentId}/companies/**`, `PreApprovedOfferService`, `ApplicationService`, `ChatService`, `DocumentService`.
- Produces:
  - `GET /api/v1/agent/portfolio`
  - `GET /api/v1/agent/pre-approved-offers` + `POST /api/v1/agent/pre-approved-offers/{id}/apply`
  - `GET /api/v1/agent/applications`, `POST /api/v1/agent/applications`, `GET/PUT /api/v1/agent/applications/{id}`
  - `POST /api/v1/agent/applications/{id}/request-confirmation`, `POST /api/v1/agent/applications/{id}/cancel`, `POST /api/v1/agent/applications/{id}/info-provided`
  - `GET/POST/DELETE /api/v1/agent/applications/{id}/documents/**`
  - `GET/POST /api/v1/agent/applications/{id}/messages`
  - Client endpoints: `POST /api/v1/applications/{id}/confirm`, `POST /api/v1/applications/{id}/return-to-agent`.

- [ ] **Step 1: Write failing integration & RBAC tests in `AgentWorkflowIntegrationTest`**

Проверить полный цикл и негативные кейсы:
- `agent@demo.test` создаёт черновик для ИП Котова -> загружает документ -> вызывает `request-confirmation` -> ИП Котова видит заявку в `PENDING_CLIENT_CONFIRMATION`, подтверждает (`confirm` с `bureauConsent = true`) -> заявка переходит в `SUBMITTED`;
- попытка `agent@demo.test` создать/открыть заявку для `stroygrad` или компании `agent2` -> `404`;
- отзыв мандата в `AgentAccessPort` после создания заявки -> все запросы агента к заявке/документам/чату возвращают `404` (Review Focus #1);
- попытка `agent@demo.test` вызвать `confirm`, `offer/accept`, `sign` -> `403`;
- попытка клиента вызвать прямой `submit` на агентской заявке -> `409` (Review Focus #2);
- при переходе в `DISBURSED` комиссия агента переходит в `ACCRUED`, при `REJECTED` — в `CANCELLED`.

- [ ] **Step 2: Run test to verify failure**

Run: `OUTBOX_WORKER_ENABLED=false DB_PORT=55432 ./gradlew :services:credit:test --tests "*AgentWorkflowIntegrationTest"`
Expected: FAIL.

- [ ] **Step 3: Implement `credit/agent`, client confirmation endpoints, chat/document access, notifications and nginx routes**

Реализовать `IdentityAgentAccessClient` (fail-closed `503`), `AgentApplicationService`, `AgentController`, расширить `ApplicationService`/`ApplicationController`/`ChatService`/`TransitionNotices`, обновить маршруты в `docker/gateway/nginx.conf`.

- [ ] **Step 4: Run full `credit` test suite**

Run: `OUTBOX_WORKER_ENABLED=false DB_PORT=55432 ./gradlew :services:credit:check`
Expected: PASS (все старые + новые тесты, ktlint, JaCoCo >= 75%).

- [ ] **Step 5: Commit**

```bash
git add services/credit docker/gateway/nginx.conf
git commit -m "feat(credit): implement end-to-end AGENT workflow, strict RBAC, documents/chat proxy and commission tracking"
```

---

### Task 6: Поствыдачный цикл кредита и потраншевые выдачи (`credit/servicing` + `V15`)

**Files:**
- Create: `services/credit/src/main/resources/db/migration/V15__loan_servicing_and_tranches.sql`
- Create: `services/credit/src/main/kotlin/ru/creditcabinet/servicing/domain/LoanAccount.kt`
- Create: `services/credit/src/main/kotlin/ru/creditcabinet/servicing/domain/LoanServicingPorts.kt`
- Create: `services/credit/src/main/kotlin/ru/creditcabinet/servicing/application/LoanServicingService.kt`
- Create: `services/credit/src/main/kotlin/ru/creditcabinet/servicing/infrastructure/LoanServicingJdbcStore.kt`
- Create: `services/credit/src/main/kotlin/ru/creditcabinet/servicing/controller/LoanServicingController.kt`
- Modify: `services/credit/src/main/kotlin/ru/creditcabinet/pipeline/PipelineSteps.kt` (создание `loan_account` + 1-го транша + графика при `DISBURSING -> DISBURSED`)
- Test: `services/credit/src/test/kotlin/ru/creditcabinet/servicing/LoanServicingIntegrationTest.kt`

**Interfaces:**
- Consumes: `LoanMath`, `LoanApplication` (`DISBURSED`), `Offer`, `AgentAccessPort`.
- Produces:
  - `GET /api/v1/applications/{id}/loan` -> `LoanAccountResponse(id, status, approvedLimit, disbursedAmount, outstandingPrincipal, revolving, tranches, payments)` (`CLIENT`, `BANK_STAFF`, read-only `AGENT` по мандату)
  - `POST /api/v1/loans/{loanId}/tranches` (`{ amount, purpose }`, только `CLIENT`, `revolving == true`)
  - `POST /api/v1/loans/{loanId}/payments/next` (только `CLIENT`)
  - `POST /api/v1/loans/{loanId}/repay-early` (только `CLIENT`)

- [ ] **Step 1: Write failing tests for post-disbursement lifecycle & tranches**

В `LoanServicingIntegrationTest.kt` проверить:
- после `DISBURSED` автоматически создаётся `loan_account` (`ACTIVE`), график платежей и первый транш (для револьверного продукта — 50% лимита, для обычного — 100%);
- клиент по револьверной кредитной линии выбирает второй транш в пределах остатка -> `disbursedAmount` и `outstandingPrincipal` растут, будущие платежи пересчитываются;
- попытка выбрать транш больше остатка лимита или по неревольверному кредиту -> `400`/`409` (Review Focus #4);
- попытка `AGENT` вызвать `/tranches`, `/payments/next` или `/repay-early` -> `403` (Review Focus #4);
- `payments/next` помечает очередной платёж `PAID` и уменьшает остаток; `repay-early` закрывает кредит (`status = REPAID`, `outstandingPrincipal = 0`).

- [ ] **Step 2: Run test to verify failure**

Run: `OUTBOX_WORKER_ENABLED=false DB_PORT=55432 ./gradlew :services:credit:test --tests "*LoanServicing*"`
Expected: FAIL.

- [ ] **Step 3: Implement `V15`, `credit/servicing` and `PipelineSteps` hook**

Создать таблицы `loan_accounts`, `loan_tranches`, `loan_payments`, реализовать чистую доменную логику в `LoanAccount.kt` (для покрытия JaCoCo >= 75%), адаптер JDBC, контроллер и вызов `loanServicingService.openOnDisbursement(...)` в транзакции `DISBURSED`.

- [ ] **Step 4: Run full backend build**

Run: `OUTBOX_WORKER_ENABLED=false DB_PORT=55432 ./gradlew build`
Expected: PASS по всем 4 сервисам и `platform-common`.

- [ ] **Step 5: Commit**

```bash
git add services/credit
git commit -m "feat(credit): add post-disbursement loan servicing, repayment simulation and revolving tranches"
```

---

### Task 7: Фронтенд агентского кабинета, подтверждения клиента и предодобренных предложений (`frontend/`)

**Files:**
- Modify: `frontend/src/types.ts`
- Modify: `frontend/src/shared/labels.ts`
- Modify: `frontend/src/api/client.ts`
- Modify: `frontend/src/routes/AppRoutes.tsx`
- Modify: `frontend/src/components/AppShell.tsx`
- Modify: `frontend/src/pages/AuthPages.tsx` (добавить кнопки демо-доступа `agent@demo.test` и `agent2@demo.test`)
- Create: `frontend/src/pages/agent/AgentPortfolioPage.tsx`
- Create: `frontend/src/pages/agent/AgentClientPage.tsx`
- Create: `frontend/src/pages/agent/AgentApplicationPage.tsx`
- Modify: `frontend/src/pages/ClientHomePage.tsx` (блок предодобренных предложений + карточки заявок, ожидающих подтверждения)
- Modify: `frontend/src/pages/client/application/ApplicationPage.tsx` (баннер `PENDING_CLIENT_CONFIRMATION` с чекбоксом БКИ и кнопками «Подтвердить и отправить» / «Вернуть агенту»)
- Test: `frontend/src/pages/agent/AgentPortfolioPage.test.tsx`

**Interfaces:**
- Consumes: все REST-эндпоинты из Tasks 1, 4, 5.
- Produces: полный UI-контур для роли `AGENT` (`/agent`, `/agent/clients/:companyId`, `/agent/applications/:id`), клиентский блок предодобренных предложений и экран подтверждения агентской заявки.

- [ ] **Step 1: Write failing Vitest unit test for `AgentPortfolioPage` and client confirmation flow**

Проверить рендер портфеля агента (KPI, комиссии, клиенты, предодобренные бейджи, таблица заявок со «следующим шагом») и отображение кнопок подтверждения/возврата у клиента в `PENDING_CLIENT_CONFIRMATION`.

- [ ] **Step 2: Run Vitest to verify failure**

Run: `cd frontend && npx vitest run src/pages/agent/AgentPortfolioPage.test.tsx`
Expected: FAIL.

- [ ] **Step 3: Implement API client methods, types, routes, Agent pages and Client confirmation / pre-approved UI**

Обновить `types.ts`, `labels.ts`, `api/client.ts`, `AppRoutes.tsx`, `AppShell.tsx`, `AuthPages.tsx`, создать страницы агента и обновить `ClientHomePage.tsx` и `ApplicationPage.tsx`.

- [ ] **Step 4: Run TypeScript check, lint and Vitest**

Run: `cd frontend && npx tsc --noEmit && npm run lint && npx vitest run`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend
git commit -m "feat(frontend): add AGENT cabinet, pre-approved offers and client confirmation UI"
```

---

### Task 8: UI жизненного цикла продуктов, обслуживания кредита/траншей, мобильный адаптив 375px и отчёт жюри

**Files:**
- Modify: `frontend/src/pages/bank/ProductsPage.tsx` (создание нового продукта в `DRAFT`, кнопки «Опубликовать / В архив / Восстановить», поле `agentCommissionRateBps`, даты действия)
- Modify: `frontend/src/pages/bank/BankQueuePage.tsx` и `frontend/src/pages/bank/application/BankApplicationPage.tsx` (бейдж агентского канала)
- Create: `frontend/src/components/LoanServicingBlock.tsx` (остаток долга, транши + кнопка «Запросить транш» для револьверных, график платежей + кнопки «Внести платёж» и «Досрочно погасить»)
- Modify: `frontend/src/pages/client/application/ApplicationPage.tsx` и `frontend/src/pages/agent/AgentApplicationPage.tsx` (встроить `LoanServicingBlock`, для агента read-only)
- Modify: адаптивные стили в `AppShell.tsx`, `ClientHomePage.tsx`, `AgentPortfolioPage.tsx`, `ProductsPage.tsx` (375px без горизонтального переполнения)
- Create: `docs/jury-feedback-report.md` (шаблон-таблица «Комментарий жюри → Что изменили → Как проверить» с заполненными пунктами нашего трека и слотами под пункты Участников 1, 2, 3)
- Test: `frontend/src/components/LoanServicingBlock.test.tsx`

**Interfaces:**
- Consumes: REST-эндпоинты из Tasks 3, 6.
- Produces: UI конструктора продуктов с lifecycle, UI поствыдачного обслуживания и траншей, чистый мобильный адаптив 375px, документ `docs/jury-feedback-report.md`.

- [ ] **Step 1: Write failing Vitest test for `LoanServicingBlock`**

Проверить отображение остатка, списка траншей, кнопки «Запросить транш» (видна для `CLIENT` при `revolving = true`, скрыта в `readOnly` режиме для `AGENT`), вызов `payments/next` и `repay-early`.

- [ ] **Step 2: Run Vitest to verify failure**

Run: `cd frontend && npx vitest run src/components/LoanServicingBlock.test.tsx`
Expected: FAIL.

- [ ] **Step 3: Implement `LoanServicingBlock`, `ProductsPage` lifecycle controls, bank agent badges, mobile fixes and `docs/jury-feedback-report.md`**

Реализовать все UI-компоненты, проверить адаптивность на ширине 375 px и сформировать структурированный отчёт для жюри.

- [ ] **Step 4: Run full verification (backend + frontend)**

Run: `OUTBOX_WORKER_ENABLED=false DB_PORT=55432 ./gradlew build && cd frontend && npx tsc --noEmit && npm run lint && npx vitest run`
Expected: PASS без ошибок компиляции, линтера и тестов.

- [ ] **Step 5: Commit**

```bash
git add frontend docs/jury-feedback-report.md
git commit -m "feat(frontend): add product lifecycle UI, loan servicing with tranches, mobile polish and jury feedback report"
```

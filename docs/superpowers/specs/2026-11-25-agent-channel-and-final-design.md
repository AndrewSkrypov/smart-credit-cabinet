# Дизайн-спецификация финала: Агентский канал, продуктовый контур и оркестрация (Вариант A)

**Дата:** 2026-11-25  
**Статус:** Готова к утверждению пользователем (прошла `builder-check`)  
**Команда и срок:** 4 человека, 4 дня (96 часов), оркестрирующая роль — Участник 4.

---

## 1. Назначение и контекст

На отборочном этапе реализован цифровой кредитный конвейер для малого и микробизнеса (4 сервиса на Kotlin 2.1 + Spring Boot 3.5: `identity`, `credit`, `engagement`, `external-sim`, плюс `libs/platform-common`, React 18 SPA, Nginx gateway, PostgreSQL 16, Valkey, SeaweedFS S3, ClamAV, Prometheus/Grafana, Ollama).

Цель финала — встроить **агентский канал продаж** (агент сопровождает нескольких действующих клиентов банка за вознаграждение) в существующий конвейер так, чтобы действия агента, клиента и банка оставались согласованными, аудируемыми и защищёнными строгой ролевой моделью, и одновременно закрыть ключевые продуктовые и инженерные замечания жюри отборочного этапа с трассировкой «Комментарий → Что изменили → Как проверить».

---

## 2. Цели, не-цели и анти-цели

### Цели
1. **Сквозной агентский сценарий (ядро финала):** агент работает с несколькими действующими компаниями-клиентами, видит их статусы, предодобренные предложения и следующие шаги, готовит заявки и догружает документы, а клиент подтверждает отправку, акцепт оффера и подписание ПЭП внутри кабинета без внешних каналов связи.
2. **Строгая изоляция доступа (RBAC/IDOR):** агент не может получить данные, заявки или документы не закреплённых за ним компаний (`404`), и не может выполнять юридически значимые действия клиента или действия банка (`403`).
3. **Продуктовые доработки по фидбэку жюри:**
   - предодобренные предложения (витрина банка для клиента и агента);
   - жизненный цикл продуктов в конструкторе (`DRAFT → ACTIVE → ARCHIVED`, даты действия, ставка агентской комиссии);
   - поствыдачный жизненный цикл кредита (активный договор, график платежей, внесение платежа / досрочное погашение до `REPAID`);
   - потраншевые выдачи для револьверных продуктов (выборка траншей в пределах одобренного лимита);
   - правки мобильной версии UI (375 px).
4. **Координация параллельных треков:** единые контракты API, ролей, событий и тестовых учёток для Участника 1 (гексагональная архитектура, шлюз, миграции, C4), Участника 2 (облачный сервер организаторов, логи+трейсы+метрики, QA и автотесты) и Участника 3 (быстрый проактивный ИИ-агент).

### Не-цели
- Онбординг новых клиентов банка и открытие расчётных счетов агентом (прямо исключено регламентом финала).
- Выделение 5-го физического микросервиса в рамках 4-дневного спринта (зафиксировано в C4-плане эволюции Участника 1).
- Реальная внешняя отправка SMS, звонков или внешней почты для основного сценария (процесс проходит строго внутри веб-кабинета).
- Обучение собственной ML-модели скоринга (скоринг остаётся детерминированным и воспроизводимым).

### Анти-цели (что сделает работу провалом, даже если код компилируется)
- Утечка чужих клиентов/заявок/документов агенту или возможность подписать/подтвердить заявку за клиента.
- Поломка существующих 10 сценариев, CI-порога JaCoCo >= 75% для `domain` или рассинхронизация `ApplicationStateMachine` с `docs/state-machine.md`.
- Использование закрытых/платных LLM API или подмена решения банка ответом ИИ.
- Нестабильный стенд на сервере организаторов во время 7-минутной защиты.

---

## 3. Ограничения

- **Срок и ресурсы:** 4 дня, 4 разработчика.
- **Автономность:** полный запуск в Docker Compose на синтетических данных без доступа к корпоративным системам; обязательный деплой на выделенный облачный сервер организаторов; доступ в GitHub для `alfa-hack-2026`.
- **Обратная совместимость БД:** существующие миграции `V1..V12` (`credit`), `V1..V5` (`identity`), `V1..V3` (`engagement`) не редактируются; все изменения схемы идут новыми версиями миграций.
- **Память и стек:** сохраняем 4 Spring Boot сервиса (`identity`, `credit`, `engagement`, `external-sim`), чтобы уложиться в бюджет памяти сервера организаторов (8 GiB + профиль `ai`/`monitoring`).

---

## 4. Рассмотренные подходы и выбор

| Вариант | Суть | Компромисс | Решение |
| --- | --- | --- | --- |
| **A (выбран)** | Полный агентский канал + предодобренные предложения + жизненный цикл продуктов + компактный поствыдачный контур и транши внутри текущих 4 сервисов | Плотный 4-дневный график, требуется жёсткая дисциплина контрактов с Дня 1 | **Выбран пользователем.** Закрывает 100% критериев финала и все продуктовые замечания жюри без инфраструктурного риска |
| **B** | Только агентский канал + предодобренные + жизненный цикл продуктов; поствыдачный цикл и транши — в roadmap | Безопаснее по срокам, но слабее по отработке продуктового фидбэка жюри | Отклонён |
| **C** | Вариант A + физический вынос 5-го микросервиса (`loan-servicing`) | Высокий риск сорвать интеграцию, lock-файлы, CI и лимиты памяти за 4 дня | Отклонён (жюри рекомендовало сначала гексагональную структуру и C4) |

---

## 5. Детальный дизайн и контракты для команды

### 5.1 Ролевая модель и привязка клиентов (`libs/platform-common` + `services/identity`)

1. **Новая роль:**
   - В `libs/platform-common/.../TokenClaims.kt`: добавить `Roles.AGENT = "AGENT"` и вычисляемое свойство `TokenClaims.isAgent: Boolean get() = role == Roles.AGENT`.
   - В `services/identity/.../User.kt`: добавить `UserRole.AGENT`. Инвариант в `User.init`: `companyId` и `canSign` у `AGENT` всегда `null` и `false` (агент не принадлежит одной компании и не имеет права подписи за клиента).
2. **Таблица мандатов `agent_clients` в БД `identity` (новая миграция `V6__agent_role_and_clients.sql`):**
   - `id uuid primary key`
   - `agent_user_id uuid not null references users(id)`
   - `company_id uuid not null references companies(id)`
   - `status varchar(16) not null` (`ACTIVE`, `REVOKED`)
   - `assigned_at timestamptz not null`
   - `revoked_at timestamptz`
   - Уникальный частичный индекс по `(agent_user_id, company_id) where status = 'ACTIVE'`.
3. **API `identity`:**
   - Публичный для агента: `GET /api/v1/agent/clients` — возвращает список закреплённых за текущим `AGENT` активных компаний (id, inn, name, legalForm, assignedAt). Для других ролей — `403`.
   - Внутренний (не публикуется gateway):
     - `GET /internal/v1/agents/{agentId}/companies` — список UUID активных компаний агента.
     - `GET /internal/v1/agents/{agentId}/companies/{companyId}/access` — `{ "allowed": true/false, "company": { ... } }`.
   - **Fail-closed:** при недоступности `identity` сервис `credit` отвечает `503` (не разрешает доступ по кэшу/умолчанию).
4. **Синтетические демо-учётки агентов (пароль `Demo2026`):**
   - `agent@demo.test` (роль `AGENT`, «Соколов Артём — партнёрский агент»): закреплены 4 действующие компании — ИП Котова (`client@demo.test`), ООО «Северная мебель» (`mebel@demo.test`), ООО «Вектор Логистик» (`logistic@demo.test`), ООО «Технопарк Плюс» (`technopark@demo.test`).
   - `agent2@demo.test` (роль `AGENT`, «Морозова Елена — агент»): закреплена только ООО «Дальний Восток Агро» (`agro@demo.test`).
   - ООО «СтройГрад» (`stroygrad@demo.test`) и ООО «Ромашка-Трейд» (`romashka@demo.test`) не закреплены ни за одним агентом — используются в негативных RBAC/IDOR тестах.

---

### 5.2 Конечный автомат и процесс агентской заявки (`services/credit`)

1. **Изменения в `ApplicationStatus.kt` и `ApplicationStateMachine.kt`:**
   - Добавить `ActorType.AGENT`.
   - Добавить статус `PENDING_CLIENT_CONFIRMATION` (нетерминальный, `visibleToBank = false` — банк видит заявку только после подтверждения клиента).
   - Добавить события:
     - `REQUEST_CLIENT_CONFIRMATION(setOf(AGENT))`: `DRAFT -> PENDING_CLIENT_CONFIRMATION`. Guard: все параметры заявки валидны (сумма, срок, цель, выручка), кроме `bureauConsent` (его ставит клиент при подтверждении); заявка принадлежит этому агенту (`agentId == actor.id`).
     - `CONFIRM_AND_SUBMIT(setOf(CLIENT))`: `PENDING_CLIENT_CONFIRMATION -> SUBMITTED`. Guard: `bureauConsent == true`, все параметры валидны; запускает стандартную задачу проверки полноты (`pipeline_jobs`) и фиксирует `submittedAt`.
     - `RETURN_TO_AGENT(setOf(CLIENT))`: `PENDING_CLIENT_CONFIRMATION -> DRAFT` (с опциональным комментарием клиента, что поправить).
     - `AGENT_CANCEL(setOf(AGENT))`: `DRAFT -> WITHDRAWN`, `PENDING_CLIENT_CONFIRMATION -> WITHDRAWN` (агент может отменить только свой черновик до отправки в банк).
   - Расширить существующее событие `INFO_PROVIDED(setOf(CLIENT, AGENT))`: на этапе `INFO_REQUESTED` закреплённый агент может загрузить недостающие документы и вернуть заявку на проверку (`INFO_REQUESTED -> SUBMITTED`).
   - Добавить `PENDING_CLIENT_CONFIRMATION` в `WITHDRAWABLE` (клиент может отозвать заявку, подготовленную агентом).
   - Юридическая граница полномочий: `SUBMIT`, `CONFIRM_AND_SUBMIT`, `ACCEPT_OFFER`, `DECLINE_OFFER`, `SIGN` разрешены **строго** `CLIENT` (подписание — только с `canSign = true`). Попытка вызова от `AGENT` возвращает `403 Forbidden`.
   - Видимость и права клиента на агентскую заявку (архитектурное решение оркестратора):
     - В статусе `DRAFT` агентская заявка (`agentId != null`) **скрыта от клиента** (аналогично `visibleToBank = false`), пока агент не переведёт её в `PENDING_CLIENT_CONFIRMATION`.
     - В статусе `PENDING_CLIENT_CONFIRMATION` клиент видит все параметры и загруженные агентом документы, но **не редактирует черновик напрямую**: доступны только три действия — `CONFIRM_AND_SUBMIT` (с `bureauConsent = true`), `RETURN_TO_AGENT` (с комментарием) или `WITHDRAW`.
     - Прямой `SUBMIT` клиентом для агентской заявки (`agentId != null`) запрещён (`409 Conflict`), чтобы не обходить шаг `PENDING_CLIENT_CONFIRMATION`.
   - Обновить [docs/state-machine.md](file:///Users/andrejskrypov/Desktop/Project/Альфа-Changellenge_Умный_кабинет/docs/state-machine.md) и `ApplicationStateMachineTest` синхронно с таблицей переходов.
2. **Поля агента и комиссии в `applications` (миграция `V13__agent_channel_and_preapproved.sql`):**
   - `agent_id uuid` (nullable — null для прямых клиентских заявок)
   - `agent_name varchar(255)` (снимок имени агента)
   - `pre_approved_offer_id uuid` (nullable)
   - `agent_commission_rate_bps integer` (фиксируется из версии продукта при подаче, например 150 = 1.50%)
   - `agent_commission_amount bigint` (пересчитывается при выдаче от фактической суммы, до выдачи — расчётная оценка)
   - `agent_commission_status varchar(16)` (`NONE`, `EXPECTED`, `ACCRUED`, `CANCELLED`). При переходе в `DISBURSED` статус комиссии автоматически становится `ACCRUED`; при `REJECTED`/`WITHDRAWN`/`CLOSED` — `CANCELLED`.
3. **Матрица доступа для агента в `credit`:**
   - Доступ к заявке, её документам, истории и чату разрешён агенту только если одновременно выполнены два условия: `application.agentId == caller.userId` И `identity` подтверждает активный мандат `(caller.userId, application.company.id)`. При нарушении — `404 Not Found`.
   - Чат по заявке (`credit/chat`): разрешить `AGENT` писать и читать сообщения в закреплённых за ним агентских заявках (событие уведомляет клиента и банк).
   - Уведомления (`engagement`): при `REQUEST_CLIENT_CONFIRMATION` — уведомление компании клиента «Агент подготовил заявку №… — проверьте и подтвердите»; при смене статуса агентской заявки (`INFO_REQUESTED`, `OFFER_ISSUED`, `SIGNING`, `DISBURSED`, `REJECTED`, `RETURN_TO_AGENT`) — уведомление также отправляется `agentId`.
4. **Эндпоинты агентского контура (`/api/v1/agent/**`):**
   - `GET /api/v1/agent/portfolio` — сводка: закреплённые клиенты, счётчики заявок по статусам, «требуют действия», суммы комиссий (`expected`, `accrued`).
   - `GET /api/v1/agent/applications` — список заявок агента с фильтрацией по статусу/клиенту и вычисленным полем `nextAction` («Заполните черновик», «Ожидает подтверждения клиента», «Догрузите документы до …», «Клиент подписывает договор», «Комиссия начислена»).
   - `POST /api/v1/agent/applications` — создание черновика заявки для закреплённой `companyId`.
   - `GET /api/v1/agent/applications/{id}` / `PUT /api/v1/agent/applications/{id}` — просмотр и редактирование черновика.
   - `POST /api/v1/agent/applications/{id}/request-confirmation` — перевод в `PENDING_CLIENT_CONFIRMATION`.
   - `POST /api/v1/agent/applications/{id}/cancel` — отмена черновика (`AGENT_CANCEL`).
   - `POST /api/v1/agent/applications/{id}/info-provided` — завершение дозапроса документов.
   - **Единый URL-контракт документов и чата агента (архитектурное решение оркестратора):** агент всегда работает строго под префиксом `/api/v1/agent/applications/{id}/documents` и `/api/v1/agent/applications/{id}/messages` (контроллеры вызывают те же application-сервисы `credit/document` и `credit/chat` с проверкой агентского мандата). Это полностью изолирует контур агента в Nginx gateway (Участник 1) и упрощает матрицу автотестов (Участник 2).
   - Для клиента: `POST /api/v1/applications/{id}/confirm` (`{ "bureauConsent": true }`) и `POST /api/v1/applications/{id}/return-to-agent` (`{ "comment": "..." }`).

---

### 5.3 Предодобренные предложения (`credit`)

1. **Таблица `pre_approved_offers` (в `V13`):**
   - `id uuid primary key`, `company_id uuid not null`, `product_code varchar(32) not null references products(code)`, `max_amount bigint not null`, `min_term_months integer not null`, `max_term_months integer not null`, `rate numeric(5,2) not null`, `expires_at timestamptz not null`, `status varchar(16) not null` (`ACTIVE`, `USED`, `EXPIRED`, `DISMISSED`), `reason_text varchar(500) not null`, `created_at timestamptz not null`.
2. **Синтетическое наполнение:** при миграции создаются активные предодобренные предложения для ИП Котова (оборотный кредит до 3 млн под 17.5%), ООО «Северная мебель» (кредитная линия до 4 млн под 18.0%), ООО «Вектор Логистик» (инвестиционный кредит до 5 млн под 16.9%).
3. **API и поведение:**
   - `GET /api/v1/pre-approved-offers` (для `CLIENT` — предложения своей компании).
   - `GET /api/v1/agent/pre-approved-offers` (для `AGENT` — предложения только по закреплённым компаниям, опционально фильтр `?companyId=...`).
   - `POST /api/v1/pre-approved-offers/{id}/apply` (клиент) и `POST /api/v1/agent/pre-approved-offers/{id}/apply` (агент) — создаёт предзаполненный `DRAFT` в лимитах предодобренного предложения, переводит оффер в `USED` (при отмене черновика можно вернуть в `ACTIVE`).
4. **Влияние на скоринг и выпуск оффера (архитектурное решение оркестратора):**
   - Если заявка связана с активным `preApprovedOfferId` и при подаче сумма `<= offer.maxAmount`, а срок в `offer.minTermMonths..offer.maxTermMonths`, то при прохождении `SCORING` (при отсутствии жёстких стоп-факторов — ликвидация, просрочка 90+, возраст бизнеса < 3 мес.) `CreditPolicy` автоматически выпускает `APPROVED`-оффер по ставке `offer.rate` (не уводя заявку в `MANUAL_REVIEW`).
   - Если в `DRAFT` пользователь изменил сумму или срок за пределы предодобренного предложения, `preApprovedOfferId` автоматически отвязывается (статус оффера возвращается в `ACTIVE`), и заявка идёт по стандартному скорингу.

---

### 5.4 Жизненный цикл продуктов (`services/credit/.../product`)

1. **Модель (миграция `V14__product_lifecycle_and_servicing.sql`):**
   - Добавить `lifecycle_status varchar(16) not null default 'ACTIVE'` (`DRAFT`, `ACTIVE`, `ARCHIVED`), `effective_from timestamptz`, `effective_to timestamptz`, `agent_commission_rate_bps integer not null default 150`.
   - `Product.active` сохраняется как производное (`lifecycleStatus == ProductLifecycleStatus.ACTIVE`), чтобы не сломать существующий публичный каталог и тесты.
2. **Переходы в конструкторе (`RISK_ADMIN`, архитектурное решение оркестратора поверх append-only `product_versions`):**
   - Таблица `product_versions` остаётся строго append-only (триггер из `V5` не ослабляется). `lifecycle_status`, `effective_from`, `effective_to` и `agent_commission_rate_bps` пишутся в каждую новую ревизию `product_versions`, а `products.current_version` указывает на последнюю ревизию.
   - Добавляется `POST /api/v1/bank/products` для создания нового продукта с нуля в статусе `DRAFT` (не виден клиентам и агентам в публичном каталоге).
   - `POST /api/v1/bank/products/{code}/publish` (`DRAFT -> ACTIVE`), `POST /api/v1/bank/products/{code}/archive` (`ACTIVE -> ARCHIVED`), `POST /api/v1/bank/products/{code}/restore` (`ARCHIVED -> ACTIVE`) принимают `expectedVersion` и `reason` и создают новую неизменяемую строку в `product_versions`. Для уже активного продукта редактирование условий (`PUT`) по-прежнему создаёт новую `ACTIVE`-ревизию без раздвоения указателей (оптимально для 4-дневного спринта). Поданные заявки всегда работают со своим снимком версии.

---

### 5.5 Поствыдачный цикл и потраншевые выдачи (`services/credit/.../servicing`)

1. **Сущности (в `V14`):**
   - `loan_accounts`: `id uuid primary key`, `application_id uuid unique not null references applications(id)`, `company_id uuid not null`, `contract_number varchar(64) not null`, `product_code varchar(32) not null`, `revolving boolean not null`, `approved_limit bigint not null`, `disbursed_amount bigint not null`, `outstanding_principal bigint not null`, `interest_rate numeric(5,2) not null`, `term_months integer not null`, `status varchar(16) not null` (`ACTIVE`, `REPAID`), `next_payment_date date`, `next_payment_amount bigint`, `created_at timestamptz not null`, `closed_at timestamptz`.
   - `loan_tranches`: `id uuid primary key`, `loan_account_id uuid not null references loan_accounts(id)`, `tranche_number integer not null`, `amount bigint not null`, `purpose varchar(255)`, `disbursed_at timestamptz not null`.
   - `loan_payments`: `id uuid primary key`, `loan_account_id uuid not null references loan_accounts(id)`, `payment_number integer not null`, `due_date date not null`, `principal_amount bigint not null`, `interest_amount bigint not null`, `total_amount bigint not null`, `status varchar(16) not null` (`SCHEDULED`, `PAID`), `paid_at timestamptz`.
2. **Автоматическое создание при `DISBURSED`:**
   - В транзакции завершения выдачи (`DISBURSING -> DISBURSED`) создаётся `loan_accounts` (`status = ACTIVE`), первый транш в `loan_tranches` (для обычного кредита — на 100% суммы; для револьверной кредитной линии — на 50% от лимита или запрошенный первый транш, чтобы на демо сразу был виден доступный остаток лимита для второго транша!) и график `loan_payments` через `LoanMath`.
3. **Операции обслуживания:**
   - `GET /api/v1/applications/{id}/loan` — детали активного кредита, список траншей и график платежей (доступно `CLIENT` своей компании, `BANK_STAFF` и `AGENT` своей агентской заявки в режиме read-only).
   - `POST /api/v1/loans/{loanId}/tranches` (только `CLIENT` своей компании, только если `revolving == true` и `status == ACTIVE`): запрос дополнительного транша в пределах `approved_limit - disbursed_amount`; обновляет `disbursed_amount`, `outstanding_principal` и пересчитывает будущие `SCHEDULED` платежи.
   - `POST /api/v1/loans/{loanId}/payments/next` (только `CLIENT` своей компании): имитация оплаты очередного платежа; когда все платежи `PAID` или `outstanding_principal == 0` -> `status = REPAID`, `closed_at = now`.
   - `POST /api/v1/loans/{loanId}/repay-early` (только `CLIENT` своей компании): досрочное полное погашение -> все оставшиеся платежи закрываются, `outstanding_principal = 0`, `status = REPAID`.

---

### 5.6 Фронтенд и мобильная полировка (`frontend/`)

- **Навигация:** в `AppShell.tsx` и `AppRoutes.tsx` добавляется контур `AGENT` (`/agent`, `/agent/clients/:companyId`, `/agent/applications/:id`).
- **Портфель агента (`/agent`):** карточки KPI (активных клиентов, заявок в работе, ожидаемая и начисленная комиссия), фильтр «Требуют моего действия», список закреплённых компаний с бейджами предодобренных предложений и кнопкой «Оформить заявку», таблица всех заявок агента с колонкой «Следующий шаг».
- **Карточка агентской заявки:** редактор черновика, загрузка документов, переписка, журнал истории с ролями, кнопка «Отправить клиенту на подтверждение»; на этапах клиента (`OFFER_ISSUED`, `SIGNING`) — явный статус ожидания без кнопок акцепта/подписи.
- **Кабинет клиента:** блок предодобренных предложений на `ClientHomePage`; явная карточка подтверждения агентской заявки (`PENDING_CLIENT_CONFIRMATION`) с чекбоксом согласия БКИ и кнопками «Подтвердить и отправить» / «Вернуть агенту»; блок «Обслуживание кредита и транши» на выданной заявке (`DISBURSED`).
- **Кабинет банка:** бейдж «Агентский канал: <Имя агента>» в очереди и карточке заявки; управление статусом жизненного цикла (`DRAFT/ACTIVE/ARCHIVED`) и комиссией в `ProductsPage`.
- **Мобильная версия (375 px):** устранить горизонтальные переполнения, наложение кнопок в шапках карточек и узкие поля ввода на всех ключевых экранах (проверяется Playwright на viewport 375×812).

---

### 5.7 Границы владения файлами и синхронизация 4-дневного спринта

- **Участник 4 (Оркестратор):** `services/identity` (роль `AGENT` + `agent_clients`), `services/credit` (новые миграции `V13`, `V14`, агентский сценарий, `preapproved`, `servicing`, расширение `product`), `frontend/`, `docs/state-machine.md`, `docs/final-contracts.md`, итоговый отчёт жюри.
- **Участник 1 (Архитектура):** гексагональный рефакторинг существующих пакетов `credit` и `engagement/notification` (координирует PR по пакетам, чтобы не конфликтовать с `V13`/`V14`), модульный `docker/gateway/`, `COMMENT ON` (отдельная миграция после `V14`) + rollback-скрипты, C4-диаграммы.
- **Участник 2 (Инфра/Observability/QA):** `deploy/`, `monitoring/` + OTel/JSON-логи в `platform-common`, `docs/operations.md`, `docs/test-scenarios.md`, `e2e/`, `contracts/postman/`, `security/`.
- **Участник 3 (ИИ):** `services/engagement/.../assistant/`, `frontend/src/components/AssistantPanel.tsx`, `scripts/assistant-eval*`, `docs/assistant-eval.md`.

---

## 6. Что остаётся на усмотрение исполнителя (Builder)

- Точные тексты UI-подсказок, формулировки уведомлений и визуальная вёрстка карточек в рамках существующих дизайн-токенов ([docs/design-tokens.md](file:///Users/andrejskrypov/Desktop/Project/Альфа-Changellenge_Умный_кабинет/docs/design-tokens.md)).
- Выбор конкретного механизма вызова `identity` из `credit` (расширение существующего `IdentityClient` с коротким таймаутом и fail-closed).
- Конкретные параметры расчёта графика при доборе транша (аннуитет от нового остатка на оставшееся число месяцев через существующий `LoanMath`).
- Внутренняя структура DTO и мапперов при условии соблюдения описанных REST-маршрутов, статусов и кодов ошибок (`401`, `403`, `404`, `409`).

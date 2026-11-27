# Дизайн: закрытие инженерных «жёлтых» пунктов финала (Подход A)

Дата: 2026-11-26
Ветка: `feat/final-partial-gaps` (worktree `.worktrees/final-partial-gaps`, база `main` = `3e039dd`)
Основание: роадмап финала (`docs/plans/final-roadmap-2026-11-26.md`), раздел 3 (пункты `2.1`, `2.4`, `6.1–6.3`).

## 1. Цель и мотивация

Закрыть три оставшихся инженерных («жёлтых») замечания жюри без вмешательства в параллельную интеграцию веток (`feature/final-integration`), публичные API-контракты, фронтенд и документы Этапа A:
1. **Пункт 2.4 (Rollback миграций):** добавить воспроизводимые `U*`-скрипты отката бизнес-миграций `credit` (`V13–V16`), проверить цикл `V → U → V` на локальной PostgreSQL и зафиксировать процедуру в `db/rollback/README.md`.
2. **Пункты 6.1–6.3 (Тестовая документация и негативные автотесты):** расширить автоматическое покрытие граничных значений, некорректных данных, IDOR по ресурсам агента (документы и чат) и отказов `identity`/источников; оформить каталог тест-кейсов с уникальными ID и трассировкой на требования в `docs/test-scenarios.md`.
3. **Пункт 2.1 (Гексагональная архитектура + ArchUnit):** завершить разделение на слои `domain / application / controller / infrastructure` в оставшихся модулях (`identity/user`, `identity/company`, `credit/advice`, `credit/integration`, `engagement/assistant`) и закрепить архитектурные границы ArchUnit-тестами во всех трёх сервисах (`credit`, `identity`, `engagement`).

## 2. Цели, не-цели и анти-цели

**Цели:**
- `U13–U16` для `credit` полностью откатывают DDL миграций `V13–V16` и позволяют повторно накатить `V13–V16` без ошибок; существующий скрипт `deploy/check-schema-rollback.py` (`V17–V18`) не ломается.
- Негативные и граничные интеграционные/доменные тесты покрывают агентский канал (документы, чат, мандат, сбой `identity` → `503`, `403` на запрещённые действия) и обслуживание кредита (границы траншей и платежей, IDOR).
- `docs/test-scenarios.md` содержит структурированный каталог тест-кейсов с ID и трассировкой на требования задания и комментарии жюри, проходит `python3 scripts/check-docs.py`.
- Оставшиеся модули `identity`, `credit` и `engagement` разнесены по слоям гексагональной архитектуры, а `HexagonalArchitectureTest` (ArchUnit) в каждом из трёх сервисов проверяет границы на каждой сборке `./gradlew test`.
- Все тесты и `ktlintCheck` проходят локально (`DB_PORT=55432`).

**Не-цели:**
- Не меняем публичные REST-маршруты, OpenAPI-спецификации, фронтенд и режим обучения (шаг 5 параллельного плана).
- Не трогаем `docs/research.md`, `docs/jury-feedback-report.md`, `docs/plans/final-roadmap-2026-11-26.md` и `.worktrees/final-integration` (шаги 1–6 параллельного плана).
- Не добавляем новые Flyway-миграции и не редактируем существующие `V*`-миграции.
- Не удаляем скрипты оценки ИИ (`scripts/assistant-*.py` / `*.json`), чтобы не сломать возможные ссылки из CI в `feature/final-integration`.

**Анти-цели:**
- Нарушение бинарной/HTTP-совместимости существующих эндпоинтов или Spring-бинов при рефакторинге пакетов.
- Появление merge-конфликтов в файлах, активно редактируемых параллельно.
- Английские сообщения коммитов (все коммиты в ветке пишутся на русском языке по правилам команды).

## 3. Рассмотренные подходы и выбор

- **Подход A (выбран):** только инженерные «жёлтые» пункты (`2.4` rollback `U13–U16`, `6.1–6.3` негативные автотесты и каталог тест-кейсов, `2.1` гексагональная архитектура + ArchUnit). Нулевой риск поломки API/UI и минимальный риск конфликтов при слиянии в `feature/final-integration`.
- **Подход B (отложен):** Подход A + продуктовые усиления (`1.1–1.4`, `4.2`: просрочка, справка о закрытии, автогенерация предодобрений). Затрагивает OpenAPI, фронтенд и потенциально новую миграцию `V19` — целесообразнее выполнять после стабилизации `feature/final-integration`.
- **Подход C (отклонён):** только продуктовые усиления без `2.1`, `2.4` и `6.1–6.3` — оставляет открытым приоритетное архитектурное замечание жюри.

## 4. Архитектура и детали реализации

### 4.1. Rollback бизнес-миграций `credit` (`V13–V16`)

В `db/rollback/credit/` добавляются четыре обратных SQL-скрипта:
- `U16__outbox_trace_parent.sql` — удаляет колонку `trace_parent` из `delivery_outbox`.
- `U15__loan_servicing_and_tranches.sql` — удаляет таблицы `loan_payments`, `loan_tranches`, `loan_accounts` в порядке обратном FK.
- `U14__agent_channel_and_preapproved.sql` — удаляет индексы `idx_applications_pre_approved`, `idx_applications_agent`, колонки агентского канала и предодобрения из `applications`, а затем таблицу `pre_approved_offers`.
- `U13__product_lifecycle.sql` — возвращает `products_code_current_version_fkey` в `not deferrable`, удаляет ограничение `product_versions_effective_window` и колонки `agent_commission_rate_bps`, `effective_to`, `effective_from`, `lifecycle_status` из `product_versions`.

Добавляется отдельный скрипт `deploy/check-business-rollback.py`, который на временной копии БД прогоняет `U18 → U17 → U16 → U15 → U14 → U13`, затем повторно накатывает `V13 → V14 → V15 → V16 → V17 → V18` и сверяет состав таблиц и колонок до и после цикла. `db/rollback/README.md` обновляется с таблицей `U13–U16` и командой репетиции. Существующий `deploy/check-schema-rollback.py` не изменяется.

### 4.2. Негативные автотесты и каталог тест-кейсов

Добавляются отдельные тестовые классы (без раздувания существующих файлов, чтобы избежать merge-конфликтов):
- `services/credit/src/test/kotlin/ru/creditcabinet/agent/AgentNegativeAndBoundaryIntegrationTest.kt`:
  - Граничные значения суммы и срока при создании/обновлении черновика агентом (`0`, отрицательные, ниже минимума продукта, выше максимума продукта → `400`).
  - Неполные/некорректные данные: пустой или несуществующий `productCode`, отрицательная выручка, попытка подтверждения клиентом без согласия БКИ → `400`/`409`.
  - IDOR по документам и чату агента: загрузка/список/скачивание документа и отправка/чтение сообщения по чужой заявке и по заявке клиента с отозванным мандатом → `404`.
  - Отказ `identity` (исключение/таймаут в `AgentAccessPort`) → fail-closed `503`.
  - Запрещённые действия агента: попытка подтвердить, подписать, вызвать банковский маршрут андеррайтинга → `403`.
- `services/credit/src/test/kotlin/ru/creditcabinet/servicing/LoanServicingBoundaryIntegrationTest.kt`:
  - Границы выборки транша: `0`, отрицательная сумма, сумма больше доступного остатка лимита, выборка на неревольверном и на уже погашенном кредите → `400`/`409`.
  - Границы платежей: досрочное погашение с суммой `<= 0` или больше остатка долга, повторная оплата уже оплаченного платежа → `400`/`409`.
  - IDOR и RBAC: доступ к счёту чужой компании → `404`, попытка агента вызвать `POST .../tranches` или `POST .../payments` → `403`.

В `docs/test-scenarios.md` добавляется раздел «Каталог тест-кейсов и трассировка требований» с таблицей кейсов (`TC-01` .. `TC-24`), привязкой к требованиям задания/жюри, типу проверки (позитивный / негативный / граничный / безопасность / восстановление) и точному имени автотеста.

### 4.3. Гексагональная архитектура и ArchUnit-тесты

1. **Подключение ArchUnit:**
   - В `gradle/libs.versions.toml` добавляется `archunit = "1.4.0"` и библиотека `archunit-junit5 = { module = "com.tngtech.archunit:archunit-junit5", version.ref = "archunit" }`.
   - В `services/credit/build.gradle.kts`, `services/identity/build.gradle.kts` и `services/engagement/build.gradle.kts` добавляется `testImplementation(libs.archunit.junit5)`, lockfile обновляются через `--write-locks`.

2. **Доразнос оставшихся модулей:**
   - `identity/user`: добавляется `user/application/UserService.kt`, `UserController` делегирует в `UserService`.
   - `identity/company`: добавляется `company/application/CompanyService.kt`, `CompanyController` делегирует в `CompanyService`.
   - `credit/advice`: `AdviceController` переносится в `advice/controller/`, `ManualReviewEvents` разделяется на порт в `advice/application/` и реализации outbox/HTTP-доставки в `advice/infrastructure/` (с сохранением всех Spring-бинов и маршрута `/internal/v1/underwriting/applications/{id}/advice`).
   - `credit/integration`: `ExternalPorts.kt` переносится в `integration/domain/` (интерфейсы внешних источников), `SimulatorClients.kt` — в `integration/infrastructure/`.
   - `engagement/assistant`: 10 файлов раскладываются по пакетам:
     - `assistant/domain/`: `Knowledge.kt`, `ProductPicker.kt`, `AssistantNextSteps.kt`, `ApplicationFacts.kt`, доменные интерфейсы `LanguageModel` и `AssistantReferenceCache`.
     - `assistant/application/`: `AssistantService.kt`, `ManualReviewSummaries.kt`.
     - `assistant/controller/`: `AssistantController.kt`.
     - `assistant/infrastructure/`: реализации `LanguageModel` (Ollama / rules-only), реализация `AssistantReferenceCache`, `AssistantMetrics.kt`.
   - Все существующие тесты и импорты обновляются, сохраняются имена таблиц, эндпоинтов, метрик и JSON-контрактов.

3. **ArchUnit-тесты границ:**
   - `services/credit/src/test/kotlin/ru/creditcabinet/architecture/HexagonalArchitectureTest.kt`
   - `services/identity/src/test/kotlin/ru/creditcabinet/architecture/HexagonalArchitectureTest.kt`
   - `services/engagement/src/test/kotlin/ru/creditcabinet/architecture/HexagonalArchitectureTest.kt`
   - Проверяемые инварианты:
     - Классы в `..domain..` не зависят от `..controller..`, `..infrastructure..`, `org.springframework.web..`, `jakarta.persistence..`, `org.springframework.jdbc..`.
     - Классы в `..controller..` не зависят напрямую от `..infrastructure..` и `jakarta.persistence..`.
     - Классы в `..application..` не зависят от `..controller..` и `org.springframework.web.bind.annotation..`.

## 5. Проверка готовности

- `python3 deploy/check-business-rollback.py` против локальной PostgreSQL 16 (`127.0.0.1:55432`) проходит без ошибок.
- `DB_PORT=55432 ./gradlew :services:identity:check :services:credit:check :services:engagement:check` (за исключением Docker-зависимых тестов Valkey/ClamAV/S3, которые прогоняются в CI) — полностью зелёный, включая `ktlintCheck` и все три `HexagonalArchitectureTest`.
- `python3 scripts/check-docs.py` — 0 ошибок.
- Все коммиты в ветке `feat/final-partial-gaps` оформлены на русском языке.

## 6. Что остаётся на этап реализации (builder)

- Точный список внутренних методов, выносимых в `UserService` и `CompanyService` в `identity`, определяется по текущему коду `UserController` и `CompanyController`.
- При обновлении `engagement/assistant` необходимо проверить, чтобы не сломались Spring-конфигурации `@ConfigurationProperties` и тесты `AssistantApiTest`, `ManualReviewSummariesTest`, `OllamaModelTest`, `AssistantNextStepsTest`, `ProductPickAndSummaryTest`.

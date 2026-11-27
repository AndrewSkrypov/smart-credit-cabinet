# Отчёт по отработке замечаний жюри и результатам финального спринта

**Проект:** «Умный кабинет» — цифровой кредитный конвейер для малого и микробизнеса  
**Релиз финала:** Агентский канал, продуктовый жизненный цикл, поствыдачное обслуживание и транши  
**Ветка:** `main` в GitLab организаторов (резервная копия — GitHub)

---

## 1. Комментарий жюри → Что изменили → Как проверить

Статусы: ✅ сделано и проверяется, 🟡 сделано частично (что осталось — в той же строке),
⏸ сознательно не делали (почему — в той же строке).

### 1. Продукт и бизнес-логика

| № | Комментарий | Статус | Что изменили | Как проверить |
| --- | --- | --- | --- | --- |
| 1.1 | Предодобренные предложения, проактивное предложение продукта | ✅ | Таблица `pre_approved_offers` (`V14`), витрина у клиента и агента, заявка по предложению в один клик; в скоринге оффер в пределах лимита выпускается по ставке предложения. Банк формирует предложение по оборотам и данным БКИ (`POST /api/v1/pre-approved-offers/generate`), клиенту уходит уведомление через outbox. Отбор компаний по расписанию — в плане пилота | `PreApprovedOfferTest`, `PreApprovedGenerationIntegrationTest`, `AgentWorkflowIntegrationTest`; `client@demo.test` → `/client` → «Оформить» |
| 1.2 | Потраншевые выдачи | ✅ | `loan_tranches` (`V15`): для кредитной линии первый транш — 50% лимита, остальное выбирается траншами в кабинете | `LoanAccountDomainTest`, `LoanServicingIntegrationTest`; `mebel@demo.test` → выданный кредит → «Выбрать транш» |
| 1.3 | Жизненный цикл после выдачи | ✅ | Кредитный счёт, график, очередной платёж, досрочное погашение до статуса «Погашен»; у агента — только просмотр | `LoanServicingIntegrationTest`, `LoanServicingBlock.test.tsx`, e2e `agent-channel.spec.ts` |
| 1.4 | Конструктор и жизненный цикл продуктов | ✅ | Статусы `DRAFT → ACTIVE → ARCHIVED` и восстановление, даты действия, комиссия агента поверх версий; поданные заявки сохраняют свою версию | `ProductLifecycleIntegrationTest`, `VersionEditors.test.tsx`, e2e `release-products.spec.ts`; `risk@demo.test` → `/bank/products` |

### 2. Архитектура и бэкенд

| № | Комментарий | Статус | Что изменили | Как проверить |
| --- | --- | --- | --- | --- |
| 2.1 | Гексагональная архитектура до конца (приоритет) | ✅ / 🟡 | Слои `domain / application / controller / infrastructure` в `credit` (`agent`, `application`, `advice`, `chat`, `integration`, `offer`, `preapproved`, `product`, `servicing`), `engagement` (`assistant`, `notification`) и `identity` (`user`, `company`); границы проверяет ArchUnit на каждой сборке. Осталось: модули сервисного контура `credit/document` и `credit/financing` — перенос после стабилизации контура, чтобы не конфликтовать с текущей поставкой | `HexagonalArchitectureTest` в `credit`, `identity`, `engagement` |
| 2.2 | Микросервисы и C4 с планом эволюции | ✅ | C4: контекст, контейнеры, компоненты и план выделения сервисов в [c4-architecture.md](c4-architecture.md). Пилот `analytics` (Kafka-проекция статусов) — первый шаг этой эволюции, включается флагом | `docs/c4-architecture.md`; `./gradlew -PalphaAnalytics=true :services:analytics:test` |
| 2.3 | Разделить API Gateway по контурам | ✅ | `docker/gateway/conf.d/`: клиент, агент, банк, identity, engagement, запрет `/internal`, свои лимиты на контур; то же в Helm | `docker/gateway/conf.d/*`, `python3 security/gateway_rate.py` |
| 2.4 | Rollback миграций и `COMMENT ON` | ✅ | `COMMENT ON` во всех сервисах; обратные скрипты `U13–U18` бизнес-миграций и комментариев, репетиция `V → U → V` в CI; для `V19–V27` — восстановление снимка и runbook передачи владения (`U24`, `U26`, `U27`); повтор каждой версии Flyway из снимка в CI | `python3 deploy/check-business-rollback.py`, `deploy/check-schema-rollback.py`, шаг CI «Restore and replay every Flyway version» |

### 3. Наблюдаемость и инфраструктура

| № | Комментарий | Статус | Что изменили | Как проверить |
| --- | --- | --- | --- | --- |
| 3.1 | Логи, трейсы, метрики | ✅ | OpenTelemetry с `traceparent` через HTTP и outbox, Tempo; JSON-логи с `traceId` и маскированием ПДн в Loki; метрики Prometheus, дашборды и алерты в Telegram | `docker compose --profile monitoring up -d && python3 monitoring/verify.py`; [operations.md](operations.md) |
| 3.2 | Стабильный демо-стенд, развёртывание на сервере организаторов | ✅ | GitLab, CI/CD и стенды развёрнуты на сервере организаторов: Helm-чарт кабинета, HTTPS, публичное демо отдельно от защищённого Dev, Grafana за входом | https://credit-cabinet.ru, `helm/cabinet/README.md`, [deployment.md](deployment.md) |

### 4. ИИ-агент и автоматизация

| № | Комментарий | Статус | Что изменили | Как проверить |
| --- | --- | --- | --- | --- |
| 4.1 | Скорость и проактивность | ✅ | Потоковые ответы (SSE) со справкой до генерации, кэш типовых ответов в Valkey, `next-steps` заранее подсвечивает риски и следующий шаг | `AssistantApiTest`, `AssistantNextStepsTest`, `AssistantReferenceCacheIntegrationTest`; [assistant-eval.md](assistant-eval.md) |
| 4.2 | Автономность в допустимых рамках | ✅ | Система сама формирует предодобрение и уведомляет клиента; напоминание о недостающих документах в один клик агента; подсказки андеррайтеру обновляются при ручном рассмотрении; черновик по предодобрению готовится автоматически и всегда требует подтверждения агента и клиента. Решение и юридически значимые действия остаются за человеком | `AssistantNextStepsTest`, `AgentAutoPrepareDraftIntegrationTest`, `PreApprovedGenerationIntegrationTest`, e2e `assistant-advice.spec.ts` |

### 5. UI/UX

| № | Комментарий | Статус | Что изменили | Как проверить |
| --- | --- | --- | --- | --- |
| 5.1 | Мобильная версия | ✅ | Адаптив кабинетов агента, клиента, банка и обслуживания кредита на 375 px; режим обучения проверен на телефоне | e2e на проекте `mobile` (375×812): `agent-channel.spec.ts`, `polish-responsive.spec.ts`, `onboarding-tours.spec.ts` |

### 6. Тестирование и QA

| № | Комментарий | Статус | Что изменили | Как проверить |
| --- | --- | --- | --- | --- |
| 6.1 | Подробная структурированная тестовая документация без дублей | ✅ / 🟡 | [test-scenarios.md](test-scenarios.md): каталог тест-кейсов `TC-01…TC-29` с трассировкой на требования и замечания жюри, агентский канал, режим обучения, замер канала. Осталось: объединить отчёты оценки ИИ в один | [test-scenarios.md](test-scenarios.md) |
| 6.2 | Негативные сценарии, некорректные данные, границы доступа | ✅ | Матрица доступа на 35 маршрутов; граничные суммы и сроки, неполные данные, IDOR документов и чата, отказ `identity`, просрочка и чужой кредит — в интеграционных тестах и в e2e через шлюз | `AgentNegativeAndBoundaryIntegrationTest`, `LoanServicingBoundaryIntegrationTest`, e2e `boundary-negative.spec.ts`, `python3 security/matrix.py` |
| 6.3 | Больше автотестов | ✅ | Бэкенд 432 теста, фронтенд 195, e2e агентского канала и режима обучения, Postman агентского контура | `./gradlew build`, `cd frontend && npx vitest run`, `cd e2e && npm test` |

### Сверх замечаний: что добавили для финала

| Что | Зачем | Как проверить |
| --- | --- | --- |
| Агентский канал: роль, мандаты, портфель, подтверждение клиентом, комиссия | Обязательный сценарий финала; обоснование — [agent-research.md](agent-research.md) | e2e `agent-channel.spec.ts`; `python3 scripts/agent-channel-metrics.py --runs 10` |
| Режим обучения: туры по кабинету и ключевым экранам для каждой роли | Агенту и предпринимателю без банковской экспертизы понятны действия и следующие шаги | Меню «Обучение» в шапке; e2e `onboarding-tours.spec.ts`; `OnboardingTour.test.tsx` |
| Замеры канала и план развития | Отделить измеренное от гипотез, показать масштабирование и стоимость | [final-metrics.md](final-metrics.md), [scaling-and-roadmap.md](scaling-and-roadmap.md) |

---

## 2. Демо-учётные записи для проверки жюри (пароль `Demo2026`)

| Роль | Email | Назначение на демо |
| --- | --- | --- |
| `AGENT` | `agent@demo.test` | Соколов Артём — закреплённые компании: ИП Котова, ООО «Северная мебель», ООО «Вектор Логистик», ООО «Технопарк Плюс» |
| `AGENT` | `agent2@demo.test` | Морозова Елена — закреплена только ООО «Дальний Восток Агро» (проверка изоляции портфелей) |
| `CLIENT` | `client@demo.test` | ИП Котова — подтверждение агентской заявки, предодобренный оборотный кредит, обслуживание и транши |
| `CLIENT` | `mebel@demo.test` | ООО «Северная мебель» — предодобренная револьверная кредитная линия (потраншевые выборки) |
| `CLIENT` | `stroygrad@demo.test` | ООО «СтройГрад» — не закреплена за агентами (негативные IDOR-проверки) |
| `UNDERWRITER` | `underwriter@demo.test` | Очередь и карточка банка с бейджем «Агентский канал: …» |
| `RISK_ADMIN` | `risk@demo.test` | Конструктор продуктов (`DRAFT → ACTIVE → ARCHIVED`, даты действия, комиссия агента) |

---

## 3. Команды полной верификации трека оркестратора

```bash
# Backend (identity, credit, engagement, external-sim, JaCoCo domain >= 75%)
OUTBOX_WORKER_ENABLED=false DB_PORT=55432 ./gradlew build

# Frontend (TypeScript, ESLint 0 warnings, Vitest unit/component tests)
cd frontend && npx tsc --noEmit && npm run lint && npx vitest run
```

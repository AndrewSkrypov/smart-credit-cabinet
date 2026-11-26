# Синтетическая приёмка финального финансирования

Используйте отдельный локальный стенд одной интегрированной ревизии с Credit, Identity, Engagement, External Sim и SPA. Для счёта и выписки включите проверку загрузок по инструкции стенда с действующим ClamAV. Сохраните SHA из `state.json`, хеши запущенных JAR и SPA и путь к отчёту каждой попытки.

В свежей БД Credit после штатных миграций примените фикстуру. Для нативного стенда `qa_state` указывает на созданный каталог состояния, `qa_pg_port` — на его PostgreSQL-порт. `psql` запускается из поставки этого стенда.

```bash
psql -X -h "$qa_state/pg-socket" -p "$qa_pg_port" -U cabinet -d credit \
  -v ON_ERROR_STOP=1 -v synthetic_fixture=isolated \
  -f e2e/fixtures/final-agent-financing-reserve.sql
```

Фикстура добавляет две действующие револьверные линии «Северной мебели» с остатками 80 000 ₽ и 200 000 ₽ и отдельную поданную заявку с открытым дозапросом выписки. Повторное применение сохраняет уже существующие данные. Для повторного прогона API поднимите свежую БД, чтобы проверять единственную фактическую выборку в новом журнале.

Команды из `e2e` интегрированной ревизии после `npm ci` и `npm run typecheck`:

```bash
export CABINET_FINAL_FINANCING_E2E=1
export CABINET_URL="$qa_loopback_gateway"
export PLAYWRIGHT_CHANNEL=chrome
export CABINET_EXPECTED_INVOICE_MODE=MANUAL
PLAYWRIGHT_JUNIT_OUTPUT_FILE="$qa_evidence/api.xml" \
  npm run test:desktop -- tests/final-agent-financing.spec.ts --grep '@api' \
  --reporter=line,junit --output "$qa_evidence/api-artifacts"
PLAYWRIGHT_JUNIT_OUTPUT_FILE="$qa_evidence/browser.xml" \
  npm run test:desktop -- tests/final-agent-financing.spec.ts --grep '@browser' \
  --reporter=line,junit --output "$qa_evidence/browser-artifacts"
```

`MANUAL` проверяется на нативном стенде с выключенной моделью. Для отдельного прогона с действующей локальной моделью укажите `CABINET_EXPECTED_INVOICE_MODE=MODEL`. Сохраните приложение `invoice-mode` из отчёта обеих попыток. При успешном `MODEL` сценарий сверяет фактическую сумму, две даты и источник. Без текстового слоя ожидается `MANUAL`.

Браузерный сценарий готовит и возвращает пакет через API, проверяет отображение вариантов в трёх ролях, подтверждает актуальную версию и БКИ, проводит ручное решение и подписывает выдачу в браузере. API отдельно проверяет отказ по устаревшей версии, отзыв мандата, выписку и денежный эффект повтора.

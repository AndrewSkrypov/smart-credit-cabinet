# MCP кредитного кабинета

Локальный MCP по stdio для агента. Сервер выдаёт следующий шаг и срок, показывает сводку и сравнивает два варианта сохранённой «Примерки кредита». В обычном режиме ответы помечены `source: synthetic-fixture`, для запуска нужны только Node.js и установленные зависимости.

## Установка и запуск

Версии закреплены в `.nvmrc`, `package.json` и `package-lock.json`. Проверяемая версия Node.js — 24.13.0, официальный SDK — `@modelcontextprotocol/sdk@1.32.2`. Stdio-клиент и сервер согласовали редакцию `2025-11-25`.

Из корня рабочего дерева:

```sh
cd integrations/cabinet-mcp
nvm use
npm ci --ignore-scripts
npm run build
npm test
npm run --silent verify
```

`verify` сам запускает и завершает сервер. Для запуска процесса из MCP-клиента используйте `node dist/main.js --profile agent`. В stdout сервера идут только сообщения JSON-RPC, диагностика идёт в stderr. Для клиента с форматом `mcpServers` заполните абсолютные пути из [client.example.json](client.example.json). Автоматический прогон использует программный SDK-клиент.

Обычная установка получает зависимости из npm. С предварительно заполненным доверенным кэшем установка также работает офлайн через `npm ci --ignore-scripts --offline --cache /absolute/path/to/cache`.

## Инструменты

- `list_agent_next_actions` принимает `{"limit":2}` или `{}`. Лимит применяется после проверки доступа и отбора записей со следующим действием. Пустой портфель возвращает `items: []`.
- `get_agent_application_summary` принимает `{"applicationId":"6b7a0f51-2958-4228-bb7b-68f23d57b161"}`. Для приватного запроса результат содержит состояние `OPEN` и путь к блоку кабинета. Имена, идентификаторы, метаданные и содержимое файлов не выдаются.
- `preview_agent_financing_scenario` принимает `{"applicationId":"40188819-ffc2-4caa-a57f-a8f9414391c1","delayDays":6,"alternativeTermMonths":24}`. Попробуйте также `delayDays: 5`. При задержке 5 дней поступление учитывается, остатки составляют `104730.99` и `188208.39` ₽. При 6 днях остатки составляют `-35269.01` и `48208.39` ₽. Денежные значения, ставка и версии передаются строками.

Все три инструмента объявляют `readOnlyHint`. В интерфейсе провайдера есть только список, сводка и пробный расчёт. Превью опирается на фиксированные эталоны без переноса формул LoanMath. Оно не создаёт редакцию и не меняет кредитное решение. Ошибка возвращает `isError: true` и текстовое JSON-содержимое с полями `code` и `message`, без прежних данных.

Профиль запуска задаёт синтетическую роль и сессию вне аргументов инструментов. `agent` видит две заявки, `empty` видит пустой список, `client` получает `FORBIDDEN`, запуск без профиля и `expired-session` дают `UNAUTHENTICATED`. Профили `summary-only`, `expired-mandate` и `missing-basis` проверяют область доступа, истёкший мандат и отсутствующую основу расчёта. Чужая и неизвестная заявка, исключённая компания, истёкший и отозванный мандат возвращают одинаковый `CASE_NOT_FOUND`. Тесты меняют состояние мандата и зависимости через тестовый провайдер за пределами MCP.

## Приёмка с Credit

Локальная приёмка stdio выполнена на `3e9d2971a3e442c98299f00b2efb010f71c3878c`. Транспорт Streamable HTTP подготовлен поверх снимка protected `477588b7be2525a0417087e0adf623315d282a91`.

Локальный провайдер следует DTO из Credit `bf797cd542c0e3def842e05aac6dcf0e98b759ec`. Подключайте его только к отдельному синтетическому экземпляру после проверки готовности HTTP. Backend должен слушать `127.0.0.1` с профилем `agent-mcp-local` и `server.forward-headers-strategy=none`. Выделенный короткоживущий JWT выпустите штатным тестовым механизмом на стороне стенда.

1. Задайте `CABINET_MCP_CREDIT_ORIGIN` как `http://127.0.0.1:<port>` либо HTTPS на том же адресе с проверяемым сертификатом. Задайте `CABINET_MCP_CREDIT_TOKEN_FILE` как абсолютный путь к приватному обычному файлу пользователя процесса с правами `0600`. Передавайте только путь. Файл читается на каждый вызов, для ротации заменяйте его атомарно приватным файлом того же владельца.
2. Запускайте `node dist/main.js --local-credit` с этим окружением. Успешные HTTP-ответы проходят закрытые схемы и дают `source: credit`. Провайдер обращается только к `GET /api/v1/agent/mcp/priorities`, `GET /api/v1/agent/mcp/applications/{id}/summary` и `POST /api/v1/agent/mcp/applications/{id}/financing-scenario/preview` в режиме `SAVED_SCENARIO`. Запросы идут напрямую, без переадресаций, с общим сроком 3 секунды до конца чтения, максимум 128 КиБ JSON. На отказ провайдер возвращает устойчивый код без прежних данных.
3. После `npm run build` передайте абсолютный путь к `test/credit-live.ts` в `CABINET_MCP_TEST_SCRIPT` совместного Spring harness. Harness передаёт Node параметры `CABINET_CREDIT_ORIGIN`, `CABINET_CREDIT_TOKEN_FILE`, `CABINET_CREDIT_APPLICATION_ID` (UUID выделенного черновика с эталоном 2 млн ₽, 20%, 12/24 мес., приход 140 000 ₽) и `CABINET_CREDIT_SOURCE_SHA` (точный SHA запущенного Credit). Для ручного прогона передайте те же параметры в `npm run --silent verify:credit`. Проверка вызывает три инструмента через stdio, сверяет сводку, список, превью для дней 5 и 6 и стабильные ошибки. В отчёте только статус, версии и длительности.
4. Вместе с владельцем Credit сверяйте веб и MCP на одинаковых вводных и версиях. До и после серии превью сверяются версия заявки, число редакций, документы, бизнес-события и outbox. Сохранение и проверка синтетических полномочий на стороне Credit идут отдельно.

`npm test` проверяет SDK-контракт и провайдер с подставным транспортом. `npm run test:credit-http` запускает отдельный loopback-stub и проверяет настоящий HTTP-транспорт, включая отказ переадресации и срок чтения тела. Проверка со stub не является приёмкой с Credit.

Веб-сессионный JWT не используется как внешний грант. Удалённый Streamable HTTP потребует отдельного этапа с OAuth, токеном для аудитории MCP, минимальным scope, сроком действия, отзывом и доверенной передачей личности в Credit.

Локальные доказательства находятся в [отчёте](acceptance/README.md) и [трёх ответах](acceptance/tool-responses.json).

## Кандидат Streamable HTTP

Контейнер запускает `node dist/http-main.js` на внутреннем TCP 8080. Обычный запуск `node dist/main.js --profile agent` по-прежнему использует stdio. У HTTP-транспорта три прежних read-only инструмента, ответы JSON, протокол `2025-11-25`, сессии отключены. Клиент обнаруживает ресурс через `GET /.well-known/oauth-protected-resource/api/mcp`, авторизованные вызовы идут через `POST /api/mcp`. `GET /healthz` подтверждает только состояние слушателя.

- `MCP_ISSUER` — канонический HTTPS origin среды. `MCP_RESOURCE` — тот же origin с `/api/mcp`. Scope — `cabinet:mcp:read`. Dev и Prod требуют свои OAuth-клиенты и redirect URI.
- `MCP_IDENTITY_ORIGIN` и `MCP_CREDIT_ORIGIN` — утверждённые приватные HTTPS-адреса сервисов. Credit использует порт 8443. `MCP_IDENTITY_CA_FILE`, `MCP_CREDIT_CA_FILE`, `MCP_CREDIT_CERT_FILE` и `MCP_CREDIT_KEY_FILE` — абсолютные пути к PEM-файлам для TLS и mTLS. `CABINET_MCP_IDENTITY_SERVICE_TOKEN` — отдельный секрет среды из защищённого механизма развёртывания. Его использует только заголовок запроса к Identity.
- Identity проверяется на каждом авторизованном запросе через `POST /internal/mcp/authorize`. В тело кладётся внешний access token. Identity проверяет срок действия и отзыв grant, точные resource/scope и актуальность пользователя. Адаптер проверяет закрытый ответ `{actorId, role, resource, scope, actorAssertion}`, включая роль `AGENT` и точные resource/scope. В Credit отправляется только assertion. При сбоях зависимости ответ закрывается ошибкой без старых данных.
- Внутренние маршруты Credit — `GET /internal/mcp/credit/priorities`, `GET /internal/mcp/credit/applications/{id}/summary`, `POST /internal/mcp/credit/applications/{id}/financing-scenario/preview`. Заголовок — `X-Cabinet-Mcp-Actor-Assertion`. До подтверждения точных схем и авторизации на внутренних сервисах эту раскладку нельзя считать приёмкой.

Собирайте Dockerfile с контекстом `integrations/cabinet-mcp`. CI может передать `NODE_IMAGE` с закреплённым registry digest. Развёртывание требует TLS на шлюзе, ограничений ingress/egress и проверки цепочки Identity → MCP → Credit. Публикуйте только `/api/mcp` и пути discovery/OAuth, согласованные с владельцами Identity и Edge, после отрицательных тестов Dev. Привилегированные служебные маршруты остаются внутри сети.

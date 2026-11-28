# Локальная приёмка

Проверена фиксированная синтетика. Согласованная редакция MCP — `2025-11-25`. Версии — Node 24.13.0, SDK 1.32.2, Zod 4.3.6 и TypeScript 5.9.3.

```sh
npm ci --ignore-scripts --offline --cache /absolute/path/to/warmed/npm-cache
npm run build
npm test
npm run --silent verify
```

Тесты охватывают обмен JSON-RPC и завершение по EOF, закрытые схемы, разрешённый портфель и приватную выписку, безопасные отказы по роли и доступу, отзыв и отказ зависимости после успешного вызова, два эталона задержки и повторное превью без изменений. Программный SDK-клиент записал три структурированных ответа в [tool-responses.json](tool-responses.json).

Lockfile SHA-256 — `7171e2a2d20f07ab1fa25f2ac6fd096af454a04339311e7bd7532578b43da88a`. Для независимой сверки запустите команды в чистом checkout или после переноса коммита на согласованную базу.

## Стыковка с локальным Credit

Контракт адаптера проверен через SDK-клиента с подставным HTTP-транспортом. Набор `npm test` включает 13 проверок, в том числе неизменный офлайн-режим, точный allowlist операций, закрытые ответы, ошибку без прежних данных и ротацию файла с синтетической сессией.

Для отдельной проверки настоящего loopback HTTP с тестовым сервером выполните `npm run test:credit-http` в среде, разрешающей привязку к `127.0.0.1`. Для проверки с самого MCP до запущенного Credit выполните `npm run --silent verify:credit` с окружением из [README](../README.md). Результаты stub и Credit следует фиксировать отдельно.

## Remote OAuth to Credit

Use a throwaway HTTPS stack with the remote MCP release enabled, an
existing active synthetic agent, a saved scenario with the day-5/day-6
receipt fixture, and a different synthetic case outside that agent's
mandate. The acceptance client must be registered for the exact numeric
loopback HTTP callback used by this test. Collect the synthetic Credit
database snapshot separately before and after, including application
versions, scenario revisions, documents, business events and outbox.

Run the build and `node test/oauth-credit-live.ts` on Node 24.13.0 with
`CABINET_MCP_SYNTHETIC_ACCEPTANCE=1`, the matching HTTPS origin in
`CABINET_MCP_ACCEPTANCE_ORIGIN`, and
`CABINET_MCP_ACCEPTANCE_FIXTURE` pointing at an owner-only absolute JSON
path. Its keys are `email`, `password`, `clientId`, `redirectUri`,
`applicationId` and `foreignApplicationId`. For a private trust chain
set `NODE_EXTRA_CA_CERTS` to the approved CA PEM. Use ordinary certificate
and hostname validation. The script never follows the OAuth redirect, and
reports only a fixed stage label, with no credential or fixture values.
It revokes the access token whether the checks succeed or fail.

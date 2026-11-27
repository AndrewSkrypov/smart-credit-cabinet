# Dev: переключение четырёх сервисов

Этот документ подготавливает следующий неизменяемый выпуск после local.
Он описывает допуск и ожидаемые результаты; успешная выкладка remote здесь
не заявляется до получения фактических квитанций GitLab и Dev.

В состав кандидата также включены `onboarding-tours` и содержащая её ветка
`final-materials` на `bce766855cbe119092ff318d47a5d5bfedac4b1d`.
Это новая дельта frontend, бизнес-портов и денежных operation receipts V27/V2.
Результаты CI #104 ниже относятся к исходной базе, а эта дельта требует
нового source-review и CI точного объединённого SHA.

База — защищённый merge `a04241ff21c9dcd9e70e0fc917e3feb795b40b38`.
Его исходник `0bd867b61b3563dab7d507ec70134652e2c5a092` прошёл все 33 обязательных
задания [CI #104](https://gitlab.credit-cabinet.ru/pump/smart-credit-cabinet/-/pipelines/104),
включая frontend, E2E и пять финальных бизнес-сценариев.
[Pipeline #110](https://gitlab.credit-cabinet.ru/pump/smart-credit-cabinet/-/pipelines/110)
выполняет защищённую поставку четырёх режимов local; её результат проверяется отдельно.

## Допуск к слиянию

- CI точного SHA этой ветки: все 33 обязательных задания успешны; source-review закрыто.
- Dev local принят: manifest связан с указанной базой, четыре владельца local,
  runtime и mTLS готовы, штатная миграция Credit завершена, HTTPS и роли проверены.
- Свежий отчёт Dev подтверждает current local, точный digest и отсутствие pending.
- Согласованная копия и журнал предыдущей поставки сохранены. Alpha сохраняет
  поколение `g8b1a02f4d62af8c0`, исходный marker и `read_enabled=false`.

MR CI этой ветки разрешено выполнять параллельно проверкам local.
Слияние и запуск remote допускаются только после фактической приёмки local.
Для Servicing используется общая очередь `cabinet-ci-servicing-acceptance`.

## Следующий защищённый выпуск

Создать отдельный API pipeline на новом защищённом merge SHA с переменными:

| Переменная | Значение |
| --- | --- |
| `CABINET_ALPHA_PILOT` | `1` |
| `CABINET_DEV_DIAGNOSTICS` | `1` |
| `CABINET_CATALOG_DEV_MODE` | `remote` |
| `CABINET_ASSESSMENT_DEV_MODE` | `remote` |
| `CABINET_SERVICING_DEV_MODE` | `remote` |
| `CABINET_DOCUMENTS_DEV_MODE` | `remote` |

Дождаться всех 39 обязательных quality/verify/extended проверок. Перед
release-images сверить свежие current/pending и ресурсы. После публикации
повторить диагностику и сверить неизменяемый manifest, режимы, digest образов,
OCI config ID и SHA восьми JAR. Затем выполнить штатный dev-deploy того же pipeline.

## Фактическая приёмка remote

Сохранить отдельные квитанции нового release-images, dev-deploy и диагностики
после выкладки. Проверить четыре remote, единственного writer, fencing и mTLS,
Assessment generation, Servicing authority и Documents epoch. Сверить реально
работающие image ID/revision с manifest, frontend JavaScript по HTTPS и роли.
Alpha должна сохранить исходное поколение и marker, подтвердить catch-up и
чтение из SQL. Current соответствует новому manifest, pending отсутствует.

Финальные бизнес-сценарии выполняются в изолированном CI; production-like Dev
проверяется штатными runtime-, HTTPS- и ролевыми пробами без подмены живой БД
тестовой фикстурой. Измерение projection/fallback при включённом чтении остаётся
условием отдельного включения чтения проекции; теневой режим сохраняется.

При сбое повторяется тот же manifest и согласованная попытка под deploy.lock.
Возврат владельцев и восстановление выполняются по
[инструкции поставки](../../deploy/README.md#первый-выпуск-четырёх-runtime-на-dev)
и сохранённой копии; manifest уже использованного SHA не переписывается.
При переносе и возврате сохраняются все `loan_operations`, точность их
JSON-снимков и исходный результат повтора ключа. При копии local V26 до миграции
отсутствующая таблица означает пустую историю; включение нового владельца
всё равно требует применённых V27/V2. U27 предназначен для изолированной
SQL-репетиции и не используется для штатного возврата владельцев.
Prod в эту поставку не входит.

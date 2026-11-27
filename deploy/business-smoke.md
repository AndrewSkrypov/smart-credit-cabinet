# Dev business API smoke — подготовка следующего инкремента

Модуль `business_smoke.py` не имеет сетевого CLI. Только обычный host-side
контроллер может вызвать `run(deployment,state,current,desired,report,lock_fd=...)`.
Это отдельный следующий инкремент поверх принятого protected merge324550d,
не доказательство текущей Dev-поставки. Hook применён в `dev-release.py` этого
исходного инкремента. Историческое предложение patch сохранено только в private
snapshot; дополнительного patch-файла в поставке нет.

## Границы запуска

До создания HTTP клиента проверяются реальный `Deployment`, computed
`dev_payment_reserve_simulation`, exact cabinet-dev/nonlocal/approved HTTPS,
DEMO_ACCOUNTS=false/SIM_CONTROL_ENABLED=false, версионированный manifest/SHA/digest,
held deploy.lock, реальные private current/pending, immutable attempt previous/target,
четыре REMOTE и успешный привязанный runtime report. Raw CLI/ENV-утверждения не
включают harness. Новый config-owner включает отдельный
DEV_PAYMENT_RESERVE_SIMULATION_ENABLED только своим scoped Dev overlay.

Private jury.json0600 читается только на хосте: NOFOLLOW, regular file, owner,
размер128KiB, точная схема трёх ролей. AGENT/UNDERWRITER входят обычным HTTPS.
Jury CLIENT/company/mandate не используются для мутаций. Два новых CLIENT
регистрируются public signup по новым валидным00…ИНН; источник генерирует для
них синтетическую действующую банковскую компанию. Все новые мандаты выдаются
этими CLIENT существующему jury AGENT.

HTTPS фиксирован на dev.credit-cabinet.ru, системная проверка CA/hostname;
нет proxy/redirect/альтернативного origin/флага отключения TLS. Ограничения:
400 requests,20min,25s на весь request включая заголовки через POSIX deadline,
256KiB request,1MiB response,32MiB суммарных response bytes, identity encoding.
Токены, пароли, ПЭП-коды и PDF остаются в памяти. Ошибки — фиксированные коды,
без body/URL credentials. Генерируются только небольшие синтетические PDF.

## Что проверяет API и чего оно не доказывает

| Согласованный сценарий | Живой API proof будущего запуска |
|---|---|
| Выбор срока/возврат/версия/подача/выдача | Свои2m WORKING_CAPITAL,12/24months, калькулятор, сохранённый handed snapshot, изменённая revision, stale409, foreign404/agent403, один CONFIRM_AND_SUBMIT, одинаковый пакет банка, normal manual decision→ПЭП→симулированная выдача |
| Мандат/допуск | Новый собственный мандат; bank404 до подачи, повтор отзыва, agent404 на карточке/events/documents/messages, CLIENT продолжает свой handed пакет; оригинальный jury mandate не меняется |
| Приватная выписка | Новый собственный agent application→normal submit/manual-review→request-info BANK_STATEMENT, foreign upload404, CLIENT upload, bank exact bytes, agent/foreign403/404, отсутствие privateID/name/text на agent surfaces, satisfied=true, client info-provided |
| Резерв | Две новые OVERDRAFT через тот же полный normal цикл: approved160000→первый транш80000→available80000; approved400000→available200000. PENDING не даёт draw; DELAYED130000, low shortfall50000, RECEIVED140000→required0/projected40000; same event replay, conflicting event409; high confirm exact version replay→ровно один дополнительный130000 tranche |
| Живой browser | NOT_RUN. API проходит смысл цепочки, но не доказывает browser/рендеринг/клики/скриншоты. Существующий CI Playwright proof и поздний Dev browser proof остаются отдельными |

Для нормального manual-review задаётся синтетическая declaredRevenue1b;
обычный source00… генерирует6–60m, discrepancy является normal manual reason.
Если фактическая политика, продукты или выдача не дают требуемого состояния,
smoke **останавливается**, не меняет SQL/правила/каталог и не объявляет PASS.
Проверяются актуальные условия каталога для линий160k/400k. Ставка сценария
берётся из реального snapshot; арифметика перепроверяется независимо, даты
относительные будущие с тем же смыслом задержки. Абсолютные reserve числа
не масштабируются и не заменяются похожими значениями.

MODEL записывается только из фактического API ответа с проверенными суммой и
датой; image-only invoice обязан дать MANUAL. Недоступность модели оставляет
MANUAL и ручной выбор полей; выключать модель через sim-control не требуется.
Полные ответы с текстом, кодами и документами в report/ledger не копируются.
Ledger содержит только UUID/alias, шаги и whitelisted синтетические финансовые
факты, checksums/счётчики, наблюдённый invoice mode.

## Journal и cleanup

`state/business-smoke/<SHA>-<runUUID>.json`:0700 directory/0600 file, atomic
replace, fsync. До каждого изменения сохраняется intent без payload. Для
создания отдельно сохраняется intent до HTTP; полученный UUID сохраняется
до очистки intent. Network/JSON/crash ambiguity не запускает повтор создания.
Повторный запуск на том же SHA при наличии ledger останавливается для
операторского reconciliation, включая crash между PASS и current commit.

В finally отменяются только записанные собственные незавершённые заявки и
новые мандаты. DISBURSED/terminal accounts, audit и документы сохраняются и
учитываются как retained synthetic data; нет SQL/delete-данных/volume cleanup.
Unknown creation intent остаётся неизвестным; по нему нет enumeration или
спекулятивного удаления. При неполной cleanup статус INCOMPLETE, не PASS.

`cleanup_saved(...)` — отдельный host/operator вызов в том же проверенном
controller context. Он читает сохранённый ledger, восстанавливает вход новых
CLIENT через HMAC(jury AGENT password,runUUID+alias) в памяти, проверяет exact
user/company и обрабатывает только известные UUID. Пароли новых CLIENT не
записываются. Смена исходного jury password или неопределённое создание
потребует ручного operator reconciliation, а не чужих login/массовой очистки.
Метод не повторяет business actions и никогда не предоставляет acceptance.

## Hook и Alpha

Контроллер вызывает smoke после verify-release только при существующем pending,
`has_all_runtimes`, четырёх REMOTE и computed Dev flag, **до current commit**.
Verify-only, LOCAL и обычный повтор уже принятого current без pending не создают
business fixtures или ledger. Ошибка попадает в существующий fail-closed
контроллер с закрытием Caddy/pending retained. Proof вложен в
`report.release.business_smoke`, поэтому четыре top-level поля старого
extractor не расширяются. После всех signup/заявок/отзывов/cleanup выполняется
новая фактическая `verify_alpha_catchup` к сохранённому поколению. Старый
immutable alpha-ready journal не переписывается, свежий proof идёт в итоговый
report с `manifest_sha256` именно текущего release. Сам catchup helper это
поле не добавляет; hook дополняет свежий результат без изменения исходной
immutable квитанции. Применение hook к исходнику не является фактическим запуском
на Dev; сам модуль не пишет current/pending/attempt/backup/ENV.

Context regression использует настоящий `RegistryRelease`: компонент Catalog
хранится как `runtime["catalog-grpc"]`, а бизнес-имя доступно через `release.catalog`.
Недопустимая metadata до создания API нормализуется в фиксированный
`SmokeError` (подкласс RuntimeError), чтобы существующий контроллер закрыл
candidate ingress. Raw KeyError/AttributeError и содержимое metadata наружу
не передаются. Offline hook tests читают фактический контроллер и исполняют его
выбранный try/except блок: настоящая функция catchup работает с mock-портами,
после business/cleanup; ошибка закрывает mock Caddy и сохраняет current/pending.
Отдельная регрессия вызывает фактический `deploy()` для same-current/no-pending:
не создаются business API, ledger, новая попытка или резервная копия.
Это не фактическая проверка HTTP, Kafka, SQL или текущего Dev.

## CI без новых mandatory gates

1. `test_business_smoke_delivery.py` автоматически входит в существующий
   `helm-chart`: quality.yml уже обнаруживает `test_*delivery*.py`. Здесь
   transport/context/journal/isolation/cleanup и отрицательные financial
   outputs проверяются offline, без сети и JVM.
2. Фактический HTTP flow использует те же `Smoke` methods из test-only companion
   `ci_business_api.py` в существующем `deployment:[local]`, после всех прежних
   restore assertions. Socket строго127.0.0.1, TLS/SNIlocalhost и private provider
   CA; shared Dev не используется. `deployment:[s3]` сохраняет прежний flow без
   повторения API smoke. Новый job не добавлен, inventory33/39 сохранён.
3. Изолированный отчёт честно имеет base-local scope, не four-REMOTE/Dev/browser.
   Детали запуска, bounded timeout70мин и остающийся four-REMOTE GAP описаны в
   `ci-business-api.md`. Production context/HTTPS policy не ослаблены; host
   harness не получает сетевого CLI. Actual HTTP/PG/engine запуск этого нового
   сценария ещё не выполнен.

# Изолированная проверка бизнес-API

Существующий job `deployment: [local]` проверяет новые бизнес-сценарии до их первого исполнения на
общем Dev. Это интеграционный тест реальных HTTP/HTTPS API и приложений, а не
подмена бизнес-ответов mock-объектами. Число gates33/39 и правила extended jobs
не меняются: на MR после33 обязательных PASS оператор штатно запускает этот
manual job как дополнительную бизнес-приёмку; на protected pipeline он входит
в39 обязательных. Без его actual PASS приёмка нового бизнес-инкремента не закрыта.
В уже созданные pipelines новые проверки не добавляются.

## Исполнение

Provider остаётся `deploy/test-local.py`: собственные случайные Compose-проекты,
версии пяти образов строго равны `CI_COMMIT_SHA`, обычные миграции, HTTPS,
согласованная копия, восстановление в пустой target и проверки файлов/прав.
Исходный набор проверок не сокращается. Новый `--business-api-smoke` запускается
после существующих count/restore/memory assertions на восстановленном target.
Этот режим запрещает `--keep`, S3, legacy backup и optional AI profiles.
S3-элемент matrix продолжает прежний flow без повторного business smoke. Общий
timeout matrix увеличен35→70мин: прежние35 + API budget20 + два bounded Credit
restart10 + overhead5. Новый job/дубликат provider не добавляется.

Только новый CI helper добавляет versioned
`ci/gitlab/quality/compose.business-api.yaml` к конкретному owned target Credit.
Физический Compose project остаётся случайным `cabinet-prod-restore-test-*`;
логический context Credit — `cabinet-dev`, Dev-флаг — `true`, оба DEMO/SIM флага —
`false`. Это явная тестовая конфигурация, не разрешение включить флаг через
произвольный shell/ENV и не изменение production `Deployment` policy.

Перед изменением проверяются `CI=true`, numeric job id, exact source SHA,
изолированный unix Docker socket, daemon label `cabinet-ci=true`, actual Compose
labels и OCI revision пяти контейнеров. Default Docker socket запрещён. Helper
не имеет URL/credentials CLI. API socket всегда `127.0.0.1:<allocated target port>`;
TLS проверяет private CA provider и server name `localhost`. DNS и shared Dev не
используются; redirects, compressed/oversized responses и request budgets
проверяются независимо от host smoke transport.

AGENT и UNDERWRITER создаются только в Identity этого проверенного target через
существующий private operator SQL pattern: две новые случайные записи `users`,
штатный bcrypt, без company/signing прав. Credentials остаются в памяти и не
попадают в отчёты. CLIENT, компании, мандаты, заявки, решения банка, подпись,
выдача и резерв создаются исключительно обычными API. SQL loan/reserve/fence
fixtures отсутствуют.

Используются методы `business_smoke.Smoke`: два новых CLIENT с ИНН `00…`, свои
мандаты, версия сценария и согласие клиента, manual underwriting, ПЭП,
приватная выписка и 403/404, отзыв мандата, две возобновляемые линии
160000/400000 с доступными 80000/200000, required130000/shortfall50000,
поступление140000 и повтор confirm с единственным эффектом130000.

Незавершённые свои объекты закрываются существующим `Smoke.cleanup()`, выданные
синтетические кредиты сохраняются до уничтожения всего временного проекта.
В `finally` helper возвращает исходную Compose command, пересоздаёт Credit с
флагом `false` и проверяет actual container flags. Любая ошибка business flow,
cleanup или восстановления флага не позволяет provider выставить `passed=true`.
Внешний provider в своём `finally` удаляет только созданные им два проекта.

## Доказательства и ограничения

Публикуемый `deploy/reports/test-*/business-api.json` содержит actual flags,
source/image identities, own fixture ids и финансовые before/after; не содержит
credentials, токенов, request/response bodies или закрытого cabinet.env.
Частный журнал остаётся под `deploy/runtime/test-*/ci-business/business-smoke/`
с правами0700/0600 и в CI artifacts не включён. Его `manifest_sha256=null`:
Registry manifest и immutable Dev attempt в этом тесте не создаются.

Scope отчёта всегда `isolated-base-local-api`, `dev_accepted=false`,
`four_remote_accepted=false`, browser `NOT_RUN`. В исходных названиях проверок
`Smoke` сохранён исторический суффикс `remote_single_effect`; actual runtime
здесь embedded, и этот суффикс не доказывает remote transport.

Полный four-REMOTE provider требует LOCAL baseline, штатную согласованную копию
и REMOTE target с **другим** source-bound revision; разные manifests одного SHA
контроллер отвергает. Ему нужны digest-pinned runtime images и существующие
owner hooks. Ни first-REMOTE обход, ни ручные SQL fences не используются.
Существующие remote-contract CI gates остаются отдельными доказательствами.
Повтор бизнес-flow на настоящем four-REMOTE Dev после обычного deploy и browser
приёмка остаются обязательными отдельными шагами.

На этапе подготовки выполняются только offline/syntax проверки. Реальное
исполнение нового job и все бизнес/transport assertions должны быть приняты по
результатам следующего pipeline; локальная подготовка не является runtime PASS.

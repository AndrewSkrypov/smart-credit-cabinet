# Prod Kubernetes Secrets из Vault

Операторский адаптер для `cabinet-prod` в `alfa-cluster-2`. Kubernetes UID
`ca101720-31ee-4247-9786-a51ec69f62c7` зафиксирован в коде. При пересоздании
namespace сверьте новый UID через доверенный административный контекст и
обновите код отдельной проверяемой правкой. Потребители и существующий
GitLab deploy ServiceAccount остаются на прежнем контракте.

## Данные и права

В монтировании `pump` типа KV v2 каждой записи
`prod/kubernetes/<имя Secret>` соответствует один существующий объект.
Значения KV v2 совпадают с `.data` Kubernetes. Каждый ключ содержит
канонический base64 существующих байтов, поэтому перенос сохраняет UTF-8,
переводы строк и файлы SSH. Посторонние ключи и неполные наборы отклоняются.

- `cabinet-secrets` — `Opaque`, 11 ключей из [Helm-контракта](../../../helm/cabinet/README.md).
  При внешнем S3 ещё одновременно `S3_ACCESS_KEY` и `S3_SECRET_KEY`.
- `cabinet-s3-app` — `Opaque`, `S3_ACCESS_KEY`, `S3_SECRET_KEY`.
- `cabinet-s3-admin` — `Opaque`, `S3_ADMIN_ACCESS_KEY`, `S3_ADMIN_SECRET_KEY`.
- `cabinet-prod-registry` — `kubernetes.io/dockerconfigjson`,
  `.dockerconfigjson` с единственной записью `registry.credit-cabinet.ru`.
  До импорта владелец GitLab подтверждает, что токен выдан с `read_registry`.
- `engagement-ollama-ssh` — `Opaque`, `id_ed25519`, `known_hosts`.
  До импорта владелец H100 подтверждает назначение ключа и host pin.

TLS Secret ingress, учётные данные людей и жюри, Kubernetes kubeconfig,
ServiceAccount tokens, секреты других namespace и PostgreSQL read-only доступы
отдельных внешних операторов находятся вне этого переноса.

Оператор с согласованным доступом устанавливает [RBAC](rbac.yaml) и регистрирует
два Vault ACL через `vault policy write`. [Reader](read-policy.hcl) получает право
`read` на фиксированные пять data paths. [Seeder](seed-policy.hcl) получает
`read` и `create` на те же пути. Права `list`, `update`, `delete` и доступ к
метаданным KV отсутствуют. Kubernetes ServiceAccount
`cabinet-prod-vault-reader` читает пять именованных Secret и один namespace.
`cabinet-prod-vault-sync` дополнительно получает `patch` этих же Secret для
server dry-run и изменения. Kubernetes RBAC не различает dry-run и реальный
PATCH, поэтому выдавайте этот токен только на согласованное окно изменений.

Токены короткоживущие, раздельные между K8s и Vault, из источников с
установленными политиками. Для Vault начального оператора можно выдать
непродлеваемый токен через управляемую роль либо через
`vault token create -field=token -policy=<имя> -no-default-policy -renewable=false -ttl=15m -explicit-max-ttl=15m`.
Kubernetes оператор получает TokenRequest через
`kubectl --context "$PUMP_PROD_CONTEXT" -n cabinet-prod create token <ServiceAccount> --duration=15m`
с правильным audience API и проверяет срок. Сразу перенаправляйте stdout
команд выдачи в отдельные файлы 0600 в операторском каталоге 0700 под шифрованием
или в эквивалентное закрытое хранилище. Ни один токен не должен попадать в аргументы
процессов, историю, отчёты или CI artifacts. Для синхронизации в кластере нужен
отдельный согласованный Vault Kubernetes/JWT auth с привязкой к ServiceAccount,
namespace и audience, projected JWT для входа в Vault и отдельный токен с
audience Kubernetes API. Автоматической регистрации этого auth здесь нет.
Адаптер принимает закрытый обычный файл или один токен через stdin, которому
можно передать projected token процессом под доверенной идентичностью.

## Первичный перенос

Проведите после завершения Dev-проверок и в отсутствие другой операции над
выбранным Secret. Проверьте доступ к приватному Kubernetes API, точный Prod
namespace UID, TLS Vault и наличие всех выбранных объектов, отдельно от CD
release role. Администратор проверяет, что reader может читать каждый
заявленный Secret и получает отказ на patch, list и create. Аналогично
проверяется sync role с разрешённым patch, закрытые остальные Secret и
namespace, запрет чтения TLS и токенов. Аналогичные отрицательные запросы
к Vault проверяют только разрешённые операции и точные paths.

На доверенном runtime с Python 3.9+ запустите с флагами (показан internal S3):

```bash
sync_args=(
  --kube-address "$PUMP_PROD_API_HTTPS" --kube-ca "$PUMP_PROD_API_CA"
  --kube-token-file "$PUMP_PROD_KUBE_READER_TOKEN_FILE"
  --vault-address "$PUMP_VAULT_HTTPS" --vault-ca "$PUMP_VAULT_CA"
  --vault-token-file "$PUMP_VAULT_PROD_SEED_TOKEN_FILE"
)
for name in cabinet-secrets cabinet-s3-app cabinet-s3-admin cabinet-prod-registry engagement-ollama-ssh
do
  python3 deploy/vault/kubernetes/sync.py seed --secret "$name" "${sync_args[@]}" || break
done

sync_args=(
  --kube-address "$PUMP_PROD_API_HTTPS" --kube-ca "$PUMP_PROD_API_CA"
  --kube-token-file "$PUMP_PROD_KUBE_READER_TOKEN_FILE"
  --vault-address "$PUMP_VAULT_HTTPS" --vault-ca "$PUMP_VAULT_CA"
  --vault-token-file "$PUMP_VAULT_PROD_READ_TOKEN_FILE"
)
python3 deploy/vault/kubernetes/sync.py verify "${sync_args[@]}" \
  --secret cabinet-secrets --secret cabinet-s3-app --secret cabinet-s3-admin \
  --secret cabinet-prod-registry --secret engagement-ollama-ssh
```

Выбирайте только существующие компоненты. `seed` пишет в Vault через CAS=0
и проверяет побайтовую сверку после записи. Повтор с теми же данными
идемпотентен, существующая запись с другими данными блокирует перенос.
Строки статуса содержат только имя объекта, версию и исход проверки.
`verify` означает, что исходные Kubernetes Secret уже соответствуют Vault.
При этом rollout приложения и ротация не нужны. Для подтверждения
выполните штатные Prod smoke и проверку ролей, S3 и, если включены,
SSH bridge и private registry pull.

## Восстановление или контролируемое изменение

`stage` проверяет все выбранные версии и вызывает server dry-run для
расхождений. Реальный `apply` принимает ровно один Secret и требует
явные `--vault-version`, `--journal` и Kubernetes sync token. Операция
выполняется только после подготовки соответствующего backend и потребителей.
PostgreSQL, Valkey, S3 и взаимосвязанные ключи приложения требуют отдельного
согласованного плана ротации с проверкой readiness. Изменение Secret само
по себе не перезапускает Pods и не меняет пароль в PostgreSQL.

Укажите новый путь к журналу 0600 в закрытом операторском каталоге 0700
на шифрованном носителе. Он содержит оба набора данных, нужен для автономного
отката и сохраняется в защищённом хранилище на срок окна восстановления.
Адаптер требует отсутствие такого файла при изменении, синхронизирует его на
диск до PATCH и проверяет UID, тип и resourceVersion в одном запросе. При
неоднозначной ошибке делает повторное чтение перед выводом результата.
Не запускайте повторный apply при неизвестном результате. Проверьте тот же
объект и версию, сохраните журнал и проверьте сопутствующий rollout.

`rollback --secret <имя> --journal <путь>` требует только Kubernetes sync token
и Kubernetes CA. Он восстанавливает прежние данные, если нынешний объект и
его данные соответствуют записанному изменению. После отката перезапустите
соответствующие потребители в согласованном порядке и повторите их smoke.
Изменения сразу нескольких Secret требуют отдельной операции, поскольку
Kubernetes не предоставляет транзакции между объектами.

Локальная проверка на синтетических API и TLS:

```bash
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s deploy/vault/kubernetes -v
```

[Vault KV v2 API](https://developer.hashicorp.com/vault/api-docs/secret/kv/kv-v2) ·
[Kubernetes dry-run и resourceVersion](https://kubernetes.io/docs/reference/using-api/api-concepts/)

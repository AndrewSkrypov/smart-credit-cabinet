# Kubernetes-контур кабинета

В каждом окружении выделите собственный namespace. Имена Service внутри него
совпадают с именами сервисов приложения. Для доступа извне используйте
существующий LB и ingress controller. Chart не создаёт балансировщик или
сертификат. Допустим отдельный NodePort на gateway при прямом подключении
к существующему LB. При нескольких репликах gateway ограничители Nginx
считаются отдельно на каждом pod.

## Входные данные

- Новый kubeconfig и явный Kubernetes context, StorageClass с RWO-томами,
  доступный registry и namespaced pull Secret с правом `read_registry`.
- Версия PostgreSQL сохраняет совместимость с томом. Образы
  `images.{identity,credit,engagement,external-sim,frontend,postgres,swagger}`
  передаются из одного проверенного release manifest в виде `registry/path@sha256:…`.
  В `release.revision` передайте полный Git SHA этого манифеста.
- Создайте `secrets.existingSecret` вне Helm. Ключи `POSTGRES_PASSWORD`,
  `VALKEY_PASSWORD`, `JWT_SECRET`, `IDENTITY_LINK_KEY`, `SIM_API_KEY`,
  `IDENTITY_DB_PASSWORD`, `CREDIT_DB_PASSWORD`, `ENGAGEMENT_DB_PASSWORD`,
  `IDENTITY_MIGRATION_PASSWORD`, `CREDIT_MIGRATION_PASSWORD`,
  `ENGAGEMENT_MIGRATION_PASSWORD`. Внешний S3 также использует ключи
  `S3_ACCESS_KEY` и `S3_SECRET_KEY` из этого Secret.
  Пароли ролей БД и Valkey используйте в формате URL-safe без padding.
  Выдавайте разные секреты каждой роли. Выберите один из вариантов S3 ниже.
- `app.publicURL` — внешняя HTTPS-точка входа. `gateway.dnsResolver` — IPv4
  cluster DNS, доступный из pod. `gateway.clusterDomain` задаётся при
  отличии от `cluster.local`. Укажите реальные адреса ingress peers в
  `gateway.trustedProxyCIDRs` и проверьте на gateway, что контроллер
  перезаписывает входящие forwarded-заголовки. Лимиты считаются по IP
  после доверенного прокси. Укажите фактический namespace контроллера в
  `ingress.sourceNamespaces` и метки его Pod в `ingress.sourcePodLabels`.
  Для текущего Traefik это namespace `cabinet-prod` и метка
  `app.kubernetes.io/instance=cabinet-edge-cabinet-prod`. Проверьте, что CNI
  применяет оба селектора и gateway видит только согласованные адреса.
- Для внешнего TLS включите `ingress.enabled`, задайте `ingress.className`,
  `ingress.host`, `ingress.tlsSecret` и оставьте gateway ClusterIP. Edge
  настраивает HTTP→HTTPS, health check и передаёт `X-Forwarded-For`,
  `X-Forwarded-Proto`. На контроллере отключите буферизацию SSE, автоматические
  повторы запросов записи и задайте таймаут ответа для ассистента до 120 секунд.
  Для уже существующего ingress-nginx эти настройки передаются в
  `ingress.annotations`: `nginx.ingress.kubernetes.io/proxy-buffering: "off"`,
  `nginx.ingress.kubernetes.io/proxy-next-upstream: "off"`,
  `nginx.ingress.kubernetes.io/proxy-read-timeout: "120"`,
  `nginx.ingress.kubernetes.io/proxy-body-size: "20m"`.
- Доступ мониторинга на `8080` разрешается для namespaces из
  `monitoring.namespaces`. OTLP настраивается через `app.otlpEndpoint`.
  Публичный gateway скрывает внутренние сервисные маршруты и actuator.

## Хранение документов

Внутренний S3 проверяется перед каждым обновлением приложения отдельным
Helm Job. Он использует прикладной ключ для PUT/GET с побайтовой сверкой,
проверяет запреты и удаляет контрольный объект. Неуспешный Job останавливает
обновление до миграций.

**Встроенный SeaweedFS.** Включите `s3.internal.enabled=true` на первом
этапе установки инфраструктуры. Оставьте `s3.endpoint` пустым. Приложение
будет использовать `http://seaweedfs:8333` внутри выделенного namespace.
Укажите `s3.bucket`, `s3.region` и размер `s3.internal.storage`. Создайте вне
Helm два Secret с разными ключами доступа:

- `s3.internal.appSecret` (по умолчанию `cabinet-s3-app`) содержит
  `S3_ACCESS_KEY` и `S3_SECRET_KEY`. Даёт чтение и запись под `documents/`
  и просмотр списка объектов в одном bucket.
- `s3.internal.adminSecret` (по умолчанию `cabinet-s3-admin`) содержит
  `S3_ADMIN_ACCESS_KEY` и `S3_ADMIN_SECRET_KEY`. Даёт администрирование и
  доступ к объектам только в этом bucket. Доступ оператора к endpoint
  осуществляется через Kubernetes port-forward. Приложение получает
  только ключи из app Secret.

SeaweedFS Mini запускается в одной реплике на томе RWO. Он создаёт заданный
bucket при первом запуске и сохраняет его при перезапусках. У реплик
Credit один S3 endpoint. При остановке SeaweedFS документы временно
недоступны. Развёртывание не добавляет отказоустойчивость хранения.
NetworkPolicy допускает на S3-порт только Credit. Для ограничения
доступа к портам SeaweedFS требуется CNI с поддержкой NetworkPolicy.

После первого этапа выполните smoke из локального административного
окружения до обновления app. В одном терминале поднимите порт только на
loopback, а в другом передайте две переменные app Secret через
штатное хранилище секретов и запустите проверку:

```sh
kubectl --context "$CONTEXT" -n "$NAMESPACE" port-forward --address 127.0.0.1 svc/seaweedfs 18333:8333
```

```sh
helm/cabinet/check-s3.sh 18333 "$S3_BUCKET" "$S3_REGION"
```

Проверка записывает синтетический объект под `documents/`, сравнивает
байты после чтения, проверяет запрет анонимного чтения и записи за
пределами префикса, затем удаляет объект. Нужны curl с `--aws-sigv4`
и OpenSSL. Закройте port-forward после проверки.

**Внешний S3.** Оставьте `s3.internal.enabled=false`, задайте `s3.endpoint`,
`s3.region`, `s3.bucket` и передайте ключи приложения из
`secrets.existingSecret`. Заранее подготовьте закрытый bucket,
сервисный доступ к префиксу `documents/` и версионирование или
отдельный бэкап. Для переключения действующего контура сначала
приостановите запись и скопируйте объекты в новый backend с сохранением
ключей. После проверки количества и контрольных сумм выполните upgrade с
новой конфигурацией и проверьте скачивание документов. PVC
`data-seaweedfs-0` остаётся при отключении StatefulSet, сохраняя исходную
копию на время проверки.

## Старт ClamAV

InitContainer открывает каталог образа и PVC раздельно. Он заполняет
отсутствующие базы `main`, `bytecode` и `daily` из подписанных CVD.
Для уже заполненного PVC он выбирает `daily` по серийному номеру,
сохраняет более свежую базу и выводит старый CLD из рабочего
каталога после установки проверенного CVD.

При недоступности FreshClam используйте digest проверенного в CI образа
в `images.clamav`. Образ должен содержать CVD и соответствующие
`<имя>-<версия>.cvd.sign` в `/var/lib/clamav`, а также `sigtool`
и официальный сертификат в `/etc/clamav/certs`. InitContainer
проверяет detached-подпись перед публикацией файлов на PVC и не
обращается к сети. Основной контейнер продолжает проверять возраст
загруженной базы по правилу `PT160H`. Для проверки готовности
учитывайте дату сборки базы `daily`, а не дату образа.

## Двухэтапная установка и обновление

Сначала установите инфраструктуру и дождитесь готовности StatefulSets.
Этот шаг выполняется один раз. В командах `release.values.yaml` — файл
без секретов, собранный из release manifest и конфигурации контура.

```sh
helm lint helm/cabinet -f release.values.yaml
helm template cabinet helm/cabinet -n "$NAMESPACE" -f release.values.yaml \
  --set app.enabled=false |
  kubectl --context "$CONTEXT" -n "$NAMESPACE" apply --dry-run=server -f -
helm --kube-context "$CONTEXT" upgrade --install cabinet helm/cabinet \
  -n "$NAMESPACE" -f release.values.yaml --set app.enabled=false --wait --timeout 15m
helm template cabinet helm/cabinet -n "$NAMESPACE" --is-upgrade \
  -f release.values.yaml --set app.enabled=true |
  kubectl --context "$CONTEXT" -n "$NAMESPACE" apply --dry-run=server -f -
helm --kube-context "$CONTEXT" upgrade cabinet helm/cabinet -n "$NAMESPACE" \
  -f release.values.yaml --set app.enabled=true --wait --timeout 15m
```

Каждый app upgrade сначала выполняет два проверенных бэкапа, затем
`cabinet-db-init`, `cabinet-identity-flyway`, `cabinet-credit-flyway`,
`cabinet-engagement-flyway`. Успех всех заданий обязателен до обновления
Deployment. Db-init привязывает пароли и права от имени администратора.
Flyway запускается отдельными владельцами схем, приложения используют только
runtime-роли. Первый PostgreSQL-бэкап фиксирует ещё не созданные базы в
`not-yet-created.txt`. Обновления одного окружения сериализует CI.
До обновления убедитесь, что CronJob бэкапа закончил работу. Для долгой
операции временно приостановите CronJob. Повторите попытку после устранения
причины сбоя, jobs идемпотентны по отношению к уже применённым миграциям.

```sh
kubectl --context "$CONTEXT" -n "$NAMESPACE" exec deployment/gateway -- nginx -t
curl -fsS "$PUBLIC_URL/healthz"
python3 deploy/verify-jury.py --url "$PUBLIC_URL" --credentials "$PRIVATE_CREDENTIALS_FILE"
```

Для первой закрытой приёмки оператор создаёт отдельный файл 0600 в формате
того же smoke-теста. Корневые поля `CLIENT`, `AGENT`, `UNDERWRITER` содержат
`email` и `password`. Используйте разные пароли длиной 16–72 байта и разные
адреса `jury-…@demo.test`. Установите Python-пакет `bcrypt` в окружение
оператора и выполните один раз после успешного первого обновления:

```sh
python3 helm/cabinet/provision-jury.py --context "$CONTEXT" \
  --namespace "$NAMESPACE" --credentials "$PRIVATE_CREDENTIALS_FILE"
```

Операция переименовывает только три блокированные синтетические строки и
сохраняет их связи, включая мандат. На Kubernetes требуется только штатный
административный канал `pods/exec` с доступом к указанному pod PostgreSQL.
Проверяйте по отдельности реальный вход каждого пользователя, мандат агента,
запрет чужого доступа и соответствие образов манифесту. `healthz` проверяет
прохождение запроса через LB и gateway.

## Резервные копии и восстановление

Предрелизная и ежедневная копии PostgreSQL сохраняют по одному custom dump
на БД и описание глобальных ролей без паролей. Копия Valkey — проверенный
RDB. Обе процедуры проверяют архивы и сохраняют `SHA256SUMS` на отдельных
PVC `cabinet-postgres-backup` и `cabinet-valkey-backup`. Локальная ротация
задаётся `backup.retentionDays`. Периодически переносите архивы в закрытое
хранилище за пределами кластера, сохраняя контрольные суммы. Снимки трёх БД
создаются последовательно. Для согласованной точки восстановления нескольких
сервисов остановите запись в изолированном окне, снимите архивы БД,
Valkey и S3 и сохраните идентификаторы вместе.

1. Выберите конкретные архивы и версию S3 объектов. Поднимите отдельный
   namespace/PG16/Valkey с новыми томами, Secret и подходящим набором
   образов. Проверьте файлы на временном, закрытом для приложений носителе
   командой `sha256sum -c SHA256SUMS`.
2. Создайте роли и базы в чистом PostgreSQL тем же `bootstrap.sh` и
   актуальными секретами нового окружения. Проверьте состав
   `globals.sql`. Восстановите каждый dump в соответствующую пустую
   БД администратором командой `pg_restore --clean --if-exists --exit-on-error --dbname "$db" "$db.dump"`.
   Повторно примените bootstrap для восстановления точных runtime ACL.
3. Для Valkey запустите отдельный процесс на новом томе без клиентского
   трафика, с `dump.rdb` в `/data`, корректным владельцем файлов и
   `appendonly no`. После загрузки проверьте `INFO persistence` и `DBSIZE`.
   Включите AOF через `CONFIG SET appendonly yes`. Дождитесь успешного
   завершения AOF rewrite (`aof_rewrite_in_progress:0`,
   `aof_last_bgrewrite_status:ok` в `INFO persistence`) и остановите
   временный процесс перед подключением тома к StatefulSet. Valkey
   предпочитает AOF RDB-файлу при штатном старте.
4. Восстановите согласованный бакет, проверьте контрольные суммы и загрузку
   документов через приложение. Поднимите release, совместимый со схемой
   восстановленного набора, и выполните ролевую приёмку.
5. Переключайте трафик только после отдельной проверки и утверждения
   восстановления. Helm rollback возвращает образы и конфигурацию. Откат
   схемы и состояние S3/Valkey требуют отдельной процедуры. Если миграции
   несовместимы со старой версией, сначала восстановите согласованную копию.

`helm/cabinet/test-chart.sh` проверяет совпадение копий исходников Compose,
выполняет `helm lint` и рендерит оба этапа, включая варианты NodePort
и Ingress.

### Снимки SeaweedFS

Если CSI поддерживает `VolumeSnapshot`, используйте утверждённый
`VolumeSnapshotClass` с сохранением резервных копий и храните копию за
пределами одного тома. Согласуйте окно без приложений, пишущих в S3, и
остановите StatefulSet перед созданием снимка. После
`readyToUse: true` запустите SeaweedFS, проверьте S3 и возобновите
обработку трафика. Снимок и бэкапы PostgreSQL/Valkey включайте в одну
контрольную точку восстановления.

Источник снимка — PVC `data-seaweedfs-0` в namespace релиза. Для репетиции
восстановления CSI-оператор должен предоставить снимок в изолированном
namespace. До включения StatefulSet создайте в нём PVC
`data-seaweedfs-0` с тем же StorageClass, размером не меньше исходного,
`accessModes: [ReadWriteOnce]`, и `dataSource`, который ссылается на этот
`VolumeSnapshot` в группе `snapshot.storage.k8s.io`. После подключения
PVC проверьте закрытый доступ и чтение известных объектов перед
переключением трафика. При отсутствии CSI-снимков сохраняйте согласованную
копию объектов закрытым S3-клиентом на другой носитель и проверяйте
восстановление в отдельный bucket.

## Выделенный SSH-транспорт Ollama

Стандартный режим Dev, жюри и Prod использует правила при пустом
`OLLAMA_URL`. В `cabinet-prod` можно включить `ollamaBridge.enabled=true`
после проверки маршрута с узлов и подов. Передайте проверенные IPv4 и
TCP-порт SSH в `ollamaBridge.host` и `ollamaBridge.port`. Значение
`images.ssh-bridge` — опубликованный digest образа с OpenSSH, POSIX
`sh`, `cp`, `chmod`, `sleep` и записью пользователя в passwd для
UID/GID 10002.

Подготовьте отдельный Secret `ollamaBridge.secretName` с файлами
`id_ed25519` и `known_hosts` вне Helm. В `known_hosts` закрепляется
подтверждённый ED25519-ключ хоста под именем `gpu-vm3-pump-model`.
Зарегистрируйте у GPU-оператора публичный ключ учётной записи
`pump-model-forward`. Secret монтируется только в sidecar, который
копирует файл ключа в собственный memory volume с режимом `0600`.
Для применения обновлённого Secret выполните согласованный rollout
Engagement. Закрытый ключ остаётся в контуре приложения.

Sidecar слушает `127.0.0.1:11434` внутри pod Engagement. При потере
SSH он переподключается и не привязывает readiness приложения к GPU.
Включение транспорта задаёт точный тег `ollamaBridge.model` и
`ollamaBridge.timeout`; прогрев включается отдельно через
`ollamaBridge.warmup` после приёмки. Kubernetes Service для туннеля
отсутствует. Политика исходящего трафика разрешает проверенный SSH
IPv4/порт, DNS, PostgreSQL, Valkey, Identity и Credit. Prometheus
продолжает снимать метрики по существующей политике входящего трафика.
Для node-local DNS укажите в `ollamaBridge.dns.address` реальный
resolver pod. При включённом `app.otlpEndpoint` укажите namespace,
метки pod и TCP-порт приёмника в `ollamaBridge.otlp`.
Проверьте обе стороны доступа в используемом CNI после rollout.

После переключения проверьте свежий ответ `MODEL` и дельту
`cabinet.assistant.model{outcome="ACCEPTED"}` через штатный HTTPS.
На изолированном контуре прервите выделенный транспорт и убедитесь,
что Engagement остаётся Ready, а свежий справочный запрос возвращает
`RULES` с приростом `UNAVAILABLE`. Повторите запрос после
восстановления туннеля и 30-секундного отрицательного кэша.
Отключение `ollamaBridge.enabled` возвращает правила при следующем
обновлении релиза.

## Публичное демо под `/demo/`

Для `cabinet-prod` с `app.publicURL=https://credit-cabinet.ru` задайте
`publicDemo.enabled=true` и проверенный IPv4 внешнего демо-входа в
`publicDemo.upstreamIPv4`. Прокси принимает трафик только от gateway и
подключается только к указанному IP на порту 443, проверяя публичный
сертификат для `demo.dev.credit-cabinet.ru`. Он обслуживает только
`/demo/__ready` и `/demo/api/v1/`; HTML и статические ресурсы отдаёт
обычный frontend. Демо-учётки по-прежнему выключены в основной БД.

После выкладки проверьте четыре входа из обычной страницы входа, затем
проверьте, что открытие демо не меняет основную сессию и что демо-токен
отклоняется обычным API. Для отключения при следующем обновлении задайте
`publicDemo.enabled=false`.

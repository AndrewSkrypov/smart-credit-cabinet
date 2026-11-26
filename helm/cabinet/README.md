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
  `ENGAGEMENT_MIGRATION_PASSWORD`, `S3_ACCESS_KEY`, `S3_SECRET_KEY`.
  Пароли ролей БД и Valkey используйте в формате URL-safe без padding.
  Выдавайте разные секреты каждой роли. Учетной записи S3 нужен доступ к
  заранее подготовленному bucket, задаваемому `s3.endpoint`, `s3.region`
  и `s3.bucket`. Для S3 задайте версионирование или отдельный бэкап объектов.
- `app.publicURL` — внешняя HTTPS-точка входа. `gateway.dnsResolver` — IPv4
  cluster DNS, доступный из pod. `gateway.clusterDomain` задаётся при
  отличии от `cluster.local`. Укажите реальные адреса ingress peers в
  `gateway.trustedProxyCIDRs` и проверьте на gateway, что контроллер
  перезаписывает входящие forwarded-заголовки. Лимиты считаются по IP
  после доверенного прокси. Используйте отдельный namespace контроллера
  в `ingress.sourceNamespaces`. Для hostNetwork или SNAT добавьте
  подтверждённые источники в `ingress.sourceCIDRs` и проверьте CNI.
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

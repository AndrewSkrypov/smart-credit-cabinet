# Оповещения и дежурства

## Локальная проверка

Запустите `python3 monitoring/alerts/smoke.py` из корня репозитория. Нужен Docker Compose.
Проверка поднимает отдельную сеть и временные Prometheus, Alertmanager, GoAlert и HTTP fixture.
Порты публикуются на loopback, учётные записи демонстрационные, данные сбрасываются после теста.
Сценарий проверяет `Up → pending → firing`, доставку локального webhook в GoAlert,
`Acknowledge`, переход Prometheus в inactive и закрытие того же инцидента через resolved webhook.
Сценарий проверяет основного дежурного в активной ротации и переход
неподтверждённого инцидента к резервному через одну минуту. Исходящих сообщений нет.

## Production

Alertmanager группирует Telegram по окружению, имени правила и сервису. Критические
оповещения prod дублируются в GoAlert. Телеграм получает critical и warning.
Dev не попадает в production-получатели. Секрет `cabinet-paging` в namespace
мониторинга содержит `telegram-token`, `telegram-chat-id`, `goalert-url` и монтируется
в Alertmanager. Подключайте маршруты после согласования канала Telegram и состава смены.
Проверяйте маршрутизацию с `amtool config routes test` до включения исходящих уведомлений.

GoAlert v0.35.0 можно включить через `helm/observability/goalert-values.yaml`. Выделите
собственную БД PostgreSQL и роль с правом миграций в ней. Используйте проверяемое TLS
соединение, если БД вынесена из доверенной сети, и постоянный ключ шифрования.
Секрет `cabinet-goalert` содержит `db-url` и `data-encryption-key`. При переносе
сохраните ключ вместе с восстановимой резервной копией БД. GoAlert слушает только
внутренний Service, без Ingress. Администратор подключается через
`kubectl -n monitoring port-forward svc/cabinet-goalert 8081:8081` с разрешением RBAC
на port-forward. Ссылка для такого способа доступа — `http://localhost:8081`.

Создайте первую учётную запись администратора через CLI внутри GoAlert.
Подставляйте подтверждённые имя и email оператора. CLI прочитает настройки БД из
переменных окружения контейнера и запросит пароль интерактивно.

```bash
kubectl -n monitoring exec -it deployment/cabinet-goalert -- \
  /usr/bin/goalert add-user --admin --user cabinet-operator --email OPERATOR_EMAIL
```

1. Создайте Schedule с ротацией основного и резервного дежурных, часовой пояс
   `Europe/Moscow`. Проверьте наличие дежурного в каждом интервале.
2. Привяжите Schedule к первой ступени Escalation Policy. Первая ступень уведомляет
   текущего дежурного сразу. Если подтверждения нет 10 минут, вторая ступень уведомляет
   резервного дежурного. Ещё через 15 минут третья ступень уведомляет владельца сервиса.
   Согласуйте контактные точки и режим повтора с командой.
3. Укажите политику в сервисе GoAlert и выпустите ключ типа Prometheus Alertmanager.
   Для webhook из кластера замените origin URL на `http://cabinet-goalert:8081`,
   сохранив путь и параметры. Полный URL занесите в `cabinet-paging/goalert-url`.
4. Настройте согласованный канал доставки дежурному (например, корпоративный SMTP
   или внутренний webhook). Ротация и эскалация функционируют без платного сервиса.
   Голосовая доставка требует провайдера Twilio и остаётся выключенной без отдельного
   согласования.
5. На тестовом инциденте проверьте, что `Acknowledge` останавливает вызов следующего
   дежурного. Доведите исходное условие до нормы и убедитесь, что пришёл resolved и
   закрылась эта же карточка. Для зависшей карточки сверяйте источник перед ручным Close.
   Отдельно оставьте тестовый инцидент без ack до второй ступени и проверьте статус
   доставки оператору в истории GoAlert.

## Документация

[Нативный webhook GoAlert](https://goalert.me/docs/alerting/alerting-from-prometheus/),
[эксплуатация GoAlert](https://github.com/target/goalert/blob/v0.35.0/docs/getting-started.md),
[Alertmanager](https://prometheus.io/docs/alerting/0.33/configuration/).

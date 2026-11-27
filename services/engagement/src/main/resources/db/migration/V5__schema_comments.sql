comment on table delivery_outbox is 'Очередь доставки почтовых копий уведомлений';
comment on column delivery_outbox.event_key is 'Идемпотентный ключ доставки';
comment on column delivery_outbox.state is 'Состояние очереди';
comment on column delivery_outbox.next_attempt_at is 'Ближайшая дата повтора';
comment on column delivery_outbox.trace_parent is 'Сохранённый родительский контекст W3C';

comment on table notification_preferences is 'Версионированные пользовательские настройки почты';
comment on column notification_preferences.user_id is 'Владелец настроек';
comment on column notification_preferences.email_enabled is 'Разрешение дублировать важные события на почту';
comment on column notification_preferences.version is 'Версия оптимистической блокировки';

comment on table notification_receipts is 'Принятые события с дедупликацией при повторах';
comment on column notification_receipts.event_key is 'Уникальный ключ принятого от отправителя события';
comment on column notification_receipts.payload is 'Исходные параметры уже обработанного события';

comment on table notifications is 'Личные уведомления кабинета';
comment on column notifications.recipient_id is 'Пользователь, которому доступно уведомление';
comment on column notifications.type is 'Сценарный тип уведомления';
comment on column notifications.link is 'Путь к экрану внутри кабинета';
comment on column notifications.dedup_key is 'Ключ дедупликации доставки пользователю';
comment on column notifications.read_at is 'Дата отметки о прочтении';

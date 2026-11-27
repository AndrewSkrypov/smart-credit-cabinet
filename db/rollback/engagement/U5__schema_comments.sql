comment on table delivery_outbox is null;
comment on column delivery_outbox.event_key is null;
comment on column delivery_outbox.state is null;
comment on column delivery_outbox.next_attempt_at is null;
comment on column delivery_outbox.trace_parent is null;

comment on table notification_preferences is null;
comment on column notification_preferences.user_id is null;
comment on column notification_preferences.email_enabled is null;
comment on column notification_preferences.version is null;

comment on table notification_receipts is null;
comment on column notification_receipts.event_key is null;
comment on column notification_receipts.payload is null;

comment on table notifications is null;
comment on column notifications.recipient_id is null;
comment on column notifications.type is null;
comment on column notifications.link is null;
comment on column notifications.dedup_key is null;
comment on column notifications.read_at is null;

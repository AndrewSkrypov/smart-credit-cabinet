-- Notifications under the bell in the header. credit writes here through
-- /internal/v1/notifications, addressed to a person, to everyone of a company or to a bank role;
-- the address is turned into one row per person on delivery, so each reads at their own pace.
create table notifications (
    id uuid primary key,
    recipient_id uuid not null,
    -- APPLICATION_STATUS, ACTION_REQUIRED, NEW_REVIEW
    type varchar(32) not null,
    title varchar(255) not null,
    body varchar(1000) not null,
    -- A path inside the cabinet ("/client/applications/…"), never an outside address.
    link varchar(512),
    -- The same event delivered twice is stored once per person.
    dedup_key varchar(255),
    read_at timestamptz,
    created_at timestamptz not null default now()
);

create unique index ux_notifications_dedup on notifications(recipient_id, dedup_key) where dedup_key is not null;
create index idx_notifications_recipient_created on notifications(recipient_id, created_at desc, id);
create index idx_notifications_unread on notifications(recipient_id) where read_at is null;

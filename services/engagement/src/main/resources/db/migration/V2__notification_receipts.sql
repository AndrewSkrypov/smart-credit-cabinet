-- The event receipt and its recipient fan-out commit together; replay never expands the audience.
create table notification_receipts (
    event_key varchar(255) primary key,
    payload jsonb not null,
    received_at timestamptz not null default now()
);

create function notification_receipts_append_only() returns trigger language plpgsql as $$
begin
    raise exception 'notification_receipts is append-only';
end
$$;

create trigger notification_receipts_no_change before update or delete on notification_receipts
    for each row execute function notification_receipts_append_only();

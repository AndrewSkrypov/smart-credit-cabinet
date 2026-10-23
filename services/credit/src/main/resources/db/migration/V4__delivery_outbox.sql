create table delivery_outbox (
    id uuid primary key,
    channel varchar(32) not null,
    event_key varchar(255) not null,
    payload jsonb not null,
    state varchar(16) not null default 'PENDING' check (state in ('PENDING', 'RUNNING', 'DELIVERED', 'FAILED')),
    attempts integer not null default 0 check (attempts >= 0),
    next_attempt_at timestamptz not null default now(),
    claim_token uuid,
    locked_until timestamptz,
    last_error_code varchar(120),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    delivered_at timestamptz,
    unique (channel, event_key),
    check ((state='RUNNING') = (claim_token is not null and locked_until is not null))
);

create index idx_delivery_outbox_due on delivery_outbox(next_attempt_at) where state='PENDING';
create index idx_delivery_outbox_lease on delivery_outbox(locked_until) where state='RUNNING';

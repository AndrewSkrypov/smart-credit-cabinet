create table assessment_execution (
    stream varchar(65) not null,
    cycle_id uuid not null,
    application_id uuid not null,
    request jsonb not null,
    progress jsonb,
    result jsonb,
    attempts integer not null default 0 check (attempts >= 0),
    available_at timestamptz not null default now(),
    locked_until timestamptz,
    claim_token uuid,
    last_error varchar(120),
    completed_at timestamptz,
    primary key (stream, cycle_id),
    check ((locked_until is null) = (claim_token is null)),
    check ((result is null) = (completed_at is null))
);
create index assessment_execution_due on assessment_execution (stream, available_at) where completed_at is null;

create table assessment_exchange_outbox (
    stream varchar(65) not null,
    cycle_id uuid not null,
    application_id uuid not null,
    payload jsonb not null,
    attempts integer not null default 0 check (attempts >= 0),
    available_at timestamptz not null default now(),
    sent_at timestamptz,
    claim_token uuid,
    locked_until timestamptz,
    last_error varchar(120),
    primary key (stream, cycle_id),
    check ((claim_token is null) = (locked_until is null))
);
create index assessment_exchange_outbox_due on assessment_exchange_outbox (stream, available_at) where sent_at is null;

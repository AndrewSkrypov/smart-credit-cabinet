create table credit_assessment_cycle (
    cycle_id uuid primary key references pipeline_jobs(id),
    application_id uuid not null references applications(id),
    application_version bigint not null check (application_version >= 0),
    stream varchar(65),
    request jsonb,
    result jsonb,
    accept_until timestamptz,
    received_at timestamptz,
    score_version bigint,
    closed_at timestamptz,
    check ((stream is null) = (request is null)),
    check ((request is null) = (accept_until is null)),
    check ((result is null) = (received_at is null)),
    check (score_version is null or result is not null)
);
create unique index credit_assessment_open_score on credit_assessment_cycle(application_id)
    where score_version is not null and closed_at is null;

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

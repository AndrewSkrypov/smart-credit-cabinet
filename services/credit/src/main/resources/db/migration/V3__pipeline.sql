-- The credit pipeline: what the sources said, how the risk was scored, what was decided and
-- offered, how the contract was signed and the money disbursed (docs/process-model.md).

-- Steps of the pipeline follow each other within milliseconds: the journal keeps their order
-- by a sequence, not by timestamps that can be equal.
alter table application_events add column seq bigint generated always as identity;

-- A worker takes a job by moving it to RUNNING with a lease; a job whose worker died is taken
-- again once the lease is over.
alter table pipeline_jobs add column locked_until timestamptz;

-- One pending or running job per application and step: enqueueing twice is harmless.
create unique index uq_pipeline_jobs_open on pipeline_jobs(application_id, step) where status in ('PENDING', 'RUNNING');

-- Versioned thresholds of the credit policy. A decision records the version it was made with,
-- so changing the rules never changes decisions already made.
create table rule_sets (
    version integer primary key,
    params jsonb not null,
    created_at timestamptz not null default now(),
    created_by uuid,
    comment varchar(1000)
);

insert into rule_sets (version, params, comment) values (1, '{
  "maxLoad": 0.35,
  "revenueMismatch": 0.30,
  "ratingCutoffs": {"A": 80, "B": 65, "C": 50, "D": 35},
  "ratingMargins": {"A": 0, "B": 1.5, "C": 3, "D": 5},
  "lowRiskOkved": ["01", "02", "03", "10", "11", "13", "14", "15", "16", "17", "18", "20", "22", "23", "25", "26", "27", "28", "29", "31", "32", "33", "62", "63", "72", "85", "86"],
  "highRiskOkved": ["41", "42", "43", "64", "66", "68", "92"],
  "weakAmountShare": 0.5,
  "weakRevenueShare": 0.15
}', 'Первая редакция по docs/process-model.md');

-- Snapshot of each source for an application: the answer as it was when the bank asked.
create table enrichment_results (
    application_id uuid not null references applications(id),
    source varchar(16) not null,
    payload jsonb not null,
    fetched_at timestamptz not null,
    primary key (application_id, source)
);

create table decisions (
    id uuid primary key,
    -- Order of decisions made within the same instant (a request for information and the
    -- automatic assessment can share a timestamp).
    seq bigint generated always as identity,
    application_id uuid not null references applications(id),
    rule_set_version integer not null references rule_sets(version),
    -- SYSTEM for the automatic assessment, UNDERWRITER for a manual decision.
    decided_by_type varchar(16) not null,
    decided_by uuid,
    score integer,
    rating varchar(1),
    stop_factors varchar(32)[] not null default '{}',
    factors jsonb not null default '[]',
    -- APPROVE, COUNTER_OFFER, MANUAL_REVIEW, REJECT, REQUEST_INFO.
    outcome varchar(16) not null,
    reason_code varchar(32),
    manual_reasons varchar(32)[] not null default '{}',
    -- What the rules propose: terms of an offer, or a refusal with its reason.
    recommendation jsonb,
    -- Everything the rules read, so the decision can be reproduced.
    inputs jsonb not null,
    comment varchar(2000),
    created_at timestamptz not null
);

create index idx_decisions_application on decisions(application_id, seq desc);

create table info_requests (
    id uuid primary key,
    application_id uuid not null references applications(id),
    items jsonb not null,
    comment varchar(2000),
    deadline timestamptz not null,
    requested_by_type varchar(16) not null,
    requested_by uuid,
    created_at timestamptz not null,
    resolved_at timestamptz,
    response_comment varchar(2000)
);

create index idx_info_requests_open on info_requests(application_id) where resolved_at is null;

create table offers (
    id uuid primary key,
    application_id uuid not null references applications(id),
    decision_id uuid not null references decisions(id),
    -- APPROVED: the requested terms; COUNTER: different amount, term or product.
    kind varchar(16) not null,
    product_code varchar(32) not null references products(code),
    amount bigint not null,
    term_months integer not null,
    rate numeric(5, 2) not null,
    monthly_payment numeric(14, 2) not null,
    total_payment numeric(14, 2) not null,
    valid_until timestamptz not null,
    -- ISSUED, ACCEPTED, DECLINED, EXPIRED, WITHDRAWN.
    status varchar(16) not null,
    created_at timestamptz not null,
    decided_at timestamptz
);

create index idx_offers_application on offers(application_id, created_at desc);

create table signing_sessions (
    id uuid primary key,
    application_id uuid not null references applications(id),
    offer_id uuid not null references offers(id),
    external_session_id uuid not null,
    -- The one-time code is shown in the cabinet instead of an SMS (SMS is outside the scenario).
    code varchar(6) not null,
    expires_at timestamptz not null,
    signature_id uuid,
    signed_at timestamptz,
    signed_by uuid,
    created_at timestamptz not null
);

create index idx_signing_sessions_application on signing_sessions(application_id, created_at desc);

create table loans (
    application_id uuid primary key references applications(id),
    contract_number varchar(32) not null unique,
    loan_account varchar(20) not null,
    settlement_account varchar(20) not null,
    amount bigint not null,
    term_months integer not null,
    rate numeric(5, 2) not null,
    revolving boolean not null,
    disbursed_at timestamptz not null,
    maturity_date date not null
);

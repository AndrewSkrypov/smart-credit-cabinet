-- Credit products, applications and everything that happens to them. The state machine and
-- its invariants are described in docs/state-machine.md.

create table products (
    code varchar(32) primary key,
    name varchar(255) not null,
    description text not null,
    -- FinancingPurpose names the product serves, e.g. {"EQUIPMENT","VEHICLE"}.
    purposes varchar(32)[] not null,
    min_amount bigint not null,
    max_amount bigint not null,
    min_term_months integer not null,
    max_term_months integer not null,
    -- Annual rate in percent before the rating margin.
    base_rate numeric(5, 2) not null,
    -- Above this amount an application always goes to an underwriter.
    auto_approval_limit bigint not null,
    -- A limit that is used and repaid again (overdraft, card) rather than a one-off loan.
    revolving boolean not null,
    display_order integer not null,
    constraint products_amounts check (min_amount > 0 and min_amount <= max_amount),
    constraint products_terms check (min_term_months > 0 and min_term_months <= max_term_months)
);

create sequence application_number_seq start 1001;

create table applications (
    id uuid primary key,
    -- Short number people quote on the phone and in the interface.
    number bigint not null unique default nextval('application_number_seq'),
    company_id uuid not null,
    -- Snapshot of the company at creation (identity owns the company itself).
    company_inn varchar(12) not null,
    company_name varchar(500),
    legal_form varchar(32) not null,
    created_by uuid not null,
    product_code varchar(32) not null references products(code),
    purpose varchar(32) not null,
    purpose_comment varchar(1000),
    amount bigint not null,
    term_months integer not null,
    -- What the client says about the business; checked against the registries later.
    declared_revenue_12m bigint,
    declared_monthly_debt_payments bigint,
    bureau_consent boolean not null default false,
    status varchar(32) not null,
    close_reason varchar(32),
    created_at timestamptz not null,
    updated_at timestamptz not null,
    submitted_at timestamptz,
    status_changed_at timestamptz not null,
    -- Optimistic lock: two actions at the same moment cannot both win.
    version bigint not null default 0
);

create index idx_applications_company on applications(company_id, created_at desc);
create index idx_applications_status on applications(status, status_changed_at);

-- One application in progress per company and product (T1 in docs/state-machine.md).
create unique index uq_applications_active_product on applications(company_id, product_code)
    where status not in ('DISBURSED', 'REJECTED', 'WITHDRAWN', 'CLOSED');

-- Journal of an application: every transition and every change that matters, never edited.
-- The client's history, the bank's audit and the funnel are all built from it.
create table application_events (
    id uuid primary key,
    application_id uuid not null references applications(id),
    event varchar(48) not null,
    from_status varchar(32),
    to_status varchar(32) not null,
    actor_type varchar(32) not null,
    actor_id uuid,
    reason_code varchar(48),
    comment varchar(2000),
    payload jsonb not null default '{}',
    occurred_at timestamptz not null
);

create index idx_application_events_application on application_events(application_id, occurred_at);
create index idx_application_events_funnel on application_events(to_status, occurred_at);

create function application_events_append_only() returns trigger language plpgsql as $$
begin
    raise exception 'application_events is append-only';
end
$$;

create trigger application_events_no_update
    before update or delete on application_events
    for each row execute function application_events_append_only();

-- Steps of the credit pipeline written in the same transaction as the transition that needs
-- them (transactional outbox); a worker takes them with FOR UPDATE SKIP LOCKED.
create table pipeline_jobs (
    id uuid primary key,
    application_id uuid not null references applications(id),
    step varchar(32) not null,
    status varchar(16) not null default 'PENDING',
    attempts integer not null default 0,
    run_at timestamptz not null,
    last_error varchar(2000),
    created_at timestamptz not null,
    updated_at timestamptz not null
);

create index idx_pipeline_jobs_due on pipeline_jobs(run_at) where status = 'PENDING';

-- A repeated request with the same Idempotency-Key returns the first result instead of a
-- second transition.
create table idempotency_keys (
    user_id uuid not null,
    idempotency_key varchar(128) not null,
    operation varchar(32) not null,
    application_id uuid not null references applications(id),
    created_at timestamptz not null default now(),
    primary key (user_id, idempotency_key)
);

create table documents (
    id uuid primary key,
    application_id uuid not null references applications(id),
    type varchar(32) not null,
    file_name varchar(255) not null,
    -- Random name in the store; never shown, never accepted from a request.
    stored_name varchar(64) not null unique,
    size_bytes bigint not null,
    uploaded_by uuid not null,
    uploaded_at timestamptz not null,
    deleted_at timestamptz
);

create index idx_documents_application on documents(application_id) where deleted_at is null;

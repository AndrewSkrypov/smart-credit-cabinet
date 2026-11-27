-- Post-disbursement loan servicing: loan accounts, revolving tranches, and repayment schedules.

create table loan_accounts (
    id uuid primary key,
    application_id uuid not null unique,
    company_id uuid not null,
    contract_number varchar(32) not null,
    approved_limit bigint not null check (approved_limit > 0),
    disbursed_amount bigint not null check (disbursed_amount >= 0 and disbursed_amount <= approved_limit),
    outstanding_principal numeric(14, 2) not null check (outstanding_principal >= 0),
    rate numeric(5, 2) not null check (rate >= 0),
    term_months integer not null check (term_months > 0),
    revolving boolean not null,
    status varchar(16) not null default 'ACTIVE'
        check (status in ('ACTIVE', 'REPAID')),
    disbursed_at timestamptz not null,
    maturity_date date not null,
    repaid_at timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create index idx_loan_accounts_company
    on loan_accounts(company_id, created_at desc);

create table loan_tranches (
    id uuid primary key,
    loan_account_id uuid not null references loan_accounts(id) on delete cascade,
    tranche_number integer not null check (tranche_number > 0),
    amount bigint not null check (amount > 0),
    purpose varchar(500),
    disbursed_at timestamptz not null,
    unique (loan_account_id, tranche_number)
);

create index idx_loan_tranches_loan
    on loan_tranches(loan_account_id, tranche_number);

create table loan_payments (
    id uuid primary key,
    loan_account_id uuid not null references loan_accounts(id) on delete cascade,
    payment_number integer not null check (payment_number > 0),
    due_date date not null,
    payment_amount numeric(14, 2) not null check (payment_amount >= 0),
    principal_amount numeric(14, 2) not null check (principal_amount >= 0),
    interest_amount numeric(14, 2) not null check (interest_amount >= 0),
    remaining_balance numeric(14, 2) not null check (remaining_balance >= 0),
    status varchar(16) not null default 'SCHEDULED'
        check (status in ('SCHEDULED', 'PAID')),
    paid_at timestamptz,
    unique (loan_account_id, payment_number)
);

create index idx_loan_payments_loan
    on loan_payments(loan_account_id, payment_number);

create table payment_reserves (
    loan_id uuid primary key references loan_accounts(id) on delete cascade,
    version bigint not null check (version > 0),
    opening_balance bigint not null check (opening_balance >= 0),
    expected_receipt_amount bigint not null check (expected_receipt_amount > 0),
    payable_amount bigint not null check (payable_amount > 0),
    target_buffer bigint not null check (target_buffer >= 0),
    control_date date not null,
    payment_date date not null,
    receipt_status varchar(16) not null check (receipt_status in ('PENDING', 'RECEIVED', 'DELAYED')),
    received_amount bigint not null check (received_amount >= 0 and received_amount <= expected_receipt_amount),
    confirmed_version bigint,
    drawn_tranche_id uuid references loan_tranches(id),
    drawn_amount bigint,
    confirmed_by uuid,
    confirmed_at timestamptz,
    updated_by uuid not null,
    updated_at timestamptz not null default now(),
    check (control_date <= payment_date),
    check (
        (confirmed_version is null and drawn_tranche_id is null and drawn_amount is null and confirmed_by is null and confirmed_at is null)
        or (
            confirmed_version is not null and confirmed_version > 0 and confirmed_version < version
            and drawn_tranche_id is not null and drawn_amount is not null and drawn_amount > 0
            and confirmed_by is not null and confirmed_at is not null
        )
    )
);

create table payment_reserve_receipt_events (
    loan_id uuid not null references payment_reserves(loan_id) on delete cascade,
    event_id varchar(128) not null,
    receipt_status varchar(16) not null check (receipt_status in ('RECEIVED', 'DELAYED')),
    reported_amount bigint check (reported_amount >= 0),
    plan_version bigint not null check (plan_version > 0),
    actor_id uuid not null,
    created_at timestamptz not null default now(),
    primary key (loan_id, event_id)
);

comment on table payment_reserves is
    'Синтетический план резерва к платежу по действующей возобновляемой линии и подтверждённый транш';
comment on table payment_reserve_receipt_events is
    'Неизменяемые отметки синтетических поступлений для аудита и повторов запроса';

create table servicing_opened_commands (
    application_id uuid primary key,
    command_id uuid not null unique,
    cycle_id uuid not null,
    account_id uuid not null unique references loan_accounts(id) on delete cascade,
    payload_sha256 char(64) not null,
    command_json jsonb not null,
    receipt_json jsonb not null,
    recorded_at timestamptz not null default now()
);

create table servicing_writer_fence (
    singleton boolean primary key default true check (singleton),
    writer varchar(8) not null check (writer in ('LOCAL','REMOTE','FENCED')),
    epoch bigint not null default 1 check (epoch > 0),
    manifest_sha256 char(64),
    changed_at timestamptz not null default now()
);
insert into servicing_writer_fence(singleton,writer) values(true,'FENCED');
-- FOR SHARE requires UPDATE privilege. Keep locking inside a narrow owner function;
-- runtime may read/check the fence but cannot change it or disable table triggers.
create function assert_servicing_writer(expected_writer text) returns boolean language plpgsql
security definer set search_path=pg_catalog as $$
declare active_writer text;
begin
    select writer into active_writer from public.servicing_writer_fence where singleton=true for share;
    if active_writer is distinct from expected_writer or expected_writer not in ('LOCAL','REMOTE') then
        raise exception 'Servicing writer is fenced' using errcode='55000';
    end if;
    return true;
end $$;
create function enforce_servicing_writer() returns trigger language plpgsql as $$
begin
    perform public.assert_servicing_writer('REMOTE');
    if tg_op='DELETE' then return old; end if;
    return new;
end $$;
create trigger loan_accounts_writer_fence before insert or update or delete on loan_accounts for each row execute function enforce_servicing_writer();
create trigger loan_tranches_writer_fence before insert or update or delete on loan_tranches for each row execute function enforce_servicing_writer();
create trigger loan_payments_writer_fence before insert or update or delete on loan_payments for each row execute function enforce_servicing_writer();
create trigger payment_reserves_writer_fence before insert or update or delete on payment_reserves for each row execute function enforce_servicing_writer();
create trigger payment_reserve_receipt_events_writer_fence before insert or update or delete on payment_reserve_receipt_events for each row execute function enforce_servicing_writer();
create trigger servicing_opened_commands_writer_fence before insert or update or delete on servicing_opened_commands for each row execute function enforce_servicing_writer();

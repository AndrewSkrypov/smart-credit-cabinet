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

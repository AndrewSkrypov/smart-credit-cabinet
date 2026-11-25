-- Post-disbursement loan servicing: loan accounts, revolving tranches, and repayment schedules.

create table loan_accounts (
    id uuid primary key,
    application_id uuid not null unique references applications(id),
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

-- Agent channel columns on applications and pre-approved offers for existing bank clients.

create table pre_approved_offers (
    id uuid primary key,
    company_id uuid not null,
    product_code varchar(32) not null references products(code),
    max_amount bigint not null check (max_amount > 0),
    min_term_months integer not null check (min_term_months > 0),
    max_term_months integer not null check (max_term_months >= min_term_months),
    rate numeric(5, 2) not null check (rate > 0),
    valid_until timestamptz not null,
    status varchar(16) not null default 'ACTIVE'
        check (status in ('ACTIVE', 'USED', 'EXPIRED')),
    application_id uuid references applications(id),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create index idx_pre_approved_offers_company
    on pre_approved_offers(company_id, status, valid_until);

alter table applications
    add column agent_id uuid,
    add column agent_name varchar(255),
    add column pre_approved_offer_id uuid references pre_approved_offers(id),
    add column agent_commission_rate_bps integer
        check (agent_commission_rate_bps is null or agent_commission_rate_bps between 0 and 1000),
    add column agent_commission_amount bigint
        check (agent_commission_amount is null or agent_commission_amount >= 0),
    add column agent_commission_status varchar(16) not null default 'NONE'
        check (agent_commission_status in ('NONE', 'EXPECTED', 'ACCRUED', 'CANCELLED'));

create index idx_applications_agent
    on applications(agent_id, created_at desc)
    where agent_id is not null;

create index idx_applications_pre_approved
    on applications(pre_approved_offer_id)
    where pre_approved_offer_id is not null;

-- Demo seeds for ИП Котова, ООО «Северная мебель» и ООО «Вектор Логистик».
insert into pre_approved_offers
    (id, company_id, product_code, max_amount, min_term_months, max_term_months, rate, valid_until, status, created_at, updated_at)
values
    ('a1b2c3d4-0000-4000-8000-000000000001', '6f1d4c1e-0b8e-4c55-9a51-000000000001', 'WORKING_CAPITAL',
     3000000, 6, 24, 17.50, '2027-12-31T23:59:59Z', 'ACTIVE', '2026-10-01T09:00:00Z', '2026-10-01T09:00:00Z'),
    ('a1b2c3d4-0000-4000-8000-000000000002', '6f1d4c1e-0b8e-4c55-9a51-000000000002', 'OVERDRAFT',
     4000000, 12, 12, 18.00, '2027-12-31T23:59:59Z', 'ACTIVE', '2026-10-01T09:00:00Z', '2026-10-01T09:00:00Z'),
    ('a1b2c3d4-0000-4000-8000-000000000003', '6f1d4c1e-0b8e-4c55-9a51-000000000003', 'INVESTMENT',
     5000000, 12, 48, 16.90, '2027-12-31T23:59:59Z', 'ACTIVE', '2026-10-01T09:00:00Z', '2026-10-01T09:00:00Z');

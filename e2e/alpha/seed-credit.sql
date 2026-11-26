-- Apply to a fresh, migrated synthetic credit database with its writers stopped.
\set ON_ERROR_STOP on
begin;
set local lock_timeout = '5s';
lock table applications in access exclusive mode;
do $$
begin
    if current_database() !~ '^alpha_qa_' or exists (select 1 from applications) then
        raise exception 'Seed requires an empty synthetic alpha_qa_* database';
    end if;
    if not exists (select 1 from products where code = 'WORKING_CAPITAL') then
        raise exception 'Seed requires the credit migrations';
    end if;
end
$$;

with fixture(n, version, status, submitted_at, agent) as (
    values
        ( 1, 0, 'DRAFT',                       null::timestamptz,    false),
        ( 2, 2, 'PENDING_CLIENT_CONFIRMATION', null,                 true),
        ( 3, 1, 'WITHDRAWN',                   null,                 false),
        ( 4, 3, 'WITHDRAWN',                   null,                 true),
        ( 5, 1, 'SUBMITTED',                   '2026-02-01T00:00Z',  false),
        ( 6, 5, 'MANUAL_REVIEW',               '2026-02-12T09:00Z',  true),
        ( 7, 6, 'REJECTED',                    '2026-03-01T00:00Z',  false),
        ( 8, 9, 'DISBURSED',                   '2026-01-10T09:00Z',  false),
        ( 9, 8, 'CLOSED',                      '2025-12-01T09:00Z',  false),
        (10, 4, 'SUBMITTED',                   '2026-01-05T09:00Z',  false)
)
insert into applications (
    id, company_id, company_inn, company_name, legal_form, created_by,
    product_code, product_version, product_catalog_snapshot, purpose, amount, term_months,
    bureau_consent, status, created_at, updated_at, submitted_at, status_changed_at, version, agent_id
)
select
    ('10000000-0000-4000-8000-' || lpad(n::text, 12, '0'))::uuid,
    ('20000000-0000-4000-8000-' || lpad(n::text, 12, '0'))::uuid,
    lpad(n::text, 10, '0'), 'Alpha QA ' || n, 'LEGAL_ENTITY',
    '30000000-0000-4000-8000-000000000001', p.code, p.current_version,
    case when submitted_at is null then '{}' else jsonb_build_object(p.code, p.current_version) end,
    'WORKING_CAPITAL', 1000000, 12, submitted_at is not null, status,
    coalesce(submitted_at, '2026-01-01T00:00Z') - interval '1 day',
    coalesce(submitted_at, '2026-01-01T00:00Z'), submitted_at,
    coalesce(submitted_at, '2026-01-01T00:00Z'), version,
    case when agent then '30000000-0000-4000-8000-000000000002'::uuid end
from fixture
join products p on p.code = 'WORKING_CAPITAL';

with fixture(n, ordinal, event, from_status, to_status, actor_type, occurred_at) as (
    values
        ( 2,  1, 'REQUEST_CLIENT_CONFIRMATION', 'DRAFT', 'PENDING_CLIENT_CONFIRMATION', 'AGENT', '2026-01-01T00:00Z'),
        ( 3,  2, 'WITHDRAW', 'DRAFT', 'WITHDRAWN', 'CLIENT', '2026-01-01T00:00Z'),
        ( 4,  3, 'AGENT_CANCEL', 'PENDING_CLIENT_CONFIRMATION', 'WITHDRAWN', 'AGENT', '2026-01-01T00:00Z'),
        ( 5,  4, 'SUBMIT', 'DRAFT', 'SUBMITTED', 'CLIENT', '2026-02-01T00:00Z'),
        ( 6,  5, 'CONFIRM_AND_SUBMIT', 'PENDING_CLIENT_CONFIRMATION', 'SUBMITTED', 'CLIENT', '2026-02-12T09:00Z'),
        ( 6,  6, 'DATA_COMPLETE', 'SUBMITTED', 'ENRICHMENT', 'SYSTEM', '2026-02-12T09:10Z'),
        ( 6,  7, 'ENRICHMENT_DONE', 'ENRICHMENT', 'SCORING', 'SYSTEM', '2026-02-12T09:20Z'),
        ( 6,  8, 'TO_MANUAL_REVIEW', 'SCORING', 'MANUAL_REVIEW', 'SYSTEM', '2026-02-12T09:30Z'),
        ( 7,  9, 'SUBMIT', 'DRAFT', 'SUBMITTED', 'CLIENT', '2026-03-01T00:00Z'),
        ( 7, 10, 'AUTO_REJECT', 'SCORING', 'REJECTED', 'SYSTEM', '2026-03-01T00:20Z'),
        ( 8, 11, 'SIGN', 'SIGNING', 'DISBURSING', 'CLIENT', '2026-01-31T23:50Z'),
        ( 8, 12, 'DISBURSED', 'DISBURSING', 'DISBURSED', 'SYSTEM', '2026-02-01T00:00Z'),
        ( 9, 13, 'OFFER_EXPIRED', 'OFFER_ISSUED', 'CLOSED', 'SYSTEM', '2025-12-21T00:00Z')
)
insert into application_events (id, application_id, event, from_status, to_status, actor_type, occurred_at)
select ('40000000-0000-4000-8000-' || lpad(ordinal::text, 12, '0'))::uuid,
       ('10000000-0000-4000-8000-' || lpad(n::text, 12, '0'))::uuid,
       event, from_status, to_status, actor_type, occurred_at::timestamptz
from fixture order by ordinal;

insert into decisions (id, application_id, rule_set_version, decided_by_type, outcome, inputs, created_at)
values ('50000000-0000-4000-8000-000000000001', '10000000-0000-4000-8000-000000000007',
        1, 'SYSTEM', 'REJECT', '{}', '2026-03-01T00:20Z');

insert into loans (application_id, contract_number, loan_account, settlement_account, amount,
                   term_months, rate, revolving, disbursed_at, maturity_date)
values ('10000000-0000-4000-8000-000000000008', 'ALPHA-QA-SYNTHETIC',
        '00000000000000000001', '00000000000000000002',
        1000000, 12, 15.00, false, '2026-02-01T00:00Z', '2027-02-01');

commit;

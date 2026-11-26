-- Seed after Credit migrations on the disposable stand. Keep the two existing lines and a separate document request.
\set ON_ERROR_STOP on
begin;
set local lock_timeout = '5s';
select set_config('alpha.qa.final_reserve', :'synthetic_fixture', true);

do $$
begin
    if current_setting('alpha.qa.final_reserve') <> 'isolated'
        or (inet_server_addr() is not null and not inet_server_addr() << '127.0.0.0/8'::inet
            and inet_server_addr() <> '::1'::inet)
    then
        raise exception 'Reserve fixtures require an explicitly selected isolated local PostgreSQL';
    end if;
    if not exists (select from products where code = 'OVERDRAFT') then
        raise exception 'Apply Credit migrations before seeding existing lines';
    end if;
end
$$;

create temporary table final_reserve_fixture on commit drop as
select * from (values
  ('966f5c83-5359-4175-a3c6-c3f3b6ed0080'::uuid, '71f982b8-cbbb-4340-ab26-06b83d2b0080'::uuid, 320000, 'QA-FINAL-RES-080'),
  ('966f5c83-5359-4175-a3c6-c3f3b6ed0200'::uuid, '71f982b8-cbbb-4340-ab26-06b83d2b0200'::uuid, 200000, 'QA-FINAL-RES-200')
) v(application_id, loan_id, disbursed_amount, contract_number);

insert into applications (
    id, company_id, company_inn, company_name, legal_form, created_by,
    product_code, product_version, product_catalog_snapshot, purpose, amount, term_months,
    bureau_consent, status, created_at, updated_at, submitted_at, status_changed_at, version
)
select f.application_id, '6f1d4c1e-0b8e-4c55-9a51-000000000002', '0001001014',
       'ООО «Северная мебель»', 'LEGAL_ENTITY', '9b2f0c4a-6d1e-4f2a-8c3b-000000000002',
       p.code, p.current_version, jsonb_build_object(p.code, p.current_version),
       'CASH_GAP', 400000, 12, true, 'DISBURSED', '2026-11-20T10:00Z',
       '2026-11-21T10:00Z', '2026-11-20T11:00Z', '2026-11-21T10:00Z', 8
from final_reserve_fixture f join products p on p.code = 'OVERDRAFT'
on conflict (id) do nothing;

insert into loans (
    application_id, contract_number, loan_account, settlement_account, amount,
    term_months, rate, revolving, disbursed_at, maturity_date
)
select application_id, contract_number, '00000000000000000001', '00000000000000000002',
       400000, 12, 19.00, true, '2026-11-21T10:00Z', '2027-11-21'
from final_reserve_fixture
on conflict (application_id) do nothing;

-- The first tranche starts at half the approved limit. The 80k fixture has a second prior draw.
with seeded_accounts as (
    insert into loan_accounts (
        id, application_id, company_id, contract_number, approved_limit, disbursed_amount,
        outstanding_principal, rate, term_months, revolving, status, disbursed_at, maturity_date
    )
    select loan_id, application_id, '6f1d4c1e-0b8e-4c55-9a51-000000000002', contract_number,
           400000, disbursed_amount, disbursed_amount, 19.00, 12, true, 'ACTIVE',
           '2026-11-21T10:00Z', '2027-11-21'
    from final_reserve_fixture
    on conflict (id) do nothing
    returning id, disbursed_amount
), tranches as (
    insert into loan_tranches (id, loan_account_id, tranche_number, amount, purpose, disbursed_at)
    select gen_random_uuid(), a.id, t.number, t.amount, 'Синтетический транш', '2026-11-21T10:00Z'
    from seeded_accounts a
    cross join lateral (values (1, 200000), (2, a.disbursed_amount - 200000)) t(number, amount)
    where t.amount > 0
)
insert into loan_payments (
    id, loan_account_id, payment_number, due_date, payment_amount, principal_amount,
    interest_amount, remaining_balance, status
)
select gen_random_uuid(), a.id, point + 1, ('2026-12-21'::date + point * interval '1 month')::date,
       case when point = 11 then a.disbursed_amount else 0 end,
       case when point = 11 then a.disbursed_amount else 0 end, 0,
       case when point = 11 then 0 else a.disbursed_amount end, 'SCHEDULED'
from seeded_accounts a cross join generate_series(0, 11) as point;

-- This case isolates document access from scheduling and the risk model.
-- It is an agent-originated application with a genuinely open, post-submission bank request.
insert into applications (
    id, company_id, company_inn, company_name, legal_form, created_by,
    product_code, product_version, product_catalog_snapshot, purpose, amount, term_months,
    bureau_consent, status, created_at, updated_at, submitted_at, status_changed_at, version, agent_id
)
select '966f5c83-5359-4175-a3c6-c3f3b6ed0310', '6f1d4c1e-0b8e-4c55-9a51-000000000002',
       '0001001014', 'ООО «Северная мебель»', 'LEGAL_ENTITY',
       '9b2f0c4a-6d1e-4f2a-8c3b-000000000201',
       p.code, p.current_version, jsonb_build_object(p.code, p.current_version),
       'EQUIPMENT', 1500000, 12, true, 'INFO_REQUESTED',
       now() - interval '2 days', now() - interval '1 hour',
       now() - interval '1 day', now() - interval '1 hour', 6,
       '9b2f0c4a-6d1e-4f2a-8c3b-000000000201'
from products p where p.code = 'INVESTMENT'
on conflict (id) do nothing;

insert into info_requests (id, application_id, items, comment, deadline, requested_by_type, created_at)
values (
    '16859b9b-9cd3-46df-8fb4-1ea824e00310', '966f5c83-5359-4175-a3c6-c3f3b6ed0310',
    '[{"documentType":"BANK_STATEMENT","question":null}]', 'Синтетический дозапрос выписки',
    now() + interval '3 days', 'UNDERWRITER', now() - interval '1 hour'
)
on conflict (id) do nothing;

commit;

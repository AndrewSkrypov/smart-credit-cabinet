begin isolation level repeatable read, read only;
set local time zone 'UTC';
set local datestyle = 'ISO, YMD';

select current_database() = :'expected_database' as correct_database \gset
\if :correct_database
\else
    \echo Подключение указывает на другую БД
    select 1 / 0;
\endif

\if :credit_source
do $$
begin
    if exists (
        select 1 from applications a left join product_versions v on v.code = a.product_code and v.version = a.product_version
        where v.code is null
    ) or exists (
        select 1 from offers o left join product_versions v on v.code = o.product_code and v.version = o.product_version
        where v.code is null
    ) then
        raise exception 'Credit references a missing selected or offered product revision';
    end if;
    if exists (
        select 1 from applications a
        cross join lateral jsonb_each_text(a.product_catalog_snapshot) alt
        where not exists (
            select 1 from product_versions v where v.code = alt.key and v.version = alt.value::bigint
        )
    ) then
        raise exception 'Alternative references a missing product revision';
    end if;
end
$$;
\endif

\copy (select code, name, description, purposes, min_amount, max_amount, min_term_months, max_term_months, base_rate, auto_approval_limit, revolving, display_order, current_version from products order by code collate "C") to 'products.csv' with (format csv, header true, encoding 'UTF8')
\copy (select code, version, name, description, purposes, min_amount, max_amount, min_term_months, max_term_months, base_rate, auto_approval_limit, revolving, active, author_id, reason, created_at, author_name, author_role, lifecycle_status, effective_from, effective_to, agent_commission_rate_bps from product_versions order by code collate "C", version) to 'product_versions.csv' with (format csv, header true, encoding 'UTF8')

commit;

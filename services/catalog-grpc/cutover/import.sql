begin;
set local lock_timeout = '30s';
set local time zone 'UTC';
set local datestyle = 'ISO, YMD';

select current_database() = :'expected_database' as correct_database \gset
\if :correct_database
\else
    \echo Подключение указывает на другую БД
    select 1 / 0;
\endif

lock table public.products, public.product_versions in access exclusive mode;
lock table catalog_cutover.authority in access exclusive mode;
do $$
begin
    if not exists (select 1 from catalog_cutover.authority where singleton and not writable) then
        raise exception 'Catalog import requires a fenced target';
    end if;
end
$$;

create temporary table imported_products (like public.products) on commit drop;
create temporary table imported_versions (like public.product_versions) on commit drop;
\copy imported_products (code, name, description, purposes, min_amount, max_amount, min_term_months, max_term_months, base_rate, auto_approval_limit, revolving, display_order, current_version) from 'products.csv' with (format csv, header match, encoding 'UTF8')
\copy imported_versions (code, version, name, description, purposes, min_amount, max_amount, min_term_months, max_term_months, base_rate, auto_approval_limit, revolving, active, author_id, reason, created_at, author_name, author_role, lifecycle_status, effective_from, effective_to, agent_commission_rate_bps) from 'product_versions.csv' with (format csv, header match, encoding 'UTF8')

-- The table owner reconciles only under exclusive locks while both runtime writers are closed.
alter table public.products disable trigger catalog_write_fence;
alter table public.product_versions disable trigger catalog_write_fence;

insert into public.products select * from imported_products
on conflict (code) do update set
    name = excluded.name, description = excluded.description, purposes = excluded.purposes,
    min_amount = excluded.min_amount, max_amount = excluded.max_amount,
    min_term_months = excluded.min_term_months, max_term_months = excluded.max_term_months,
    base_rate = excluded.base_rate, auto_approval_limit = excluded.auto_approval_limit,
    revolving = excluded.revolving, display_order = excluded.display_order, current_version = excluded.current_version;

insert into public.product_versions select * from imported_versions on conflict (code, version) do nothing;

do $$
begin
    if exists (select * from imported_products except all select * from public.products)
       or exists (select * from public.products except all select * from imported_products)
       or exists (select * from imported_versions except all select * from public.product_versions)
       or exists (select * from public.product_versions except all select * from imported_versions) then
        raise exception 'Imported catalog differs from the source snapshot';
    end if;
end
$$;

set constraints all immediate;
alter table public.products enable trigger catalog_write_fence;
alter table public.product_versions enable trigger catalog_write_fence;
commit;

\set ON_ERROR_STOP on
begin;
do $$
begin
    if current_database() <> 'catalog' then
        raise exception 'Catalog grants require the catalog database';
    end if;
end
$$;

revoke all on database catalog from public;
revoke all on schema public from public;
revoke all on table public.products, public.product_versions from public, catalog_app, catalog_writer;
grant connect on database catalog to catalog_app, catalog_writer;
grant usage on schema public to catalog_app, catalog_writer;
grant select on table public.products, public.product_versions to catalog_app, catalog_writer;
grant insert on table public.products, public.product_versions to catalog_writer;
grant update (current_version) on table public.products to catalog_writer;
commit;

begin;
revoke all on schema catalog_cutover from public;
grant usage on schema catalog_cutover to catalog_app, catalog_writer;
grant select on table catalog_cutover.authority to catalog_app, catalog_writer;
grant execute on function catalog_cutover.lock_authority() to catalog_app, catalog_writer;
commit;

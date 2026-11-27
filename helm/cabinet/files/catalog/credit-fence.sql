\set ON_ERROR_STOP on
begin;
do $$
begin
    if current_database() <> 'credit' then
        raise exception 'Credit fencing requires the credit database';
    end if;
    if exists (
        select 1 from pg_class
        where oid in ('public.products'::regclass, 'public.product_versions'::regclass)
          and pg_get_userbyid(relowner) <> current_user
    ) then
        raise exception 'Install the fence as the Credit catalog table owner';
    end if;
end
$$;

create schema catalog_cutover;
revoke all on schema catalog_cutover from public;
create table catalog_cutover.authority (
    singleton boolean primary key default true check (singleton),
    writable boolean not null
);
insert into catalog_cutover.authority (writable) values (true);

create function catalog_cutover.lock_authority() returns boolean
    language sql security definer set search_path = pg_catalog as $$
    select writable from catalog_cutover.authority where singleton for share
$$;
revoke all on function catalog_cutover.lock_authority() from public;

create function catalog_cutover.require_authority() returns trigger
    language plpgsql set search_path = pg_catalog as $$
begin
    if not coalesce(catalog_cutover.lock_authority(), false)
        and not (current_user = pg_get_userbyid((select relowner from pg_class where oid = tg_relid))
                 and session_user <> current_user) then
        raise exception using errcode = '55000', message = 'Credit catalog writer is fenced';
    end if;
    return null;
end
$$;

create trigger catalog_write_fence before insert or update or delete or truncate on public.products
    for each statement execute function catalog_cutover.require_authority();
create trigger catalog_write_fence before insert or update or delete or truncate on public.product_versions
    for each statement execute function catalog_cutover.require_authority();

create function catalog_cutover.credit_replay_ready() returns boolean
    language sql stable set search_path = pg_catalog as $$
    select exists (select 1 from catalog_cutover.authority where singleton and not writable)
      and not pg_has_role((select relowner from pg_class where oid = 'public.products'::regclass), 'MEMBER')
      and not pg_has_role((select relowner from pg_class where oid = 'public.product_versions'::regclass), 'MEMBER')
      and not has_table_privilege('catalog_cutover.authority', 'UPDATE')
$$;

create function catalog_cutover.store_revisions(payload jsonb) returns void
    language plpgsql security definer set search_path = pg_catalog as $$
declare
    revision public.product_versions;
    stored public.product_versions;
begin
    if catalog_cutover.lock_authority() is distinct from false then
        raise exception using errcode = '55000', message = 'Credit replay requires the local writer to be fenced';
    end if;
    for revision in
        select * from jsonb_populate_recordset(null::public.product_versions, payload) order by code, version
    loop
        insert into public.products
            (code, name, description, purposes, min_amount, max_amount, min_term_months, max_term_months,
             base_rate, auto_approval_limit, revolving, display_order, current_version)
        values
            (revision.code, revision.name, revision.description, revision.purposes, revision.min_amount, revision.max_amount,
             revision.min_term_months, revision.max_term_months, revision.base_rate, revision.auto_approval_limit,
             revision.revolving, 0, revision.version)
        on conflict (code) do nothing;

        insert into public.product_versions select (revision).*
        on conflict (code, version) do nothing;

        select * into strict stored from public.product_versions
        where code = revision.code and version = revision.version;
        if stored is distinct from revision then
            raise exception using errcode = '23514', message = 'Catalog revision differs from the immutable Credit copy';
        end if;
    end loop;
end
$$;

revoke all on function catalog_cutover.credit_replay_ready(), catalog_cutover.store_revisions(jsonb) from public;
grant usage on schema catalog_cutover to :"credit_app";
grant select on catalog_cutover.authority to :"credit_app";
grant execute on function catalog_cutover.lock_authority(), catalog_cutover.credit_replay_ready(),
    catalog_cutover.store_revisions(jsonb) to :"credit_app";
commit;

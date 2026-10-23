-- Used by privileged bootstrap, by the schema owner after migration, and after restore.
-- Identifiers are replaced only from the fixed identity/credit/engagement allowlist.
begin;
select pg_advisory_xact_lock(17127, 2);
alter schema public owner to __OWNER__;
revoke all on schema public from public;
revoke all on schema public from __APP__;
grant usage on schema public to __APP__;

-- Never REASSIGN OWNED from the bootstrap role: that can also transfer shared objects
-- belonging to other databases. Restrict adoption to this database's public schema.
do $$
declare item record;
begin
  for item in select c.relname,c.relkind from pg_class c join pg_namespace n on n.oid=c.relnamespace
    where n.nspname='public' and c.relkind in ('r','p','v','m') order by c.oid
  loop
    execute format('alter %s public.%I owner to __OWNER__',
      case item.relkind when 'v' then 'view' when 'm' then 'materialized view' else 'table' end,item.relname);
  end loop;
  for item in select c.relname from pg_class c join pg_namespace n on n.oid=c.relnamespace
    where n.nspname='public' and c.relkind='S'
  loop execute format('alter sequence public.%I owner to __OWNER__',item.relname); end loop;
  for item in select p.oid::regprocedure as signature from pg_proc p join pg_namespace n on n.oid=p.pronamespace
    where n.nspname='public'
  loop execute format('alter function %s owner to __OWNER__',item.signature); end loop;
  for item in select t.typname from pg_type t join pg_namespace n on n.oid=t.typnamespace
    where n.nspname='public' and t.typtype in ('e','d')
  loop execute format('alter type public.%I owner to __OWNER__',item.typname); end loop;
end $$;

revoke all on all tables in schema public from public, __APP__;
grant select,insert,update,delete on all tables in schema public to __APP__;
revoke all on all sequences in schema public from public, __APP__;
grant usage,select on all sequences in schema public to __APP__;
revoke all on all functions in schema public from public, __APP__;
grant execute on all functions in schema public to __APP__;
alter default privileges for role __OWNER__ in schema public revoke all on tables from public;
alter default privileges for role __OWNER__ in schema public grant select,insert,update,delete on tables to __APP__;
alter default privileges for role __OWNER__ in schema public revoke all on sequences from public;
alter default privileges for role __OWNER__ in schema public grant usage,select on sequences to __APP__;
-- Function EXECUTE is granted to PUBLIC globally by default; a schema-only REVOKE
-- would not subtract that global default. Remove it at the owner's database scope.
alter default privileges for role __OWNER__ revoke execute on functions from public;
alter default privileges for role __OWNER__ in schema public grant execute on functions to __APP__;

do $$
declare item text;
begin
  foreach item in array array['personal_data_consents','identity_audit','notification_receipts',
    'product_versions','application_events','application_messages','message_attachments',
    'application_chat_audit','application_chat_upload_keys']
  loop
    if to_regclass('public.' || item) is not null then
      execute format('revoke update,delete on public.%I from __APP__',item);
    end if;
  end loop;
  if to_regclass('public.flyway_schema_history') is not null then
    revoke insert,update,delete on public.flyway_schema_history from __APP__;
  end if;
end $$;
commit;

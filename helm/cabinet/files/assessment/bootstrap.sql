\getenv owner_password ASSESSMENT_MIGRATION_PASSWORD
\getenv app_password ASSESSMENT_DB_PASSWORD

select format('create role assessment_owner login noinherit nosuperuser nocreatedb nocreaterole noreplication nobypassrls password %L', :'owner_password')
where not exists (select from pg_roles where rolname='assessment_owner') \gexec
select format('create role assessment_app login noinherit nosuperuser nocreatedb nocreaterole noreplication nobypassrls password %L', :'app_password')
where not exists (select from pg_roles where rolname='assessment_app') \gexec
select 'create database assessment owner assessment_owner template template0'
where not exists (select from pg_database where datname='assessment') \gexec

do $$
begin
    if not exists (select from pg_database d join pg_roles r on r.oid=d.datdba
                   where d.datname='assessment' and r.rolname='assessment_owner') then
        raise exception 'Assessment database requires its dedicated owner';
    end if;
    if exists (select from pg_roles
               where rolname in ('assessment_owner', 'assessment_app')
                 and (not rolcanlogin or rolinherit or rolsuper or rolcreatedb or rolcreaterole or rolreplication or rolbypassrls))
       or exists (select from pg_auth_members m join pg_roles r on r.oid=m.member
                  where r.rolname in ('assessment_owner', 'assessment_app')) then
        raise exception 'Assessment roles have unexpected privileges';
    end if;
end $$;

revoke connect on database postgres, template1, template0 from public;
revoke all on database assessment from public;
grant connect on database assessment to assessment_app;

\connect assessment
revoke all on schema public from public;
grant usage on schema public to assessment_app;

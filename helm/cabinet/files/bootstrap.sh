#!/bin/sh
# Privileged bootstrap only. Application containers never receive these credentials.
set -eu
for database in identity credit engagement; do
  case "$database" in
    identity) export APP_PASSWORD="$IDENTITY_DB_PASSWORD" OWNER_PASSWORD="$IDENTITY_MIGRATION_PASSWORD" ;;
    credit) export APP_PASSWORD="$CREDIT_DB_PASSWORD" OWNER_PASSWORD="$CREDIT_MIGRATION_PASSWORD" ;;
    engagement) export APP_PASSWORD="$ENGAGEMENT_DB_PASSWORD" OWNER_PASSWORD="$ENGAGEMENT_MIGRATION_PASSWORD" ;;
  esac
  for value in "$APP_PASSWORD" "$OWNER_PASSWORD"; do
    case "$value" in ''|*[!a-zA-Z0-9_-]*) echo 'Invalid database secret format' >&2; exit 1 ;; esac
  done
  test "$APP_PASSWORD" != "$OWNER_PASSWORD"
  psql -X -q -v ON_ERROR_STOP=1 -v db="$database" -v app="${database}_app" -v owner="${database}_owner" <<'SQL'
\set VERBOSITY terse
\set SHOW_CONTEXT never
\getenv app_password APP_PASSWORD
\getenv owner_password OWNER_PASSWORD
-- Suppress password-bearing statements even if the operator normally logs all DDL.
set log_statement = 'none';
set log_min_error_statement = 'panic';
select pg_advisory_lock(17127, 1);
select format('create role %I login', role_name)
from (values (:'app'), (:'owner')) roles(role_name)
where not exists (select 1 from pg_roles where rolname=role_name) \gexec
select format('alter role %I login nosuperuser nocreatedb nocreaterole noreplication nobypassrls noinherit password %L', role_name, secret)
from (values (:'app', :'app_password'), (:'owner', :'owner_password')) roles(role_name,secret) \gexec
select format('revoke %I from %I', parent.rolname, member.rolname)
from pg_auth_members m join pg_roles parent on parent.oid=m.roleid join pg_roles member on member.oid=m.member
where member.rolname in (:'app', :'owner') \gexec
select format('create database %I owner %I', :'db', :'owner')
where not exists (select 1 from pg_database where datname=:'db') \gexec
select format('alter database %I owner to %I', :'db', :'owner') \gexec
-- The bootstrap/admin databases are not a back door for service roles.
select format('revoke connect, temporary on database %I from public', datname)
from pg_database where datname in ('cabinet','postgres','template0','template1','identity','credit','engagement') \gexec
select format('revoke all on database %I from %I', datname, :'app')
from pg_database where datname in ('cabinet','postgres','template0','template1','identity','credit','engagement') \gexec
select format('grant connect on database %I to %I', :'db', :'app') \gexec
select pg_advisory_unlock(17127, 1);
SQL
  sed -e "s/__DATABASE__/$database/g" -e "s/__OWNER__/${database}_owner/g" -e "s/__APP__/${database}_app/g" \
    /cabinet/database-permissions.sql | PGDATABASE="$database" psql -X -q -v ON_ERROR_STOP=1
done
unset APP_PASSWORD OWNER_PASSWORD
echo 'Database owners, runtime roles and privileges reconciled'

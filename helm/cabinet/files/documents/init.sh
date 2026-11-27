#!/bin/sh
set -eu
psql -X -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname documents <<'SQL'
\getenv owner_pwd CABINET_DOCUMENTS_PG_OWNER_PASSWORD
\getenv app_pwd CABINET_DOCUMENTS_PG_APP_PASSWORD
create role documents_owner login nosuperuser nocreatedb nocreaterole noinherit password :'owner_pwd';
create role documents_app login nosuperuser nocreatedb nocreaterole noinherit password :'app_pwd';
alter database documents owner to documents_owner;
revoke all on database documents from public;
revoke connect,temporary on database postgres,template1 from public;
grant connect on database documents to documents_app;
alter schema public owner to documents_owner;
revoke all on schema public from public;
SQL

\set ON_ERROR_STOP on
\set VERBOSITY terse
\set SHOW_CONTEXT never
\getenv owner_password CABINET_SERVICING_PG_OWNER_PASSWORD
\getenv app_password CABINET_SERVICING_PG_APP_PASSWORD
set log_statement = 'none';
set log_min_error_statement = 'panic';
create role servicing_owner login nosuperuser nocreatedb nocreaterole noreplication nobypassrls noinherit password :'owner_password';
create role servicing_app login nosuperuser nocreatedb nocreaterole noreplication nobypassrls noinherit password :'app_password';
create database servicing owner servicing_owner;
revoke connect, temporary on database postgres, template0, template1, servicing from public;
grant connect on database servicing to servicing_app;
\connect servicing
alter schema public owner to servicing_owner;
revoke all on schema public from public;
grant usage on schema public to servicing_app;

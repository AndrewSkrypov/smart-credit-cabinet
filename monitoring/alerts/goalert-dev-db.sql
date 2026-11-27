\set ON_ERROR_STOP on
\getenv goalert_app_password GOALERT_DB_APP_PASSWORD
SET log_min_error_statement = 'panic';
CREATE ROLE goalert LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS
  PASSWORD :'goalert_app_password';
CREATE DATABASE goalert OWNER goalert;
REVOKE CONNECT ON DATABASE goalert, postgres, template1 FROM PUBLIC;
\connect goalert
CREATE EXTENSION IF NOT EXISTS pgcrypto;

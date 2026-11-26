\getenv owner_password ALPHA_PILOT_PG_OWNER_PASSWORD
\getenv app_password ALPHA_PILOT_PG_APP_PASSWORD

CREATE ROLE analytics_owner LOGIN PASSWORD :'owner_password';
CREATE ROLE analytics_app LOGIN PASSWORD :'app_password';
ALTER DATABASE analytics OWNER TO analytics_owner;
REVOKE CONNECT ON DATABASE analytics, postgres, template1 FROM PUBLIC;
GRANT CONNECT ON DATABASE analytics TO analytics_app;

REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO analytics_app;
ALTER DEFAULT PRIVILEGES FOR ROLE analytics_owner IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO analytics_app;
ALTER DEFAULT PRIVILEGES FOR ROLE analytics_owner IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO analytics_app;

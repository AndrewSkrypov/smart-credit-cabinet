\set ON_ERROR_STOP on
SELECT format('CREATE ROLE catalog_owner LOGIN PASSWORD %L', :'owner_password')
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'catalog_owner') \gexec
SELECT format('CREATE ROLE catalog_app LOGIN PASSWORD %L', :'app_password')
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'catalog_app') \gexec
SELECT 'CREATE ROLE catalog_writer NOLOGIN'
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'catalog_writer') \gexec

DO $$
BEGIN
    IF (SELECT count(*) FROM pg_roles
        WHERE rolname IN ('catalog_owner', 'catalog_app', 'catalog_writer')
          AND rolcanlogin = (rolname <> 'catalog_writer')
          AND NOT (rolsuper OR rolcreaterole OR rolcreatedb OR rolreplication OR rolbypassrls)
          AND rolinherit) <> 3
      OR pg_has_role('catalog_app', 'catalog_owner', 'MEMBER')
      OR pg_has_role('catalog_app', 'credit_owner', 'MEMBER')
      OR pg_has_role('catalog_writer', 'catalog_owner', 'MEMBER')
      OR pg_has_role('credit_app', 'catalog_owner', 'MEMBER')
      OR pg_has_role('credit_app', 'catalog_writer', 'MEMBER') THEN
        RAISE EXCEPTION 'Catalog roles are not isolated';
    END IF;
END
$$;

SELECT 'CREATE DATABASE catalog WITH OWNER catalog_owner TEMPLATE template0'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'catalog') \gexec

DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_database WHERE datname = 'catalog'
                   AND pg_get_userbyid(datdba) = 'catalog_owner') THEN
        RAISE EXCEPTION 'Catalog requires its own database owner';
    END IF;
END
$$;

REVOKE ALL ON DATABASE catalog FROM PUBLIC;
GRANT catalog_writer TO catalog_app;

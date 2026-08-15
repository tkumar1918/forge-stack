-- Two database roles, because row-level security depends on the application never
-- being able to bypass it.
--
--   forgestack_migrator : owns the schema, runs Flyway. Table owner.
--   forgestack_app      : the application. NOT superuser, NOT BYPASSRLS, NOT the table owner.
--
-- A table's owner bypasses RLS unless FORCE ROW LEVEL SECURITY is set. We set FORCE in
-- the migrations as well, so isolation holds even for the migrator.

CREATE ROLE forgestack_migrator WITH LOGIN PASSWORD 'forgestack_migrator' NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE;
CREATE ROLE forgestack_app      WITH LOGIN PASSWORD 'forgestack_app'      NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE;

ALTER DATABASE forgestack OWNER TO forgestack_migrator;

\connect forgestack

-- The migrator owns the public schema so it can create extensions and tables.
ALTER SCHEMA public OWNER TO forgestack_migrator;
GRANT USAGE ON SCHEMA public TO forgestack_app;

-- Anything the migrator creates from here on is readable/writable by the app by default.
-- Individual migrations narrow this where needed (audit_events gets INSERT + SELECT only).
ALTER DEFAULT PRIVILEGES FOR ROLE forgestack_migrator IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO forgestack_app;
ALTER DEFAULT PRIVILEGES FOR ROLE forgestack_migrator IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO forgestack_app;

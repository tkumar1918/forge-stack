-- Mirrors docker/postgres/init/01-roles.sql for integration tests.
-- Runs as the Testcontainers superuser, already connected to the target database.

CREATE ROLE forgestack_migrator WITH LOGIN PASSWORD 'forgestack_migrator' NOSUPERUSER NOBYPASSRLS;
CREATE ROLE forgestack_app WITH LOGIN PASSWORD 'forgestack_app' NOSUPERUSER NOBYPASSRLS;

ALTER SCHEMA public OWNER TO forgestack_migrator;

-- Needed so the migrator can install the trusted citext extension.
GRANT CREATE ON DATABASE forgestack TO forgestack_migrator;
GRANT USAGE ON SCHEMA public TO forgestack_app;

ALTER DEFAULT PRIVILEGES FOR ROLE forgestack_migrator IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO forgestack_app;
ALTER DEFAULT PRIVILEGES FOR ROLE forgestack_migrator IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO forgestack_app;

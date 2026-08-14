-- Mirrors docker/postgres/init/01-roles.sql for integration tests.
-- Runs as the Testcontainers superuser, already connected to the target database.

CREATE ROLE forge_migrator WITH LOGIN PASSWORD 'forge_migrator' NOSUPERUSER NOBYPASSRLS;
CREATE ROLE forge_app WITH LOGIN PASSWORD 'forge_app' NOSUPERUSER NOBYPASSRLS;

ALTER SCHEMA public OWNER TO forge_migrator;

-- Needed so the migrator can install the trusted citext extension.
GRANT CREATE ON DATABASE forge TO forge_migrator;
GRANT USAGE ON SCHEMA public TO forge_app;

ALTER DEFAULT PRIVILEGES FOR ROLE forge_migrator IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO forge_app;
ALTER DEFAULT PRIVILEGES FOR ROLE forge_migrator IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO forge_app;

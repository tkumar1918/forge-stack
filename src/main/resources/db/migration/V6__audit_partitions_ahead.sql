-- Provision audit_events partitions ahead, and make "create" inseparable from "lock down".
--
-- V1 created partitions for 2026-08 and 2026-09 only. Rows past that land in the DEFAULT
-- partition, which keeps working and is therefore silent — but it is worse than it looks: once
-- October rows sit in DEFAULT, the October partition can no longer be created without moving them
-- out first. The cost of the gap grows the longer nobody notices it.
--
-- The sharper problem is the grants. docker/postgres/init/01-roles.sql sets
--
--     ALTER DEFAULT PRIVILEGES FOR ROLE forgestack_migrator IN SCHEMA public
--         GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO forgestack_app;
--
-- so every table the migrator creates arrives with full DML for the application, and V1's
-- REVOKE on each partition is what pulls it back to INSERT + SELECT. A partition created without
-- that REVOKE is directly writable: permission is checked on the relation actually named, so
-- `UPDATE audit_events` is refused by the parent's grants while
-- `UPDATE audit_events_2027_01` would not be. Audit history stops being append-only for that
-- month, and nothing about it looks wrong.
--
-- Verified rather than assumed: a partition created without the REVOKE reports
-- DELETE,INSERT,SELECT,UPDATE for forgestack_app, against INSERT,SELECT on the existing ones.
--
-- So partition creation gets a function that does both halves. The scheduled job named in
-- known-gaps.md §3.1 should call this rather than issuing its own CREATE TABLE, because the half
-- that is easy to forget is the half that matters.

CREATE FUNCTION create_audit_events_partition(month_start date) RETURNS void AS $$
DECLARE
    partition_name text := 'audit_events_' || to_char(month_start, 'YYYY_MM');
    -- Bounds carry an explicit +00, exactly as V1's literals do. A bare date passed through %L
    -- becomes '2026-10-01', which timestamptz resolves in the *session* timezone — and the JDBC
    -- connection inherits the JVM's, not the server's. On a +05:30 machine that silently shifts
    -- every boundary back 5.5 hours, misfiling rows written near midnight UTC on the 1st into the
    -- previous month. It surfaced here only because the first month collided with an existing
    -- partition and Postgres refused; the months after it would have been created crooked and
    -- said nothing.
    lower_bound text := to_char(month_start, 'YYYY-MM-DD') || ' 00:00:00+00';
    upper_bound text := to_char((month_start + interval '1 month')::date, 'YYYY-MM-DD') || ' 00:00:00+00';
BEGIN
    EXECUTE format(
        'CREATE TABLE IF NOT EXISTS %I PARTITION OF audit_events FOR VALUES FROM (%L) TO (%L)',
        partition_name,
        lower_bound,
        upper_bound);

    -- Not conditional on the table having just been created: re-running must converge on the
    -- right grants rather than assume they were right the first time.
    EXECUTE format('REVOKE UPDATE, DELETE, TRUNCATE ON %I FROM forgestack_app', partition_name);
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION create_audit_events_partition(date) IS
    'Creates one monthly audit_events partition and revokes the app role''s UPDATE/DELETE/TRUNCATE '
    'on it. Always use this rather than a bare CREATE TABLE: new tables inherit full DML from '
    'ALTER DEFAULT PRIVILEGES, so a partition created without the revoke is silently rewritable.';

-- Functions are executable by PUBLIC by default. Creating tables in a schema it does not own would
-- fail for forgestack_app anyway, but an application role should not be able to reach a
-- privilege-altering routine at all.
REVOKE EXECUTE ON FUNCTION create_audit_events_partition(date) FROM PUBLIC;

-- Runway through the end of 2027. The DEFAULT partition stays as the safety net it was always
-- meant to be, rather than the place everything actually lands.
DO $$
DECLARE
    month_start date := date '2026-10-01';
BEGIN
    WHILE month_start < date '2028-01-01' LOOP
        PERFORM create_audit_events_partition(month_start);
        month_start := (month_start + interval '1 month')::date;
    END LOOP;
END;
$$;

-- Forge baseline: tenancy, identity, audit.
--
-- Runs as forge_migrator (schema owner). The application connects as forge_app, which is
-- neither superuser nor BYPASSRLS nor the table owner.

-- Case-insensitive identifiers (emails, slugs) are stored as plain text and normalised to
-- lower case by the application before they are written. The obvious alternative, citext,
-- reports as Types#OTHER over JDBC and fails Hibernate's schema validation, which would mean
-- either weakening ddl-auto=validate or teaching Hibernate a custom type. Normalising at the
-- boundary is less machinery and keeps the column a boring, portable text.

-- ---------------------------------------------------------------------------
-- Identity (global, NOT tenant-scoped)
--
-- These tables are read during authentication, before any workspace is known. Putting
-- RLS on them would be a chicken-and-egg deadlock: you cannot resolve the tenant until
-- you have resolved the session, and you cannot read the session under a tenant policy.
-- They carry no workspace-owned data and are protected at the application layer.
-- ---------------------------------------------------------------------------

CREATE TABLE users (
    id            uuid PRIMARY KEY     DEFAULT gen_random_uuid(),
    primary_email text        NOT NULL UNIQUE,
    display_name  text,
    avatar_url    text,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    deleted_at    timestamptz
);

-- No token column, deliberately. The GitHub OAuth access token is used once to fetch the
-- profile during login and then discarded. A stored user token is a standing credential
-- that can act as that human on GitHub; the agent never needs it, because it uses
-- short-lived, repository-scoped GitHub App installation tokens instead.
CREATE TABLE user_identities (
    id               uuid PRIMARY KEY     DEFAULT gen_random_uuid(),
    user_id          uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    provider         text        NOT NULL,
    provider_user_id text        NOT NULL,
    provider_login   text,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT user_identities_provider_uk UNIQUE (provider, provider_user_id)
);

CREATE INDEX user_identities_user_id_idx ON user_identities (user_id);

-- ---------------------------------------------------------------------------
-- Tenancy (global read path, NOT tenant-scoped)
--
-- "Which workspaces may I access?" must be answerable without a workspace context, so
-- these are also excluded from RLS and guarded by application-layer authorization.
-- ---------------------------------------------------------------------------

CREATE TABLE workspaces (
    id         uuid PRIMARY KEY     DEFAULT gen_random_uuid(),
    slug       text        NOT NULL UNIQUE,
    name       text        NOT NULL,
    plan       text        NOT NULL DEFAULT 'ALPHA',
    status     text        NOT NULL DEFAULT 'ACTIVE',
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz,
    CONSTRAINT workspaces_status_ck CHECK (status IN ('ACTIVE', 'SUSPENDED', 'DELETED'))
);

CREATE TABLE workspace_members (
    workspace_id uuid        NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    user_id      uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role         text        NOT NULL,
    invited_by   uuid REFERENCES users (id),
    created_at   timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (workspace_id, user_id),
    CONSTRAINT workspace_members_role_ck CHECK (role IN ('OWNER', 'ADMIN', 'MAINTAINER', 'VIEWER'))
);

CREATE INDEX workspace_members_user_id_idx ON workspace_members (user_id);

-- Opaque server-side sessions. Only the hash of the token is stored, so a database leak
-- does not yield usable session tokens. Not JWT: removing a member from a workspace must
-- revoke access immediately, and embedded claims go stale exactly when that matters.
CREATE TABLE sessions (
    id           uuid PRIMARY KEY     DEFAULT gen_random_uuid(),
    user_id      uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    workspace_id uuid REFERENCES workspaces (id) ON DELETE SET NULL,
    token_hash   bytea       NOT NULL UNIQUE,
    user_agent   text,
    ip           inet,
    created_at   timestamptz NOT NULL DEFAULT now(),
    last_seen_at timestamptz NOT NULL DEFAULT now(),
    expires_at   timestamptz NOT NULL,
    revoked_at   timestamptz
);

CREATE INDEX sessions_user_id_idx ON sessions (user_id);
CREATE INDEX sessions_expires_at_idx ON sessions (expires_at) WHERE revoked_at IS NULL;

-- ---------------------------------------------------------------------------
-- Audit (tenant-scoped, append-only, monthly partitions)
--
-- Partitioned from the outset: it grows without bound and is almost always queried by
-- recency. Retrofitting partitioning onto a live table is miserable.
-- ---------------------------------------------------------------------------

CREATE TABLE audit_events (
    id            uuid        NOT NULL DEFAULT gen_random_uuid(),
    workspace_id  uuid        NOT NULL,
    actor_type    text        NOT NULL,
    actor_id      uuid,
    action        text        NOT NULL,
    resource_type text,
    resource_id   uuid,
    task_id       uuid,
    risk_level    text,
    before        jsonb,
    after         jsonb,
    ip            inet,
    user_agent    text,
    request_id    text,
    created_at    timestamptz NOT NULL DEFAULT now(),
    -- The partition key must be part of the primary key on a partitioned table.
    PRIMARY KEY (id, created_at),
    CONSTRAINT audit_events_actor_type_ck
        CHECK (actor_type IN ('HUMAN', 'SYSTEM', 'SCHEDULER', 'SUPERVISOR', 'EXECUTOR'))
) PARTITION BY RANGE (created_at);

CREATE INDEX audit_events_workspace_created_idx ON audit_events (workspace_id, created_at DESC);
CREATE INDEX audit_events_task_idx ON audit_events (task_id) WHERE task_id IS NOT NULL;

-- A default partition means an insert can never fail for want of a partition. A scheduled
-- job creates real monthly partitions ahead of time; the default is the safety net.
CREATE TABLE audit_events_default PARTITION OF audit_events DEFAULT;

CREATE TABLE audit_events_2026_08 PARTITION OF audit_events
    FOR VALUES FROM ('2026-08-01 00:00:00+00') TO ('2026-09-01 00:00:00+00');
CREATE TABLE audit_events_2026_09 PARTITION OF audit_events
    FOR VALUES FROM ('2026-09-01 00:00:00+00') TO ('2026-10-01 00:00:00+00');

-- ---------------------------------------------------------------------------
-- Row-level security
--
-- FORCE is required as well as ENABLE: without it the table owner bypasses the policy,
-- and forge_migrator would silently see every tenant's rows.
-- ---------------------------------------------------------------------------

ALTER TABLE audit_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit_events FORCE ROW LEVEL SECURITY;

-- current_setting(..., true) returns NULL when the GUC has never been set in this session.
-- But once SET LOCAL has run once on a pooled connection, the value at the end of the
-- transaction reverts to the EMPTY STRING, not to unset -- and ''::uuid raises
-- "invalid input syntax for type uuid" rather than yielding no rows. NULLIF collapses
-- both cases to NULL, which compares to nothing, so a missing tenant context returns zero
-- rows: it fails closed.
--
-- Without the NULLIF this looks correct in a fresh session and breaks only on a reused
-- pooled connection, which is the worst possible way to discover it.
CREATE POLICY audit_events_tenant_isolation ON audit_events
    USING (workspace_id = NULLIF(current_setting('app.workspace_id', true), '')::uuid)
    WITH CHECK (workspace_id = NULLIF(current_setting('app.workspace_id', true), '')::uuid);

-- ---------------------------------------------------------------------------
-- Grants
-- ---------------------------------------------------------------------------

-- Audit is append-only: the application can write and read history, never rewrite it.
REVOKE UPDATE, DELETE, TRUNCATE ON audit_events FROM forge_app;
REVOKE UPDATE, DELETE, TRUNCATE ON audit_events_default FROM forge_app;
REVOKE UPDATE, DELETE, TRUNCATE ON audit_events_2026_08 FROM forge_app;
REVOKE UPDATE, DELETE, TRUNCATE ON audit_events_2026_09 FROM forge_app;

GRANT SELECT, INSERT ON audit_events TO forge_app;

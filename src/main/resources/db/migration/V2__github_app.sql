-- GitHub App: installations, the repositories they expose, and the ones Forge maintains.
--
-- The split between github_repositories and managed_repositories is the schema-level
-- expression of the product's central rule: installation access is NOT consent to be
-- maintained. Availability and opt-in are different tables with different writers.

-- ---------------------------------------------------------------------------
-- Installations
-- ---------------------------------------------------------------------------

CREATE TABLE github_installations (
    id                 uuid PRIMARY KEY     DEFAULT gen_random_uuid(),
    workspace_id       uuid        NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    -- GitHub's own installation id. Globally unique, so one installation can never be
    -- bound to two workspaces — the database refuses the hijack, not just the code.
    installation_id    bigint      NOT NULL UNIQUE,
    account_login      text        NOT NULL,
    account_type       text        NOT NULL,
    account_id         bigint      NOT NULL,
    -- What GitHub actually granted. The ceiling on any token we can mint; Forge policy
    -- narrows below this but can never widen beyond it.
    permissions        jsonb       NOT NULL DEFAULT '{}'::jsonb,
    events             jsonb       NOT NULL DEFAULT '[]'::jsonb,
    repository_selection text,
    installed_by_user_id uuid REFERENCES users (id),
    suspended_at       timestamptz,
    deleted_at         timestamptz,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT github_installations_account_type_ck CHECK (account_type IN ('User', 'Organization'))
);

CREATE INDEX github_installations_workspace_idx ON github_installations (workspace_id);

-- ---------------------------------------------------------------------------
-- Repositories the installation exposes (available, not managed)
-- ---------------------------------------------------------------------------

CREATE TABLE github_repositories (
    id                     uuid PRIMARY KEY     DEFAULT gen_random_uuid(),
    workspace_id           uuid        NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    github_installation_id uuid        NOT NULL REFERENCES github_installations (id) ON DELETE CASCADE,
    github_repo_id         bigint      NOT NULL,
    full_name              text        NOT NULL,
    private                boolean     NOT NULL DEFAULT true,
    default_branch         text,
    archived               boolean     NOT NULL DEFAULT false,
    last_synced_at         timestamptz NOT NULL DEFAULT now(),
    -- Set rather than deleted: losing access must be visible, not silent.
    removed_at             timestamptz,
    created_at             timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT github_repositories_uk UNIQUE (github_installation_id, github_repo_id)
);

CREATE INDEX github_repositories_workspace_idx ON github_repositories (workspace_id);

-- ---------------------------------------------------------------------------
-- Repositories Forge has been told to maintain (explicit opt-in)
-- ---------------------------------------------------------------------------

CREATE TABLE managed_repositories (
    id                    uuid PRIMARY KEY     DEFAULT gen_random_uuid(),
    workspace_id          uuid        NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    github_repository_id  uuid        NOT NULL REFERENCES github_repositories (id) ON DELETE CASCADE,
    status                text        NOT NULL DEFAULT 'ACTIVE',
    -- The ceiling a human sets on what the agent may do here. MERGE_AUTONOMOUS does not
    -- exist: an agent must not merge its own work until there is data to justify it.
    autonomy_level        text        NOT NULL DEFAULT 'PR_WITH_APPROVAL',
    -- Declared by a human, never authored by the model. This is the definition of "done",
    -- and an agent that can edit its own grading rubric is not verifiable.
    verification_contract jsonb,
    settings              jsonb       NOT NULL DEFAULT '{}'::jsonb,
    enabled_by            uuid REFERENCES users (id),
    enabled_at            timestamptz NOT NULL DEFAULT now(),
    disabled_at           timestamptz,
    CONSTRAINT managed_repositories_uk UNIQUE (workspace_id, github_repository_id),
    CONSTRAINT managed_repositories_status_ck
        CHECK (status IN ('ACTIVE', 'PAUSED', 'ACCESS_LOST')),
    CONSTRAINT managed_repositories_autonomy_ck
        CHECK (autonomy_level IN ('OBSERVE_ONLY', 'SUGGEST', 'PR_WITH_APPROVAL', 'PR_AUTONOMOUS'))
);

CREATE INDEX managed_repositories_workspace_idx ON managed_repositories (workspace_id);

-- ---------------------------------------------------------------------------
-- Idempotency ledger for outbound GitHub mutations
--
-- GitHub's mutating endpoints are not idempotent. The dangerous case is crashing after
-- GitHub committed a change but before we recorded it; on resume, an intent row with no
-- response triggers a reconciling read rather than a blind retry. Blind retry is how you
-- end up with four identical pull requests.
-- ---------------------------------------------------------------------------

CREATE TABLE github_action_log (
    id           uuid PRIMARY KEY     DEFAULT gen_random_uuid(),
    workspace_id uuid        NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    task_id      uuid,
    action_type  text        NOT NULL,
    target_ref   text,
    fingerprint  text        NOT NULL,
    response     jsonb,
    created_at   timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz,
    CONSTRAINT github_action_log_uk UNIQUE (workspace_id, fingerprint)
);

-- ---------------------------------------------------------------------------
-- Row-level security
-- ---------------------------------------------------------------------------

ALTER TABLE github_installations ENABLE ROW LEVEL SECURITY;
ALTER TABLE github_installations FORCE ROW LEVEL SECURITY;
CREATE POLICY github_installations_tenant_isolation ON github_installations
    USING (workspace_id = current_setting('app.workspace_id', true)::uuid)
    WITH CHECK (workspace_id = current_setting('app.workspace_id', true)::uuid);

ALTER TABLE github_repositories ENABLE ROW LEVEL SECURITY;
ALTER TABLE github_repositories FORCE ROW LEVEL SECURITY;
CREATE POLICY github_repositories_tenant_isolation ON github_repositories
    USING (workspace_id = current_setting('app.workspace_id', true)::uuid)
    WITH CHECK (workspace_id = current_setting('app.workspace_id', true)::uuid);

ALTER TABLE managed_repositories ENABLE ROW LEVEL SECURITY;
ALTER TABLE managed_repositories FORCE ROW LEVEL SECURITY;
CREATE POLICY managed_repositories_tenant_isolation ON managed_repositories
    USING (workspace_id = current_setting('app.workspace_id', true)::uuid)
    WITH CHECK (workspace_id = current_setting('app.workspace_id', true)::uuid);

ALTER TABLE github_action_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE github_action_log FORCE ROW LEVEL SECURITY;
CREATE POLICY github_action_log_tenant_isolation ON github_action_log
    USING (workspace_id = current_setting('app.workspace_id', true)::uuid)
    WITH CHECK (workspace_id = current_setting('app.workspace_id', true)::uuid);

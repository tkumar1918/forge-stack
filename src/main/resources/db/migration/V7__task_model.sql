-- The task model: the mechanical substrate the whole agent runtime sits on.
--
-- Two levels, deliberately (plan §10). A *task* has a lifecycle — created, ready, running, done.
-- An *attempt* has phases — analyzing, planning, executing, verifying. Flattening them is what makes
-- the failure loop unanswerable: "we are in DIAGNOSING" begs "which attempt?", and the answer has to
-- be a row, not an inference.
--
-- No LLM anywhere in this phase. Everything here is meant to be provably correct while the only
-- things driving it are tests and fake handlers, because every guarantee above it inherits from
-- these tables.

-- ---------------------------------------------------------------------------
-- Tasks — lifecycle
-- ---------------------------------------------------------------------------

CREATE TABLE tasks (
    id                     uuid PRIMARY KEY     DEFAULT gen_random_uuid(),
    workspace_id           uuid        NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    managed_repository_id  uuid REFERENCES managed_repositories (id) ON DELETE CASCADE,
    parent_task_id         uuid REFERENCES tasks (id) ON DELETE SET NULL,

    origin                 text        NOT NULL,
    origin_signal_id       uuid,
    -- Callers supply this; a retried create returns the original task rather than a second one.
    idempotency_key        text,

    title                  text        NOT NULL,
    goal                   text        NOT NULL,
    acceptance_criteria    text,

    state                  text        NOT NULL DEFAULT 'CREATED',
    -- Time-in-state is the first question asked of a stuck system, and deriving it from the
    -- transition log means a join on the hottest query in the product.
    state_entered_at       timestamptz NOT NULL DEFAULT now(),
    terminal_reason        text,

    risk_level             text        NOT NULL DEFAULT 'MEDIUM',
    required_autonomy      text,

    priority               smallint    NOT NULL DEFAULT 100,
    scheduled_after        timestamptz,

    attempt_count          int         NOT NULL DEFAULT 0,
    max_attempts           int         NOT NULL DEFAULT 5,

    -- Micros, not floats. Money that is summed and compared against a limit must not carry binary
    -- rounding error, and a bigint of micro-dollars covers any budget this will ever hold.
    budget_usd_micros      bigint,
    consumed_usd_micros    bigint      NOT NULL DEFAULT 0,
    budget_tokens          bigint,
    consumed_tokens        bigint      NOT NULL DEFAULT 0,

    -- The durable copy of the lease. Redis holds the fast one; if Redis is lost entirely, the
    -- reconciler rebuilds every queue from these three columns. That is the property that makes
    -- losing Redis cost latency rather than correctness (plan §5).
    lease_owner            text,
    lease_epoch            bigint      NOT NULL DEFAULT 0,
    lease_expires_at       timestamptz,

    pr_number              int,
    pr_url                 text,
    head_branch            text,

    version                bigint      NOT NULL DEFAULT 0,

    created_by             uuid REFERENCES users (id),
    created_at             timestamptz NOT NULL DEFAULT now(),
    updated_at             timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT tasks_idempotency_uk UNIQUE (workspace_id, idempotency_key),

    CONSTRAINT tasks_origin_ck CHECK (origin IN ('USER', 'SIGNAL', 'SCHEDULE', 'AGENT')),

    -- The Task FSM's states, and only these. BLOCKED is deliberately absent: it collapsed three
    -- conditions with three different owners and three different resolutions into one useless
    -- bucket. RESUMED is absent because it is an event, not a state.
    CONSTRAINT tasks_state_ck CHECK (state IN (
        'CREATED', 'READY', 'BLOCKED_ON_DEPENDENCY', 'QUEUED', 'RUNNING',
        'AWAITING_HUMAN', 'AWAITING_EXTERNAL', 'SUSPENDED',
        'COMPLETED', 'FAILED', 'CANCELLED', 'ABANDONED')),

    CONSTRAINT tasks_risk_ck CHECK (risk_level IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),

    -- A terminal state must say why, and a live one must not pretend to.
    CONSTRAINT tasks_terminal_reason_ck CHECK (
        (state IN ('COMPLETED', 'FAILED', 'CANCELLED', 'ABANDONED')) OR terminal_reason IS NULL),

    CONSTRAINT tasks_budget_ck CHECK (consumed_usd_micros >= 0 AND consumed_tokens >= 0),
    CONSTRAINT tasks_attempts_ck CHECK (attempt_count >= 0 AND max_attempts > 0)
);

-- The scheduler's scan: runnable work for a workspace, most important first.
CREATE INDEX tasks_runnable_idx
    ON tasks (workspace_id, state, priority DESC, scheduled_after);

-- The reconciler's scan: whose lease has lapsed. Partial, because a task without a lease is not
-- interesting to it and most tasks have no lease at any given moment.
CREATE INDEX tasks_expired_lease_idx
    ON tasks (state, lease_expires_at)
    WHERE lease_expires_at IS NOT NULL;

CREATE INDEX tasks_repository_idx
    ON tasks (managed_repository_id)
    WHERE managed_repository_id IS NOT NULL;

-- ---------------------------------------------------------------------------
-- Transitions — the audit spine
-- ---------------------------------------------------------------------------

-- Append-only, like audit_events, and for the same reason: this is the record that answers "how did
-- this task get here", and a record that can be rewritten answers nothing. Every state change in
-- the system writes exactly one row here, in the same transaction as the change itself.
CREATE TABLE task_state_transitions (
    id            uuid PRIMARY KEY     DEFAULT gen_random_uuid(),
    task_id       uuid        NOT NULL REFERENCES tasks (id) ON DELETE CASCADE,
    workspace_id  uuid        NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    from_state    text        NOT NULL,
    to_state      text        NOT NULL,
    event         text        NOT NULL,
    actor_type    text        NOT NULL,
    actor_id      uuid,
    attempt_id    uuid,
    reason        text,
    -- What each guard decided, kept because "why was COMPLETE refused" is a question asked months
    -- later, and re-deriving it means reconstructing state that has since moved on.
    guard_results jsonb       NOT NULL DEFAULT '{}'::jsonb,
    created_at    timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT task_transitions_actor_ck
        CHECK (actor_type IN ('SYSTEM', 'SCHEDULER', 'SUPERVISOR', 'HUMAN'))
);

CREATE INDEX task_transitions_task_idx ON task_state_transitions (task_id, created_at DESC);

-- ---------------------------------------------------------------------------
-- Attempts — one coherent approach each
-- ---------------------------------------------------------------------------

CREATE TABLE task_attempts (
    id                   uuid PRIMARY KEY     DEFAULT gen_random_uuid(),
    task_id              uuid        NOT NULL REFERENCES tasks (id) ON DELETE CASCADE,
    workspace_id         uuid        NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    attempt_no           int         NOT NULL,

    phase                text        NOT NULL DEFAULT 'INITIALIZING',
    phase_entered_at     timestamptz NOT NULL DEFAULT now(),

    hypothesis           text,
    -- The anti-thrash mechanism: a normalised hash of the approach. Matching a prior *failed*
    -- attempt's fingerprint does not block the attempt, it injects that failure into context and
    -- escalates — "you already tried this" is worth more than a refusal.
    approach_fingerprint text,

    plan_id              uuid,
    sandbox_id           uuid,

    base_commit_sha      text,
    head_commit_sha      text,
    cumulative_patch_ref text,
    diff_stats           jsonb,

    outcome              text,
    failure_class        text,
    failure_summary      text,

    started_at           timestamptz NOT NULL DEFAULT now(),
    ended_at             timestamptz,

    CONSTRAINT task_attempts_no_uk UNIQUE (task_id, attempt_no),
    CONSTRAINT task_attempts_no_ck CHECK (attempt_no > 0),

    CONSTRAINT task_attempts_phase_ck CHECK (phase IN (
        'INITIALIZING', 'ANALYZING', 'PLANNING', 'EXECUTING', 'VERIFYING',
        'DIAGNOSING', 'REPLANNING', 'ESCALATING', 'SUBMITTING')),

    -- ABORTED is separated from FAILED on purpose: infrastructure dying is not the agent's fault,
    -- must not consume the retry budget, and must not pollute the "you already tried this" history.
    CONSTRAINT task_attempts_outcome_ck CHECK (outcome IS NULL OR outcome IN (
        'SUCCEEDED', 'FAILED', 'ABORTED', 'ESCALATED', 'TIMED_OUT')),

    -- An attempt is finished exactly when it has both an outcome and an end.
    CONSTRAINT task_attempts_ended_ck CHECK ((outcome IS NULL) = (ended_at IS NULL))
);

-- **The exit criterion for this step.**
--
-- Exactly one attempt in flight per task. This cannot be enforced in application logic: two workers
-- that both read "no live attempt" and both insert are each individually correct, and no amount of
-- checking before the write closes that window. Only the database can refuse the second one.
CREATE UNIQUE INDEX one_live_attempt_per_task
    ON task_attempts (task_id)
    WHERE ended_at IS NULL;

CREATE INDEX task_attempts_recent_idx ON task_attempts (task_id, attempt_no DESC);

-- Fingerprint lookups are always "has this approach failed on this task before", never a global
-- scan, so the index is scoped to the task and to failures.
CREATE INDEX task_attempts_fingerprint_idx
    ON task_attempts (task_id, approach_fingerprint)
    WHERE approach_fingerprint IS NOT NULL AND outcome = 'FAILED';

-- ---------------------------------------------------------------------------
-- Steps — the resume unit
-- ---------------------------------------------------------------------------

-- One step per LLM call and one per tool call. That grain is chosen so a crash costs at most one
-- tool call: coarser and a restart re-runs a ten-minute test suite, finer and the table drowns.
--
-- unique(attempt_id, step_no) is what makes replay idempotent — the executor recomputes step N,
-- finds it already committed, and moves to N+1.
CREATE TABLE task_steps (
    id            uuid PRIMARY KEY     DEFAULT gen_random_uuid(),
    attempt_id    uuid        NOT NULL REFERENCES task_attempts (id) ON DELETE CASCADE,
    task_id       uuid        NOT NULL REFERENCES tasks (id) ON DELETE CASCADE,
    workspace_id  uuid        NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    step_no       int         NOT NULL,

    phase         text        NOT NULL,
    kind          text        NOT NULL,
    status        text        NOT NULL DEFAULT 'RUNNING',

    started_at    timestamptz NOT NULL DEFAULT now(),
    ended_at      timestamptz,
    error_summary text,

    CONSTRAINT task_steps_no_uk UNIQUE (attempt_id, step_no),
    CONSTRAINT task_steps_no_ck CHECK (step_no > 0),
    CONSTRAINT task_steps_kind_ck
        CHECK (kind IN ('LLM_CALL', 'TOOL_CALL', 'SYSTEM', 'CHECKPOINT')),
    CONSTRAINT task_steps_status_ck
        CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED', 'SKIPPED'))
);

CREATE INDEX task_steps_attempt_idx ON task_steps (attempt_id, step_no);

-- Not partitioned yet, deliberately, against the plan's advice to set partitioning up front.
-- There are zero rows and no traffic; partitioning now would mean carrying created_at in every
-- primary key for a table whose access pattern is not yet observed. The cost the plan warns about
-- is retrofitting onto a *large live* table, so the trigger is volume: partition by month before
-- this exceeds a few million rows, using the create-and-lock-down function pattern established in
-- V6 for audit_events.

-- ---------------------------------------------------------------------------
-- Row-level security
-- ---------------------------------------------------------------------------

ALTER TABLE tasks ENABLE ROW LEVEL SECURITY;
ALTER TABLE tasks FORCE ROW LEVEL SECURITY;
CREATE POLICY tasks_tenant_isolation ON tasks
    USING (workspace_id = NULLIF(current_setting('app.workspace_id', true), '')::uuid)
    WITH CHECK (workspace_id = NULLIF(current_setting('app.workspace_id', true), '')::uuid);

ALTER TABLE task_state_transitions ENABLE ROW LEVEL SECURITY;
ALTER TABLE task_state_transitions FORCE ROW LEVEL SECURITY;
CREATE POLICY task_transitions_tenant_isolation ON task_state_transitions
    USING (workspace_id = NULLIF(current_setting('app.workspace_id', true), '')::uuid)
    WITH CHECK (workspace_id = NULLIF(current_setting('app.workspace_id', true), '')::uuid);

ALTER TABLE task_attempts ENABLE ROW LEVEL SECURITY;
ALTER TABLE task_attempts FORCE ROW LEVEL SECURITY;
CREATE POLICY task_attempts_tenant_isolation ON task_attempts
    USING (workspace_id = NULLIF(current_setting('app.workspace_id', true), '')::uuid)
    WITH CHECK (workspace_id = NULLIF(current_setting('app.workspace_id', true), '')::uuid);

ALTER TABLE task_steps ENABLE ROW LEVEL SECURITY;
ALTER TABLE task_steps FORCE ROW LEVEL SECURITY;
CREATE POLICY task_steps_tenant_isolation ON task_steps
    USING (workspace_id = NULLIF(current_setting('app.workspace_id', true), '')::uuid)
    WITH CHECK (workspace_id = NULLIF(current_setting('app.workspace_id', true), '')::uuid);

-- The transition log is history, on the same terms as audit_events: the application appends and
-- reads, and cannot rewrite. Explicit, because ALTER DEFAULT PRIVILEGES grants full DML on every
-- table the migrator creates — see the V6 header for how that silently un-did append-only once.
REVOKE UPDATE, DELETE, TRUNCATE ON task_state_transitions FROM forgestack_app;

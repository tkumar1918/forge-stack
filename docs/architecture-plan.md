# Forge — Technical Architecture Plan

## Context

`forge-backend` is currently a bare Spring Boot 4.1 / Java 25 skeleton: `ForgeApplication.java`, an empty `application.yaml`, and a dependency set (JPA, Redis, Flyway, Security + OAuth2 client/resource-server, Spring AI 2.0 OpenAI, Validation, WebMVC). There is no domain code, no schema, no module structure. Everything below is greenfield.

We are building **Forge**: a multi-tenant SaaS where a customer hands over ownership of a repository and Forge continuously maintains it — detecting work, planning, implementing, verifying, and opening PRs, escalating to a human when it must. The hard part is not "call an LLM with tools." The hard part is that engineering tasks are **long-running, failure-prone, and iterative**, which means the system must be able to crash on attempt 4 of 7, resume from a durable checkpoint, remember that attempts 1–3 already failed and why, and never let a language model declare victory on its own.

The intended outcome of this plan: a modular monolith with boundaries sharp enough that the agent runtime and the execution sandbox can be extracted into separate services later without a rewrite, and with the mechanical substrate (state machine, queue, leases, sandbox, tools) proven correct *before* any LLM is wired in.

### Decisions locked with the user

| Decision | Choice |
|---|---|
| Orchestration | Hand-rolled: Postgres state machine + transactional outbox + Redis Streams queue |
| Model strategy | Provider-agnostic `ModelRouter` port over Spring AI; OpenAI first; roles bound in config |
| v1 target | Design-partner alpha — real multi-tenancy and RLS from day one, billing/quotas/self-serve deferred |
| Sandbox | **Docker on a dedicated worker VM, behind a `SandboxProvider` port** (see §16) |

On the sandbox: the user has no Kubernetes experience and correctly declined to accept a design they cannot validate. Shipping a k8s control plane to a team that cannot debug it is a worse risk than the isolation gap. We take Docker-on-a-VM, harden it with flags that are readable in a single `docker run` line, and keep the port narrow so a stronger backend drops in later. §16 states the residual risk and the exact trigger for closing it.

---

## Challenges to the proposed architecture

The brief is unusually strong. Six things in it are wrong or under-specified, and they change the design.

**1. `Workspace → Agents` should not exist as an entity.**
There is no useful persistent "Agent" row. What actually exists is a *Task*, an *Attempt* executing it, and a *configuration* governing that execution. Modelling "Agent" as an actor is a chatbot-era instinct that leaks into everything: you end up asking "which agent owns this task", "is the agent busy", "what is the agent's state" — all of which are really task and attempt questions. Replace with **`AgentProfile`**: a named, versioned bundle of `{model routing, tool allowlist, autonomy level, budget defaults}` attached to a `ManagedRepository`. It is config, not an actor. This removes an entire class of confused state.

**2. The state machine conflates two levels, and that is why it looks awkward.**
`CREATED / QUEUED / BLOCKED / COMPLETED` are **task lifecycle**. `ANALYZING / PLANNING / EXECUTING / VERIFYING / DIAGNOSING / REPLANNING` are **phases within a single attempt**. Flattening them forces the question "we're in DIAGNOSING — which attempt?" to be answered implicitly, and makes the failure loop a set of edges rather than a bounded sub-process. Split into a **two-level FSM**: Task FSM (§10.1) and Attempt FSM (§10.2). The task is `RUNNING`; the attempt is `DIAGNOSING`.

**3. `RESUMED` is not a state.** It is an event (`RESUME`) that transitions `AWAITING_HUMAN → READY`. Adding it as a state means every human-pause path needs an extra hop that carries no information and cannot be queried usefully.

**4. `BLOCKED` conflates three unrelated conditions** with three different resolution paths, owners, and alert routes: blocked on a dependency edge (resolves when another task completes), blocked on a human (resolves when a person answers), and blocked on resources (resolves when budget resets or an operator intervenes). Split into `BLOCKED_ON_DEPENDENCY`, `AWAITING_HUMAN`, `SUSPENDED`. A single `BLOCKED` bucket makes the ops dashboard useless on day one.

**5. "Webhook events create or update tasks" is a task-storm generator.**
A busy repo emits hundreds of events an hour. Auto-creating tasks means auto-spending money on work nobody asked for. Insert a **`Signal`** entity between webhook and task: webhooks produce coalesced, deduplicated Signals; triage (rules first, LLM only for genuinely ambiguous cases) decides whether a Signal becomes a Task. In MVP the default is **`auto_create_tasks: false`** — Signals land in an inbox a human promotes. Autonomy here is earned per repo after you can measure triage precision.

**6. The dependency graph is really two graphs and must not share a table.**
The *work graph* (issue/PR/task, `BLOCKS`, human-confirmable, hundreds of edges, durable, audited) and the *code graph* (module→module, derived from static analysis, hundreds of thousands of edges, regenerable, invalidated by every commit) have nothing in common except the word "graph." One table means either the code graph pollutes scheduling decisions or the work graph inherits a regeneration lifecycle it must not have. Separate: `work_dependency` (§12) and a post-MVP `code_graph_edge` cache keyed by commit SHA.

**Two things the brief omits that dominate the design:**

- **Verification is the product** (§26 risk 1). "Tests pass" is not "the change is correct." The classic autonomous-agent failure is deleting the failing test. There must be a mechanical verification contract *and* diff guards that treat test deletion / `@Disabled` / assertion weakening as policy violations. No amount of model quality substitutes for this.
- **The sandbox must never hold a GitHub token** (§16, §17). Repository content — issue bodies, PR comments, READMEs, code comments — is attacker-controlled in the general case, and it flows into a model that has tool access. A token inside the sandbox plus egress is a one-prompt exfiltration. All git write operations are **host-brokered**.

---

## 1. High-level architecture

Three planes, one deployable artifact, three runtime roles.

```
                       ┌──────────────────────────────────────────┐
   Browser ─────────►  │  CONTROL PLANE   (role=api)              │
   GitHub webhooks ──► │  REST, OAuth login, App install,         │
                       │  webhook receipt (store-then-ack),       │
                       │  task CRUD, approvals, SSE feed          │
                       └───────────────┬──────────────────────────┘
                                       │ writes + transactional outbox
                       ┌───────────────▼──────────────────────────┐
                       │  PostgreSQL  — SOURCE OF TRUTH           │
                       │  tenancy · tasks · attempts · steps ·    │
                       │  transitions · evidence · graph ·        │
                       │  memory · audit · outbox                 │
                       └───────────────┬──────────────────────────┘
                                       │ outbox relay
                       ┌───────────────▼──────────────────────────┐
                       │  Redis — DERIVED ONLY                    │
                       │  Streams queues · leases · rate limits · │
                       │  installation-token cache · SSE pub/sub  │
                       └───────────────┬──────────────────────────┘
                                       │ consume + lease
       ┌───────────────────────────────▼──────────────────────────┐
       │  AGENT PLANE  (role=worker)                              │
       │  ┌────────────────────────────────────────────────────┐  │
       │  │ Agent Runtime — deterministic loop, NOT an LLM     │  │
       │  │  phase handler → context → model → tools →         │  │
       │  │  persist → FSM.next() → checkpoint → heartbeat     │  │
       │  └───┬──────────────┬───────────────┬─────────────────┘  │
       │      │              │               │                    │
       │  ModelRouter    ToolDispatcher   Verification            │
       │  (sup/exec)     (authz gate)     (mechanical)            │
       └──────┼──────────────┼───────────────┼────────────────────┘
              │              │               │
        OpenAI/etc     SandboxProvider   GitHub API (host-brokered)
                             │
                  ┌──────────▼───────────┐
                  │ EXECUTION PLANE      │
                  │ dedicated worker VM  │
                  │ Docker, no token,    │
                  │ egress deny-by-defer │
                  └──────────────────────┘

       SCHEDULER  (role=scheduler, single leader via Redis lock)
       runnability scan · lease reconciler · sandbox reaper ·
       budget rollups · CI polling fallback
```

**Why three roles from one artifact.** API latency and agent throughput have opposite scaling curves: the API is bursty and cheap, workers are long-running and expensive. One artifact with `forge.role=api|worker|scheduler` (Spring profiles gating `@Configuration`) gives independent scaling and independent failure domains without a distributed build, without cross-service contracts, and without losing local-dev simplicity (`role=all`).

- *Alternatives:* one process doing everything (agent loops starve HTTP threads and every deploy kills in-flight attempts); microservices from day one (three deploy pipelines and a network boundary before the domain model has stabilised — the boundaries would be wrong).
- *Why preferred:* extraction later is a build-file change plus a transport swap, because modules already talk through ports and the outbox, not through shared beans.
- *What could go wrong:* the shared artifact tempts a worker to reach into an API-only bean. Spring Modulith verification tests (§2) fail the build on that.

**Why Postgres is the only source of truth.** Every durable fact — state, transitions, attempts, evidence, cost — is a row. Redis is a cache and a transport, and **losing Redis entirely must cost latency, not correctness**: the reconciler rebuilds queue state by scanning Postgres for tasks with expired leases. This is a design property to test explicitly, not an aspiration.

---

## 2. Spring Boot module boundaries

Use **Spring Modulith**. It gives compile-time-verified package boundaries, a generated dependency diagram, and — critically — the `event_publication` registry, which is a transactional outbox we would otherwise hand-roll.

Root: `dev.tushar.forge`. Each module is a direct sub-package with a package-private internal and an explicit public API surface (`::api` named interface or a `spi` sub-package). Cross-module calls go through published interfaces or domain events. **No cross-module JPA entity references** — foreign keys exist in the database, but the Java side crosses boundaries by ID.

```
dev.tushar.forge
├── platform/                 (no domain knowledge; everything may depend on these)
│   ├── tenancy               TenantContext, RLS GUC binding, @TenantScoped
│   ├── crypto                envelope encryption, key rotation, secret refs
│   ├── events                domain event bus + outbox relay
│   ├── jobs                  queue port, Redis Streams adapter, leases, reconciler
│   ├── blob                  large-payload store (logs, prompts, diffs)
│   └── observability         MDC, metrics, tracing
│
├── iam/                      User, Identity, Workspace, Membership, Session, roles
├── githubauth/               OAuth login only. Depends on iam. No repo access. Ever.
├── githubapp/                Installations, JWT→installation tokens, repo sync, grants
├── githubapi/                Client facade: rate limiting, retry, ETag cache, idempotency
├── githubwebhook/            HMAC verify, store-then-ack, normalise → Signal
│
├── repo/                     ManagedRepository, VerificationContract, AgentProfile, snapshots
├── signal/                   Signal ingestion, coalescing, triage, inbox
├── task/                     Task aggregate, TaskStateService, transition table, guards
├── graph/                    work_dependency, cycle detection, runnability predicate
├── scheduler/                runnable scan, priority, admission control, concurrency caps
│
├── runtime/                  THE AGENT RUNTIME. Attempt loop, phase handlers, checkpoints
│   ├── attempt               Attempt/Step lifecycle, resume
│   ├── phase                 Initializing/Analyzing/Planning/Executing/Verifying/Diagnosing
│   └── escalation            deterministic escalation triggers
├── planning/                 Plan artifact, schema validation, plan-vs-actual drift
├── context/                  deterministic context assembly + token budgeting
├── llm/                      ModelRouter port, role binding, structured output, cost meter
├── tools/                    ToolDefinition, registry, authorization, dispatcher, truncation
├── sandbox/                  SandboxProvider port + docker adapter + reaper
├── verification/             contract execution, diff guards, evidence extraction
├── memory/                   long-term repo knowledge, staleness, retrieval
├── policy/                   risk classification, effective authority, approvals
├── cost/                     budgets, meters, circuit breakers
├── audit/                    append-only audit log
└── api/                      REST controllers + DTOs only. Zero business logic.
```

**Dependency rules enforced by `ApplicationModules.of(ForgeApplication.class).verify()` in a test:**

| Rule | Rationale |
|---|---|
| `runtime` must not depend on `api`, `githubwebhook`, `iam` | The runtime must be extractable as a service |
| `llm` must not depend on `task`, `runtime`, `policy` | Keeps the LLM layer a dumb transport; no business rules in prompts-adjacent code |
| `tools` may depend on `sandbox`, `githubapi`, `policy` — never on `llm` | Tools are called *by* the runtime, not by the model layer |
| `policy` must not depend on `llm` | Authority decisions must be computable without a model |
| Nothing depends on `api` | |
| `sandbox` must not depend on `githubapp` | Structural guarantee that no token path reaches the sandbox |
| No `com.github.dockerjava..` import outside `sandbox.docker` | Keeps the substrate swappable (§16) |

The `sandbox ↛ githubapp` rule is a security control expressed as a build constraint. It is worth more than a comment.

### Abstraction hygiene, enforced

Appendix A sets the rule that an abstraction must answer to a pressure that exists *today*. Left to review discipline, the Spring reflex reintroduces `XService`/`XServiceImpl` pairs within weeks. So it is enforced the same way every other boundary here is — in CI.

Introduce one annotation, in `platform`:

```java
/** A deliberate abstraction seam. Justification is mandatory and reviewed. */
@Retention(RUNTIME) @Target(TYPE)
public @interface Port {
    String value();   // why this seam exists — the pressure, in one sentence
}
```

ArchUnit rules in M0:

1. **No orphan interfaces.** Any interface under `dev.tushar.forge..` must satisfy at least one of: annotated `@Port`; a Spring Data repository; `sealed` (a sum type such as `SupervisorDirective`, not an abstraction seam); or having ≥2 implementations in `main`. Otherwise the build fails.
2. **No `Impl` suffix.** `noClasses().should().haveSimpleNameEndingWith("Impl")` — the naming convention that makes the anti-pattern feel normal.
3. **`@Port` count is visible.** The rule prints the current `@Port` inventory on every run, so growth is noticed in review rather than discovered a year later.

The starting inventory is exactly four, and each names its pressure:

| Port | Justification |
|---|---|
| `SandboxProvider` | Docker adapter + in-memory fake today; k8s/gVisor on the extraction path (§16) |
| `ModelRouter` | Provider adapter + fake; testing the loop without spending tokens (§13) |
| `JobQueue` (`platform.jobs`) | Redis Streams adapter + in-memory fake for deterministic FSM tests |
| `BlobStore` (`platform.blob`) | Object-store adapter + local filesystem fake |

Every one clears trigger 1 because the fake is a genuine second implementation the test strategy depends on. `TaskStateService`, `EffectiveAuthorityResolver`, the phase handlers, and the diff guards are **concrete classes** — they are tested directly against a real Postgres via Testcontainers, so no seam is needed. If someone later wants an interface on one of them, rule 1 forces them to add `@Port` with a written justification, which turns a reflex into a decision.

- *What could go wrong:* the rule fights a legitimate case — say a third-party library requires an interface. Then `@Port("required by X")` is the correct, honest escape hatch. The goal is not to prevent interfaces; it is to prevent *unconsidered* ones.

**Why Modulith over Maven/Gradle multi-module.** Multi-module gives harder enforcement but forces the boundaries to be final before we know them, and makes cross-cutting refactors expensive during the phase where they are most frequent. Modulith enforces the same rules in a test, at a tenth of the friction, and the promotion path (Modulith package → Gradle subproject → service) is mechanical.
- *What could go wrong:* Modulith on Boot 4.1 may lag. If the verification API is unstable, fall back to ArchUnit rules expressing the same table — same enforcement, more hand-written.

---

## 3. Domain model

Seven aggregates. Aggregate = transactional consistency boundary; anything crossing one is an event or an ID reference.

| Aggregate | Root | Contains | Invariant it protects |
|---|---|---|---|
| **Workspace** | `Workspace` | Members, AgentProfiles, Budgets | Every tenant row hangs off exactly one workspace |
| **Installation** | `GithubInstallation` | available repos, granted permissions | Forge's GitHub authority is bounded by what was installed |
| **ManagedRepository** | `ManagedRepository` | VerificationContract, settings, snapshots | A repo is only maintained after explicit opt-in |
| **Task** | `Task` | Attempts, Steps, ToolCalls, Results, Evidence, Transitions, Plans | Exactly one attempt in flight; state changes only via the FSM |
| **DependencyGraph** | per-workspace | work_dependency edges | No cycles among confirmed blocking edges |
| **RepoKnowledge** | per-repo | knowledge items | Knowledge is SHA-stamped and supersedable, never silently mutated |
| **Signal** | `Signal` | raw deliveries, dedup key | Webhook noise is coalesced before it can cost money |

**Task is deliberately a large aggregate.** Attempts, steps, tool calls, and evidence are written together, read together, and are meaningless apart. Splitting them would mean coordinating four transactions on a crash-resume path — exactly where correctness matters most.
- *What could go wrong:* the Task aggregate becomes a write hotspot on a very active repo. Mitigated because writes are serialised per task anyway by the single-attempt lease, so contention is bounded by concurrent *tasks*, not concurrent writers per task.

**Key entities beyond the obvious:**

- `AgentProfile` — replaces the brief's "Agent". Versioned config: model role bindings, tool allowlist, autonomy level, budget defaults, escalation thresholds. Attached to a ManagedRepository, resolvable to workspace default.
- `VerificationContract` — per repo: setup command, build command, test command, lint command, allowlisted binaries, expected duration, coverage baseline. **Declared by the human, not inferred by the model.** This is the definition of "done" and the model must not author it.
- `Signal` — the coalesced, deduplicated unit of "something happened."
- `Plan` — a versioned, schema-validated artifact with declared file scope and required authority. Plan-vs-actual drift is an escalation trigger.
- `Evidence` — a first-class row, not a log line. Test results, diffs, CI statuses, and error excerpts that justified a decision. This is what gets replayed to the model instead of raw history.
- `HumanIntervention` — an open question bound to a specific plan version and diff SHA.
- `FailureClass` — enum on attempt outcome, drives retry vs terminal (§20).

---

## 4. PostgreSQL schema design

Conventions across all tables: `id uuid primary key default gen_random_uuid()`, `created_at timestamptz not null default now()`, tenant tables carry `workspace_id uuid not null` and have RLS enabled. JSONB for genuinely open-shaped payloads (webhook bodies, tool arguments, plan bodies, policy rules) — **not** for anything queried in a hot path or needing a foreign key. Large blobs (build logs, full prompts, full diffs) go to object storage with a `*_ref` column; only summaries live in Postgres.

Migrations are Flyway, `V{n}__{desc}.sql`, forward-only. No Hibernate DDL, ever (`ddl-auto: validate`).

### 4.1 Tenancy & identity

```sql
users(id, primary_email citext unique, display_name, avatar_url, created_at, deleted_at)

user_identities(id, user_id → users, provider text, provider_user_id text,
                provider_login text, created_at,
                unique(provider, provider_user_id))
  -- deliberately no token column; see §6

workspaces(id, slug citext unique, name, plan, status, created_at, deleted_at)

workspace_members(workspace_id, user_id, role, invited_by, created_at,
                  primary key(workspace_id, user_id))
  -- role: OWNER | ADMIN | MAINTAINER | VIEWER

sessions(id, user_id, workspace_id, token_hash bytea unique, user_agent, ip inet,
         created_at, last_seen_at, expires_at, revoked_at)
```

### 4.2 GitHub

```sql
github_installations(id, workspace_id, installation_id bigint unique,
                     account_login, account_type, account_id bigint,
                     permissions jsonb, events jsonb,
                     installed_by_user_id, suspended_at, deleted_at, created_at)

github_repositories(id, github_installation_id, github_repo_id bigint,
                    full_name, private bool, default_branch, archived bool,
                    last_synced_at, removed_at,
                    unique(github_installation_id, github_repo_id))

managed_repositories(id, workspace_id, github_repository_id,
                     status,                       -- ACTIVE|PAUSED|ACCESS_LOST
                     autonomy_level,
                     agent_profile_id,
                     verification_contract jsonb,
                     settings jsonb,
                     enabled_by, enabled_at,
                     unique(workspace_id, github_repository_id))

github_action_log(id, workspace_id, task_id, action_type, target_ref,
                  fingerprint text, response jsonb, created_at,
                  unique(workspace_id, fingerprint))
  -- idempotency ledger for every outbound GitHub mutation (§21)
```

The `github_repositories` / `managed_repositories` split is the schema-level expression of the brief's core rule: installation access ≠ Forge maintenance. Availability and opt-in are different tables with different writers.

### 4.3 Signals

```sql
webhook_deliveries(id, delivery_guid text unique, event_type, action,
                   installation_id bigint, repo_github_id bigint,
                   payload jsonb, signature_valid bool,
                   received_at, processed_at, status, attempts int, error text)
  -- append-only, raw, monthly partitions, 90-day retention

signals(id, workspace_id, managed_repository_id, kind, severity,
        dedup_key text, external_ref text, summary, payload jsonb,
        status,                       -- NEW|TRIAGED|PROMOTED|IGNORED|SUPERSEDED
        occurrence_count int default 1, first_seen_at, last_seen_at,
        promoted_task_id, triage_reason,
        unique(workspace_id, dedup_key) where status in ('NEW','TRIAGED'))
```

The partial unique index on `dedup_key` is the coalescing mechanism: ten CI failures on the same SHA increment `occurrence_count` on one row rather than creating ten Signals.

### 4.4 Task, attempt, step (detailed in §11)

```sql
tasks(id, workspace_id, managed_repository_id, parent_task_id,
      origin,                       -- USER|SIGNAL|SCHEDULE|AGENT
      origin_signal_id, idempotency_key,
      title, goal text, acceptance_criteria text,
      state, state_entered_at, terminal_reason,
      risk_level, required_autonomy,
      priority smallint, scheduled_after timestamptz,
      attempt_count int default 0, max_attempts int default 5,
      budget_usd_micros bigint, consumed_usd_micros bigint default 0,
      budget_tokens bigint, consumed_tokens bigint default 0,
      lease_owner text, lease_epoch bigint default 0, lease_expires_at timestamptz,
      pr_number int, pr_url, head_branch,
      version bigint default 0,     -- optimistic lock
      created_by, created_at, updated_at,
      unique(workspace_id, idempotency_key))

task_state_transitions(id, task_id, workspace_id, from_state, to_state, event,
                       actor_type,                 -- SYSTEM|SCHEDULER|SUPERVISOR|HUMAN
                       actor_id, attempt_id, reason, guard_results jsonb, created_at)
  -- append-only; the audit spine of the whole system

task_attempts(id, task_id, workspace_id, attempt_no int,
              phase, phase_entered_at,
              hypothesis text, approach_fingerprint text,
              plan_id, sandbox_id,
              base_commit_sha, head_commit_sha, cumulative_patch_ref,
              diff_stats jsonb,
              outcome,                -- SUCCEEDED|FAILED|ABORTED|ESCALATED|TIMED_OUT
              failure_class, failure_summary text,
              started_at, ended_at,
              unique(task_id, attempt_no))

create unique index one_live_attempt_per_task
  on task_attempts(task_id) where ended_at is null;
```

That partial unique index is the hard database guarantee behind "exactly one attempt in flight." It is not enforceable by application logic under concurrency; it must be a constraint.

```sql
task_steps(id, attempt_id, task_id, workspace_id, step_no int,
           phase, kind,               -- LLM_CALL|TOOL_CALL|SYSTEM|CHECKPOINT
           status, started_at, ended_at, error_summary,
           unique(attempt_id, step_no))

tool_calls(id, step_id, attempt_id, workspace_id, tool_name,
           arguments jsonb, args_hash text, idempotency_key text unique,
           authorized bool, policy_decision jsonb,
           risk_level, started_at)

tool_results(id, tool_call_id unique, status, exit_code,
             summary text,            -- what the model sees
             output_ref text,         -- full output in blob storage
             output_bytes bigint, truncated bool, duration_ms int)

evidence(id, workspace_id, task_id, attempt_id, source_tool_call_id,
         kind,                        -- TEST_RESULT|BUILD_LOG|DIFF|CI_STATUS|
                                      -- FILE_SNIPPET|ERROR|METRIC|OBSERVATION
         title, summary text, structured jsonb, blob_ref,
         significance smallint, created_at)

plans(id, task_id, attempt_id, workspace_id, version int,
      status, authored_by_model, rationale text,
      declared_file_scope jsonb, required_authority jsonb,
      risk_assessment jsonb, steps jsonb, created_at,
      unique(task_id, version))

llm_invocations(id, workspace_id, task_id, attempt_id, step_id,
                role,                 -- SUPERVISOR|EXECUTOR|UTILITY
                provider, model, purpose,
                prompt_tokens, cached_prompt_tokens, completion_tokens,
                reasoning_tokens, cost_usd_micros bigint,
                latency_ms, finish_reason, retry_of,
                request_ref, response_ref, created_at)

human_interventions(id, workspace_id, task_id, attempt_id,
                    reason, question text, options jsonb,
                    bound_plan_version int, bound_diff_sha text,
                    requested_at, expires_at,
                    responded_at, responder_user_id, decision, decision_note)
```

`bound_plan_version` + `bound_diff_sha` close a real TOCTOU hole: a human approves a diff, the agent keeps working, and the merged change is not what was approved. If either binding no longer matches at execution time, approval is void and must be re-requested.

### 4.5 Graph, memory, policy, audit

```sql
work_dependencies(id, workspace_id,
                  from_type, from_id, to_type, to_id,   -- TASK|ISSUE|PR|DECISION|EXTERNAL
                  relation,      -- BLOCKS|RELATES_TO|DUPLICATES|SUPERSEDES|REQUIRES_DECISION
                  status,        -- INFERRED|CONFIRMED|REJECTED
                  confidence numeric(3,2), source, provenance jsonb,
                  created_by_task_id, confirmed_by, confirmed_at, created_at,
                  unique(workspace_id, from_type, from_id, to_type, to_id, relation))

repo_snapshots(id, managed_repository_id, commit_sha, languages jsonb,
               build_system, module_map jsonb, test_layout jsonb,
               entrypoints jsonb, analyzed_at, unique(managed_repository_id, commit_sha))

repo_knowledge(id, workspace_id, managed_repository_id,
               kind,            -- ARCHITECTURE|CONVENTION|CONSTRAINT|MODULE|
                                -- INCIDENT|DECISION|GOTCHA
               key text, title, body text, structured jsonb,
               confidence numeric(3,2), source, observed_at_sha,
               supporting_task_id, supersedes_id, superseded_at,
               valid_from, created_at)

agent_policies(id, workspace_id, managed_repository_id null, name,
               autonomy_level, rules jsonb, version int, active bool, created_at)

budgets(id, workspace_id, scope, scope_id, period,
        limit_usd_micros bigint, consumed_usd_micros bigint,
        hard_stop bool, period_start, period_end)

sandboxes(id, workspace_id, task_id, attempt_id, provider, external_id,
          image, cpu_limit, memory_limit_mb, status,
          created_at, expires_at, destroyed_at, destroy_reason)

audit_events(id, workspace_id, actor_type, actor_id, action,
             resource_type, resource_id, task_id, risk_level,
             before jsonb, after jsonb, ip inet, user_agent,
             request_id, created_at)
  -- monthly partitions; app role has INSERT+SELECT only, no UPDATE/DELETE grant
```

**Key indexes.** `tasks(workspace_id, state, priority desc, scheduled_after)` for the scheduler scan; `tasks(state, lease_expires_at) where lease_expires_at is not null` for the reconciler; `task_attempts(task_id, attempt_no desc)`; `evidence(task_id, kind, created_at desc)`; `work_dependencies(workspace_id, to_type, to_id) where status='CONFIRMED' and relation='BLOCKS'` for the runnability check; `llm_invocations(workspace_id, created_at)` BRIN for cost rollups.

**Partitioning.** `audit_events`, `webhook_deliveries`, `llm_invocations`, and eventually `task_steps` / `tool_calls` by month. These grow without bound and are almost always queried by recency. Set this up in the initial migration — retrofitting partitioning onto a live table is miserable.

- *Alternative considered:* a graph database (Neo4j) for the dependency graph. Rejected — edge count is in the hundreds per workspace, recursive CTEs handle it comfortably, and a second datastore breaks "Postgres is the source of truth" and transactional consistency between edges and tasks. Revisit only if the code graph becomes a product surface.
- *Alternative considered:* event-sourcing the Task aggregate. Rejected — we already get the audit property from the append-only transition and step tables, and event sourcing would make the "what is the current state, right now, under contention" query (the hottest one in the system) the hardest one.
- *What could go wrong:* JSONB creep. Guard with a rule: if you `WHERE` on it more than once, it becomes a column.

---

## 5. Redis responsibilities

Redis is **strictly derived state**. Nothing in Redis is unrecoverable from Postgres.

| # | Responsibility | Mechanism | Recovery if lost |
|---|---|---|---|
| 1 | Work queues | Redis Streams + consumer groups: `forge:q:agent:{p0,p1,p2}`, `forge:q:signal`, `forge:q:github` | Reconciler re-enqueues from `tasks` where state=QUEUED or lease expired |
| 2 | Task leases | `forge:lease:task:{id}` with owner + epoch, TTL, heartbeat renew | `tasks.lease_expires_at` is the durable copy; reconciler reclaims |
| 3 | Per-repo concurrency | Semaphore `forge:sem:repo:{id}` | Rebuilt from live attempts; bounded over-admission for one cycle |
| 4 | Rate limiting | Token buckets per installation (GitHub) and per workspace (LLM TPM/concurrency) | Buckets refill; worst case a brief 429 storm |
| 5 | Installation-token cache | `forge:ghtok:{installation}:{scope_hash}`, encrypted, TTL 50 min (tokens live 60) | Re-mint from app JWT |
| 6 | Webhook dedup fast path | `SET NX` on delivery GUID, 24h TTL | `webhook_deliveries.delivery_guid` unique index is the real guarantee |
| 7 | SSE fan-out | Pub/Sub channel per task for live UI | Client reconnects and refetches from Postgres |
| 8 | Scheduler leader election | ShedLock-style lock on `forge:leader:scheduler` | Next tick re-elects |

**Why Redis Streams over alternatives.** Consumer groups give at-least-once delivery with explicit `XACK`, and the pending-entries list makes stuck consumers *visible* — you can query which worker has held which message for how long, which is exactly the observability we need for multi-hour tasks. Lists (`BLPOP`) lose in-flight messages when a worker dies. Pub/Sub is fire-and-forget. Kafka is the technically correct answer at 100× our volume and the wrong answer now: an extra cluster to operate for a system that already treats the queue as disposable. Postgres-only queueing (`SELECT ... FOR UPDATE SKIP LOCKED`) is a genuinely reasonable alternative and worth keeping in the back pocket — we choose Redis because we need it anyway for leases, rate limits, and token caching, so it is not incremental operational surface.

**The rule that matters:** enqueue happens via the **transactional outbox**, never directly from business code. A task transitions to `QUEUED` and an outbox row is written *in the same transaction*; a relay publishes to Redis afterwards. Writing to Redis inside the transaction means a rollback leaves a phantom queue entry; writing after commit without an outbox means a crash between the two loses the work silently.

- *What could go wrong:* the outbox relay lags and tasks sit. Alert on outbox age, not just queue depth. And because the relay is at-least-once, every consumer must be idempotent (§21) — a duplicate enqueue must be harmless.
- *What could go wrong:* someone caches a business decision in Redis and it becomes load-bearing. Enforce by review: if losing it changes an outcome rather than a latency, it belongs in Postgres.

---

## 6. GitHub OAuth authentication architecture

**Purpose: identify a human. Nothing else.**

Spring Security `oauth2Login()` (the `oauth2-client` starter, not resource-server) with GitHub as the provider.

```
Browser → GET /oauth2/authorization/github
        → GitHub consent (scopes: read:user, user:email)
        → GET /login/oauth2/code/github?code=…&state=…
        → ForgeOAuth2UserService:
             exchange code → GitHub user profile
             upsert user_identities(provider='github', provider_user_id=…)
             find-or-create users row
             resolve workspace membership
        → issue Forge session cookie
        → redirect to app
```

**Scopes are exactly `read:user` and `user:email`.** Not `repo`. Not `read:org` unless and until we need to list installable orgs, and even then it is a separate incremental-consent step, not part of login. This is the concrete enforcement of the brief's rule that logging in grants the agent nothing.

**No GitHub user token is persisted.** The access token from the login exchange is used once to fetch the profile and then discarded. There is deliberately no token column on `user_identities` (§4.1).
- *Why:* a stored user OAuth token is a standing credential that can act as that human on GitHub. Every stored token is a breach amplifier and a compliance question. The agent does not need it — the agent uses installation tokens (§7). Login does not need it after the profile fetch.
- *Consequence:* any UI that needs "which orgs can I install into" does an on-demand incremental-consent flow rather than reading a cached token. Slightly worse UX, dramatically smaller blast radius. Correct trade.

**Sessions: opaque server-side, not JWT.**
- Cookie: `HttpOnly; Secure; SameSite=Lax; Path=/`, value = 256-bit random; only the SHA-256 hash is stored (`sessions.token_hash`).
- Hot path validated against Redis; `sessions` in Postgres is the durable record and the revocation surface.
- *Alternative:* stateless JWT. Rejected — we must revoke instantly when a member is removed from a workspace or a session is compromised, and workspace/role claims embedded in a JWT go stale exactly when staleness is dangerous. Session lookup is one Redis GET.
- *What could go wrong:* Redis eviction logs everyone out. Mitigate with a Postgres fallback read on cache miss.

**Workspace selection.** A user may belong to several workspaces. The active workspace lives on the session, and every request resolves `TenantContext` from it — never from a client-supplied header or path parameter alone. If a path contains a workspace ID, it must be *checked against* membership, not trusted.

**Other controls:** PKCE + `state` (Spring handles both); `Strict-Transport-Security`; CSRF tokens on all state-changing endpoints; session fixation rotation on login; absolute + idle expiry; audit every login, logout, workspace switch, and failed attempt.

---

## 7. GitHub App installation / authorization architecture

**Purpose: give the agent bounded, per-repository, short-lived authority.**

### Requested app permissions (least privilege)

| Permission | Level | Why |
|---|---|---|
| Metadata | Read | Mandatory |
| Contents | Read & write | Clone, branch, commit |
| Pull requests | Read & write | Open PRs, comment |
| Issues | Read & write | Read work, comment status |
| Checks | Read | Observe CI |
| Commit statuses | Read | Observe CI |
| Actions | Read | Read workflow run logs |

**Deliberately not requested:** Administration, Members, Organization anything, Secrets, Environments, Deployments, Packages, and — importantly — **Workflows: write**. Without it the agent physically cannot modify `.github/workflows`. That is intentional: CI configuration is the mechanism by which we verify the agent's work, and an agent able to edit its own grading rubric is an unacceptable design. If a workflow change is genuinely needed, that is a human task. This single omission removes an entire class of self-authorizing failure.

### Installation binding — and the hijack it prevents

```
User clicks "Connect repositories"
  → redirect to https://github.com/apps/forge/installations/new?state={signed_nonce}
  → GitHub install UI: user picks account + specific repositories
  → callback /github/setup?installation_id=…&setup_action=install&state=…
  → VERIFY:
      1. state nonce valid, unexpired, bound to this session
      2. GET /app/installations/{id} with app JWT  (authoritative, not the query param)
      3. the current user is an admin/owner of that installation's account
      4. installation not already bound to a different workspace
  → bind github_installations row to workspace, sync repo list, audit
```

Step 3 is not optional. Without it, an attacker who learns any `installation_id` (they are sequential-ish and leak in logs) can hit the callback and bind a stranger's installation into their own workspace, gaining agent access to repositories they do not own. Never trust `installation_id` from a query parameter as proof of anything.

### Token minting — where "effective authority" becomes real

```
App private key (KMS / sealed secret; never in the repo or a plain env var)
  → RS256 JWT, 10-minute expiry, iss = app id
  → POST /app/installations/{id}/access_tokens
       { repositories: ["one-repo"], permissions: {contents:"write", pull_requests:"write"} }
  → installation token, ≤60 min, cached in Redis for 50 min under a scope hash
```

GitHub lets the token request **narrow** both the repository set and the permission set below what was installed. We exploit this per task: a docs-only task receives a token scoped to one repository with `contents: write` and nothing else. A read-only analysis task receives `contents: read`. This is the concrete implementation of the brief's equation:

```
installed permissions  ∩  workspace/repo policy  ∩  task risk ceiling  ∩  granted approvals
   = minted token scope = effective authority
```

Authority is not a runtime check the model could argue its way past — it is the shape of the credential that exists. `TokenScopeResolver` computes it, `githubapp` mints it, and the token never leaves the control plane (§16).

### Lifecycle

`installation_repositories` and `installation` webhooks keep state in sync. Repo removed → set `github_repositories.removed_at`, move any `managed_repositories` to `ACCESS_LOST`, and move its live tasks to `SUSPENDED` with a clear reason. App suspended or uninstalled → `suspended_at` / `deleted_at`, suspend all tasks, retain data per retention policy, surface prominently in the UI. **Silent failure here is the worst outcome** — a customer must never discover that Forge quietly stopped maintaining a repo three weeks ago.

- *Alternative considered:* a single broad installation token cached per installation. Rejected — one leak exposes every repo, and it makes risk-based scoping impossible.
- *What could go wrong:* token minting becomes a rate-limit bottleneck (per-installation limits apply). Mitigate via the scope-hash cache: identical scopes reuse one token, and scopes are coarse enough (per repo × per permission-set) to cache well.
- *What could go wrong:* clock skew invalidates the app JWT. Use a short skew allowance and NTP on hosts.

---

## 8. GitHub webhook architecture

Three stages, deliberately separated: **receive** (fast, dumb, durable) → **normalise** (deduplicate, coalesce) → **triage** (decide whether it is work).

### Stage 1 — Receive (`POST /api/webhooks/github`, `permitAll`)

1. Reject bodies over 5 MB before reading fully.
2. Verify `X-Hub-Signature-256` HMAC-SHA256 over the **raw** body with a constant-time compare. Requires capturing the raw bytes before Jackson touches them (`ContentCachingRequestWrapper`); re-serialising and re-signing is a classic and fatal bug.
3. `INSERT INTO webhook_deliveries` — one small transaction. Unique violation on `delivery_guid` → return 200 and stop (GitHub retries; duplicates must be free).
4. Write an outbox row. **Return 202.**

GitHub times out at ~10 seconds and retries. Any processing inline is a correctness bug waiting for a slow day. The endpoint does nothing but verify, persist, and acknowledge.

### Stage 2 — Normalise → Signal

An async consumer maps raw events onto Signals with a deterministic `dedup_key`:

| Event | Signal kind | `dedup_key` shape |
|---|---|---|
| `check_run` / `workflow_run` failed | `CI_FAILED` | `repo:{id}:ci_failed:{sha}` |
| `issues` opened/labelled | `ISSUE_OPENED` | `repo:{id}:issue:{number}` |
| `pull_request_review` changes requested | `REVIEW_REQUESTED_CHANGES` | `repo:{id}:pr:{number}:review` |
| `push` to default branch | `CODE_CHANGED` | `repo:{id}:push:{sha}` |
| Dependabot alert | `SECURITY_ALERT` | `repo:{id}:alert:{id}` |

Coalescing: a repeat `dedup_key` on an open Signal increments `occurrence_count` and bumps `last_seen_at`. Five retried CI runs on one commit are one Signal.

**Three rules that prevent well-known disasters:**
- **Loop prevention.** Drop any event whose `sender.id` is Forge's own bot user. Otherwise: Forge opens a PR → `pull_request.opened` → Signal → Task → PR → forever. Check this first, before anything else.
- **No ordering assumptions.** GitHub does not guarantee webhook order and redelivers freely. Never mutate state purely from payload contents; on acting, **refetch current state from the API** and reconcile. The webhook is a hint that something changed, not a description of the world.
- **Events for unmanaged repos are recorded and dropped.** Installation access is not consent to be maintained.

### Stage 3 — Triage

Rules engine first, deterministic and free:

```
CI_FAILED on a PR whose head branch is owned by an existing Forge task
    → resume that task (AWAITING_EXTERNAL → READY). No LLM.
REVIEW_REQUESTED_CHANGES on a Forge PR
    → resume the owning task with the review as evidence. No LLM.
SECURITY_ALERT + repo policy auto_security = true
    → create task at risk MEDIUM.
ISSUE_OPENED with a configured label (e.g. "forge")
    → create task.
everything else
    → Signal inbox, status NEW, awaiting human promotion.
```

Only genuinely ambiguous Signals reach a cheap **utility** model, and it produces a *recommendation* with confidence, never a task. `auto_create_tasks` defaults to **false** per repo in MVP.

- *Why:* the highest-value webhook behaviours (resume on CI failure, resume on review) are pure rules. Reaching for an LLM here spends money and adds nondeterminism to the one part of the pipeline that can be exactly right.
- *What could go wrong:* the inbox becomes a graveyard nobody triages. That is a *product* signal worth having — it tells you which Signal kinds are worth automating, with real data.
- *What could go wrong:* a webhook delivery is missed entirely (GitHub outage, our downtime). Mitigate with a scheduler reconciliation pass that polls open Forge PRs and their check status on an interval. Webhooks are an optimisation over polling, never the only path.

---

## 9. Agent runtime architecture

**The runtime is an ordinary deterministic Java loop. It is not an LLM, and it is not "the AI service."** The model is a function the loop calls to make one bounded decision at a time. The loop owns control flow, persistence, budgets, authorization, and termination.

### The loop

```java
// AttemptExecutor — conceptual
Lease lease = leases.acquire(taskId, workerId);          // fenced, epoch-stamped
Attempt attempt = attempts.openOrResume(task);           // resumes at last checkpoint

while (!attempt.isTerminal()) {
    guards.check(lease, budget, deadline, cancellation);  // may throw → clean exit

    PhaseHandler handler   = handlers.forPhase(attempt.phase());
    AgentContext ctx       = contextAssembler.build(task, attempt, attempt.phase());
    PhaseOutcome outcome   = handler.run(ctx);            // may call model + tools

    tx(() -> {
        steps.persist(outcome.steps());                   // steps, tool calls, results
        evidence.record(outcome.evidence());
        attempt.setPhase(phaseFsm.next(attempt.phase(), outcome));  // RUNTIME decides
        checkpoints.write(attempt);                       // durable resume point
    });

    lease.heartbeat();
}

taskStateService.apply(task, attempt.toTaskEvent(), SYSTEM);
sandboxes.destroy(attempt.sandboxId());
```

Four properties this shape buys:

1. **Resumability.** Every iteration ends in a committed checkpoint. A worker killed mid-deploy resumes at the last completed step, not at attempt start. No re-running an expensive test suite because a pod restarted.
2. **The model cannot control flow.** `phaseFsm.next()` is a pure function of the phase and the *structured* outcome. A model that emits "task complete!" produces a `PhaseOutcome` the FSM evaluates against guards; it does not produce a transition.
3. **Bounded everything.** Steps per phase, tool calls per step, tokens per attempt, wall clock per attempt, attempts per task. Every loop has a ceiling; an unbounded agent loop is an unbounded invoice.
4. **Observable.** One row per step, one span per phase. "What is Forge doing right now" is a query, not a log grep.

### Phase handlers

| Phase | Model role | Does | Produces |
|---|---|---|---|
| `INITIALIZING` | none | Provision sandbox, clone at base SHA, load memory + prior attempts, probe repo | `RepoSnapshot`, sandbox handle |
| `ANALYZING` | executor (+1 supervisor summary) | Read-only tools: grep, read, list, git log | `RepoUnderstanding`, evidence |
| `PLANNING` | **supervisor** | Structured plan with declared file scope + required authority | `Plan` v1 (schema-validated) |
| `EXECUTING` | executor | Bounded tool loop over plan steps | Diff, tool results, evidence |
| `VERIFYING` | **none for the verdict** | Run verification contract + diff guards | Pass/fail + evidence |
| `DIAGNOSING` | executor → supervisor on repeat | Classify failure against history | `FailureClass`, hypothesis |
| `REPLANNING` | **supervisor only** | Revise plan given what failed | `Plan` v(n+1) |
| `SUBMITTING` | none | Host-brokered commit/push/PR, idempotent | PR number |

Note `VERIFYING` has no model in the decision path. The model may *summarise* a failure; it may not *decide* that verification passed. (§10.3, §26)

### Escalation — deterministic, runtime-owned

The runtime decides when to spend supervisor tokens. Triggers are computed from persisted facts, never from the model saying it is stuck:

```
attempt.failure_class repeats twice in a row
approach_fingerprint matches a prior failed attempt   ← "you already tried this"
verification worse than baseline (new failures introduced)
plan drift: files touched ∉ plan.declared_file_scope
risk_level ≥ policy escalation threshold
consumed_tokens > 60% of attempt budget with no passing verification
zero diff progress across two consecutive attempts
explicit request_human_decision tool call
```

- *Alternative considered:* let the supervisor run every iteration. Rejected — 10–30× cost for marginal benefit on mechanical steps.
- *Alternative considered:* let the executor decide when to escalate. Rejected — a stuck model is exactly the model least able to notice it is stuck. Escalation must be judged from outside.
- *What could go wrong:* thresholds are wrong at first. Make them `AgentProfile` config, not constants, and instrument escalation rate per repo from day one.

### Why not an "agentic framework"

We are not adopting LangChain4j-style autonomous agent orchestration, and we are not using Spring AI's higher-level agent abstractions for the loop. Those frameworks own control flow, and control flow — checkpointing, leasing, budgets, the FSM, authorization — *is* the product. We use Spring AI only for the narrow job of "call a model with tools and get structured output back," behind our own port (§13).
- *What could go wrong:* we rebuild framework features. Accepted; they are ~600 lines and we need them to behave exactly our way.

---

## 10. Strict state machine

Two levels (per the challenge in the preamble). The Task FSM is **lifecycle**; the Attempt FSM is **phases inside one attempt**. A task is `RUNNING` while an attempt is `DIAGNOSING`.

### 10.1 Task FSM (lifecycle)

```
                      ┌──────────┐
                      │ CREATED  │
                      └────┬─────┘
                           │ ADMIT
                  ┌────────▼─────────┐   deps unmet    ┌────────────────────────┐
                  │      READY       │◄───────────────►│ BLOCKED_ON_DEPENDENCY  │
                  └────────┬─────────┘   DEP_RESOLVED  └────────────────────────┘
                           │ ENQUEUE
                  ┌────────▼─────────┐
                  │     QUEUED       │
                  └────────┬─────────┘
                           │ CLAIM (lease acquired)
                  ┌────────▼─────────┐
        ┌────────►│     RUNNING      │◄───────────┐
        │         └────┬──┬──┬───┬───┘            │
        │              │  │  │   │                │
        │      ATTEMPT_│  │  │   │ESCALATE_HUMAN  │ RESUME
        │      FAILED  │  │  │   ▼                │
        │      (retry) │  │  │  ┌──────────────┐  │
        └──────────────┘  │  │  │AWAITING_HUMAN├──┘
                          │  │  └──────┬───────┘
       PR opened, CI/review│  │         │ REJECT / TIMEOUT
                  ┌───────▼──┴──────┐  │
                  │AWAITING_EXTERNAL│  │
                  └───────┬─────────┘  │
              EXTERNAL_OK │  │ EXTERNAL_FAILED → RUNNING
                          │  └────────────────────┐
                          ▼                       ▼
   ┌───────────┐   ┌───────────┐   ┌──────────┐  ┌───────────┐   ┌───────────┐
   │ COMPLETED │   │  FAILED   │   │CANCELLED │  │ ABANDONED │   │ SUSPENDED │
   └───────────┘   └───────────┘   └──────────┘  └───────────┘   └─────┬─────┘
        terminal        terminal      terminal      terminal            │ UNSUSPEND
                                                                        └──► READY
```

| State | Meaning | Exits via |
|---|---|---|
| `CREATED` | Exists, not admitted (budget/policy unchecked) | `ADMIT` |
| `READY` | All blocking deps satisfied; awaiting capacity | `ENQUEUE` |
| `BLOCKED_ON_DEPENDENCY` | A confirmed `BLOCKS` edge is unsatisfied | `DEP_RESOLVED` |
| `QUEUED` | On a Redis stream, unclaimed | `CLAIM`, or reconciler requeue |
| `RUNNING` | An attempt is in flight (Attempt FSM active) | attempt terminal outcome |
| `AWAITING_HUMAN` | Open `human_interventions` row | `RESUME` / `REJECT` / `TIMEOUT` |
| `AWAITING_EXTERNAL` | PR open, waiting on CI or review | webhook or polling reconciler |
| `SUSPENDED` | Budget exhausted, workspace paused, or access lost | `UNSUSPEND` (operator/period reset) |
| `COMPLETED` | PR merged, or human explicitly accepted | — |
| `FAILED` | Deterministically unachievable | — |
| `CANCELLED` | Human cancelled | — |
| `ABANDONED` | Attempt cap hit without success (distinct from FAILED: *we* gave up, the task may still be valid) | — |

`ABANDONED` vs `FAILED` matters operationally: `FAILED` means stop asking, `ABANDONED` means a human should look — different dashboards, different follow-up.

**`COMPLETED` requires a merged PR or explicit human acceptance, not an opened PR.** An autonomous maintainer whose success metric is "PR opened" optimises for PR volume, which is exactly the failure mode we are trying to avoid. `AWAITING_EXTERNAL` is where PR-open tasks live, and CI failures pull them back into `RUNNING` automatically.

### 10.2 Attempt FSM (phases within `RUNNING`)

```
INITIALIZING → ANALYZING → PLANNING → EXECUTING → VERIFYING
                               ▲          ▲           │
                               │          │      pass │ fail
                               │          │           │
                          REPLANNING ◄ DIAGNOSING ◄───┘
                               │          │
                               │          └─► ESCALATING → (attempt ends: ESCALATED)
                               └────────────► EXECUTING
                                                       ▼
                                              SUBMITTING → attempt ends: SUCCEEDED
```

Attempt terminal outcomes: `SUCCEEDED`, `FAILED`, `ABORTED` (infrastructure — sandbox lost, worker killed; **not** the model's fault, does not count against `max_attempts`), `ESCALATED`, `TIMED_OUT`.

Separating `ABORTED` from `FAILED` is important: an infrastructure blip must not consume the agent's retry budget or pollute the "you already tried this" history.

### 10.3 Enforcement — how the LLM is structurally prevented from declaring completion

All state changes funnel through one component:

```java
@Transactional
public Task apply(UUID taskId, TaskEvent event, Actor actor, TransitionContext ctx) {
    Task task = tasks.findByIdForUpdate(taskId);            // row lock + @Version
    Transition t = transitionTable.lookup(task.state(), event)
        .orElseThrow(() -> new IllegalTransitionException(task.state(), event));
    GuardResults g = guards.evaluateAll(t.guards(), task, ctx);
    if (!g.allPassed()) throw new GuardFailedException(g);
    task.setState(t.to());
    transitions.append(task, t, event, actor, g);           // append-only row
    events.publish(new TaskStateChanged(...));              // outbox
    return task;
}
```

- The transition table is a static, declared `Map<(State, Event), Transition>`. An event with no entry throws. There is no reflective, string-driven, or model-supplied path to a state.
- **The LLM has no tool that writes `tasks.state`.** Its most powerful options are `propose_transition(COMPLETE, rationale)` and `request_human_decision(...)`. `propose_transition` is a *suggestion* that must still satisfy every guard.
- Guards on `COMPLETE` (all must pass, all mechanical):
  1. Latest attempt outcome is `SUCCEEDED`.
  2. Verification contract executed and passed on the final head SHA — from `evidence`, not from prose.
  3. Diff guards passed (no deleted/disabled tests, no weakened assertions).
  4. No open `human_interventions`.
  5. Required authority ≤ effective authority.
  6. PR merged, or an explicit human acceptance record exists.
  7. Budget not exceeded (a task cannot "complete" after being cut off mid-verification).

**Why a hand-rolled table over Spring StateMachine.** Spring StateMachine is heavyweight, its persistence story fights our append-only transition log, and it wants to own the runtime. Our needs are ~20 transitions with rich guards and a transactional audit row — that is 200 lines of clear Java we can test exhaustively.
- *What could go wrong:* state explosion as edge cases arrive. Rule: **new states must be lifecycle-distinct.** If it is "how we are doing the work," it is an attempt phase. If it is "a fact about the task," it is a column. Enforce in review.
- *What could go wrong:* a guard depends on data written in another transaction and flaps. All guards read committed rows within the same locked transaction.

---

## 11. Task / attempt / step model

Six levels, mapping directly onto the brief's Task #421 example (schema in §4.4):

```
Task ── goal, state, budget, dependency edges
 └─ Attempt (n) ── ONE hypothesis, ONE plan lineage, ONE sandbox, ONE base SHA
     └─ Step (n) ── one unit of work: an LLM call, a tool call, a checkpoint
         └─ ToolCall ── name, validated args, authorization decision, idempotency key
             └─ ToolResult ── status, summary for the model, blob ref for the full output
                 └─ Evidence ── the durable, replayable fact extracted from it
```

### What makes an attempt an attempt

An attempt is **one coherent approach**. New attempt when the *approach* changes (new hypothesis after diagnosis, or the sandbox was lost). Not a new attempt for each tool call or each file edit.

`approach_fingerprint` is the mechanism behind "do not rediscover failed approaches": a normalised hash of `(hypothesis embedding-free keyword set, planned file scope, primary strategy)`. Before `EXECUTING`, the runtime checks the fingerprint against prior failed attempts on the same task. On a match it does not simply block — it **injects the prior failure into context** and escalates to the supervisor:

> Attempt 2 tried this same approach (modify `AuthFilter.doFilter`, add a null check) and failed with `NullPointerException` at `TokenResolver:88`. Do not repeat it. Evidence: [test output, diff].

This is the single highest-leverage anti-thrash mechanism in the system, and it is cheap: one hash comparison plus a context injection.

### Step granularity and checkpointing

A step is the **resume unit**. Too coarse (one step per phase) and a crash re-runs a 10-minute test suite; too fine (one per token) and we drown in rows. One step per LLM call and one per tool call is the right grain: worst case on crash we lose one tool call.

Steps are `unique(attempt_id, step_no)`, so replay after a crash is idempotent — the executor recomputes step N, finds it committed, and skips to N+1.

### Output discipline — the context-window killer

A single `mvn test` on a real project emits megabytes. Rules enforced in `ToolDispatcher`, not left to the model:

- Full output → blob storage, referenced by `tool_results.output_ref`.
- The model sees a **budgeted summary**: head + tail, plus extracted structure (failing test names, error types, stack frame of first failure).
- Hard byte cap per tool result in-context (e.g. 8 KB), with `truncated: true` stated explicitly so the model knows to ask for more.
- Extraction is **parser-first** (Surefire XML, JUnit XML, jest JSON) and only falls back to a utility-model summary when no parser matches.

### Evidence vs history

`task_steps` is the forensic record — complete, high-volume, for humans debugging. `evidence` is the curated set of facts fed back to models. They are different tables because they have different consumers and different volumes. Replaying 4,000 raw steps into a prompt is what the brief rightly warns against; replaying 15 evidence rows is tractable.

- *What could go wrong:* evidence extraction is lossy and drops the fact that mattered. Mitigate: evidence always carries `source_tool_call_id`, so a model can pull the full output via a `read_tool_output` tool when the summary is insufficient. Lossy by default, complete on demand.

---

## 12. Dependency graph design

Per the preamble challenge, **two graphs, two tables, two lifecycles.**

### 12.1 Work graph (`work_dependencies`) — MVP

Low volume, durable, human-confirmable, participates in scheduling.

Nodes are polymorphic `(type, id)`: `TASK`, `ISSUE`, `PR`, `DECISION` (a human decision, referencing `human_interventions`), `EXTERNAL` (an upstream release, a vendor fix). Relations: `BLOCKS`, `RELATES_TO`, `DUPLICATES`, `SUPERSEDES`, `REQUIRES_DECISION`.

**The trust model is the whole design:**

| `status` | `source` | Blocks scheduling? |
|---|---|---|
| `CONFIRMED` | `GITHUB_EXPLICIT` (task list, "Closes #101"), `USER` | **Yes** |
| `CONFIRMED` | `LLM_INFERENCE`, promoted by a human | **Yes** |
| `INFERRED` | `LLM_INFERENCE`, `HEURISTIC` | **No** — advisory only |
| `REJECTED` | any | No, and never re-inferred (rejection is sticky) |

**Inferred edges never block by default.** A hallucinated `BLOCKS` edge silently deadlocks a task with no error, no failure, and no obvious cause — the worst possible failure mode, because the system looks healthy while doing nothing. Instead, an inferred edge surfaces in the UI as "Forge thinks #105 may be blocked by #101 (confidence 0.72, because both modify `TokenResolver`) — confirm?" A human click promotes it to `CONFIRMED`.

Per-repo policy may later allow auto-promotion above a confidence threshold, but only once measured precision justifies it, and never for `BLOCKS`.

`provenance` (JSONB) is mandatory on inferred edges: which model, which prompt version, which evidence (files, issue text, commits). An unexplainable inferred edge is not actionable and cannot be reviewed.

### 12.2 Runnability

```sql
-- a task is runnable iff no CONFIRMED BLOCKS edge points at it from an unfinished node
select t.id
from tasks t
where t.workspace_id = :ws
  and t.state = 'READY'
  and t.scheduled_after <= now()
  and not exists (
      select 1
      from work_dependencies d
      left join tasks bt on bt.id = d.from_id and d.from_type = 'TASK'
      where d.workspace_id = t.workspace_id
        and d.to_type = 'TASK' and d.to_id = t.id
        and d.relation = 'BLOCKS' and d.status = 'CONFIRMED'
        and coalesce(bt.state, 'OPEN') not in ('COMPLETED','CANCELLED')
  )
order by t.priority desc, t.created_at
limit :n
for update skip locked;
```

`FOR UPDATE SKIP LOCKED` lets multiple scheduler instances scan concurrently without double-claiming. Non-task blockers (`ISSUE`, `PR`, `EXTERNAL`) resolve via GitHub state synced by webhooks.

### 12.3 Cycle detection

Enforced **at write time, for `CONFIRMED BLOCKS` edges only**, via a recursive CTE reachability check inside the inserting transaction:

```sql
with recursive reach(node) as (
    select :new_to
  union
    select d.to_id from work_dependencies d join reach r on d.from_id = r.node
    where d.relation='BLOCKS' and d.status='CONFIRMED' and d.workspace_id=:ws
)
select exists(select 1 from reach where node = :new_from);  -- true → cycle, reject
```

Rejecting at write time keeps the invariant "the confirmed blocking graph is a DAG" always true, so the scheduler never needs cycle handling. Inferred edges are exempt — they may be cyclic, harmlessly, because they do not participate in scheduling. Promotion to `CONFIRMED` runs the check.

A background sweep additionally detects tasks blocked for more than N days and raises an operational alert — a defence against a DAG that is technically acyclic but practically stuck.

### 12.4 Code graph — explicitly deferred

`code_graph_edge`, keyed by `(managed_repository_id, commit_sha)`, derived from static analysis, is **not in MVP** (§24). It is high-volume, invalidated by every commit, and — critically — it is not needed for scheduling. Its eventual use is context retrieval ("which modules does this change affect"), and grep plus the language server ecosystem covers enough of that initially.

- *Alternative considered:* one polymorphic edge table for both graphs. Rejected in the preamble — different volume, lifecycle, and trust model.
- *What could go wrong:* users create confirmed cycles across issues and PRs via GitHub semantics we auto-import. Mitigate: `GITHUB_EXPLICIT` edges that would create a cycle are imported as `INFERRED` with a conflict flag rather than rejected outright — never lose the signal, just do not let it block.

---

## 13. Supervisor vs Executor model architecture

### The port

```java
public interface ModelRouter {
    ModelResponse call(ModelRole role, ModelRequest request);
    <T> T callStructured(ModelRole role, ModelRequest request, Class<T> schema);
}
public enum ModelRole { SUPERVISOR, EXECUTOR, UTILITY }
```

Roles bind to models in **configuration**, never in code:

```yaml
forge:
  models:
    supervisor: { provider: openai, model: <frontier>, temperature: 0.2, max-tokens: 8000 }
    executor:   { provider: openai, model: <fast>,     temperature: 0.0, max-tokens: 4000 }
    utility:    { provider: openai, model: <cheap>,    temperature: 0.0, max-tokens: 1000 }
```

Overridable per `AgentProfile`, so a repo can be pinned to a cheaper executor or an experimental supervisor without a deploy. Call sites say `router.call(SUPERVISOR, req)` — they never name a model. Repricing, provider failover, and per-workspace A/B are config changes.

Spring AI sits *inside* the adapter. If Spring AI 2.0's tool-calling or structured-output support proves immature on Boot 4.1 (a live risk, §26), we swap in a hand-written HTTP client for one provider without touching a single call site. That insulation is the entire reason for the port.

### Division of labour

| | Supervisor | Executor |
|---|---|---|
| **Called** | Planning, replanning, escalation, risk assessment, final architectural judgement | Every mechanical step |
| **Frequency** | Bounded — hard cap (default 6/task) | The bulk of calls |
| **Context** | Goal, repo knowledge digest, attempt *summaries*, evidence, dependency context. **Not raw files at scale** | The current plan step, relevant file excerpts, last error, tool schemas |
| **Output** | Schema-validated `Plan` or `SupervisorDirective` | Tool calls, or a step completion |
| **Temperature** | 0.2 | 0.0 |

`SupervisorDirective` is a closed enum of actions, not prose:

```java
sealed interface SupervisorDirective {
    record Replan(Plan plan, String rationale)                       implements SupervisorDirective;
    record ContinueWithGuidance(String guidance)                     implements SupervisorDirective;
    record AskHuman(String question, List<Option> options, Risk r)   implements SupervisorDirective;
    record Abort(String reason, FailureClass cls)                    implements SupervisorDirective;
}
```

The supervisor cannot return "keep going and it'll probably work." It must pick a structural action the runtime knows how to execute. Unparseable output → one retry with a repair prompt → treated as `AskHuman`.

### Cost levers

- **Prompt caching.** Structure every prompt as `[stable prefix: system + repo digest + conventions][volatile suffix: current step + recent evidence]`. Executor calls within one attempt share a prefix, so the expensive part is cached. This is worth more than any model downgrade; `llm_invocations.cached_prompt_tokens` tracks whether it is actually working.
- **Escalation is bounded and deterministic** (§9). The supervisor is invoked because a persisted condition became true, not because a model asked for help.
- **Utility role for mechanical text** — summarising logs, classifying a signal, naming a branch. Never route these to the executor out of convenience.
- **No conversational accumulation.** Each call is constructed from persisted state by the context assembler. There is no growing chat history, which is the default way agent systems reach 200k-token prompts and never come back.

- *Alternative considered:* one model for everything. Rejected — planning quality dominates outcome quality, mechanical steps do not, and paying frontier prices for `read_file` is indefensible at scale.
- *Alternative considered:* three-tier with a mid model. Deferred — add only when the data shows a gap the two tiers cannot cover.
- *What could go wrong:* the executor is too weak and thrashes, so escalation fires constantly and costs *more* than a single strong model. This is measurable: track escalations-per-task and cost-per-merged-PR per model config. Treat the split as a hypothesis to validate, not an axiom.

---

## 14. Agent memory architecture

Three distinct stores. The brief's separation of execution history from long-term knowledge is right; there is a third layer between them.

| Layer | Table | Scope | Written by | Read by |
|---|---|---|---|---|
| **Execution history** | `task_steps`, `tool_calls`, `tool_results`, `task_state_transitions` | One attempt | Runtime, always | Humans debugging; rarely models |
| **Working memory** | `evidence`, `plans`, `task_attempts.hypothesis` | One task, across attempts | Runtime, curated | Models, every call |
| **Long-term knowledge** | `repo_knowledge`, `repo_snapshots` | One repo, forever | Consolidation job, gated | Models, at context assembly |

Working memory is the layer the brief implies but does not name, and it is where "do not rediscover failed approaches" actually lives — attempt hypotheses, failure classes, and evidence, scoped to the task, curated to a few dozen rows.

### Long-term knowledge

Items are typed (`ARCHITECTURE`, `CONVENTION`, `CONSTRAINT`, `MODULE`, `INCIDENT`, `DECISION`, `GOTCHA`), and every item is **stamped with the commit SHA at which it was observed** and carries a confidence score.

Two rules keep it from rotting:

1. **Append + supersede, never mutate.** A revised belief writes a new row and sets `supersedes_id`; the old row keeps `superseded_at`. You can always answer "what did Forge believe last March, and why did it change?"
2. **Staleness decays confidence.** Knowledge observed 400 commits ago about a file that has since changed 30 times is downweighted at retrieval and eventually flagged for revalidation. Confident, stale knowledge is worse than no knowledge, because the model will not question it.

**Knowledge is written by a gated consolidation job, not by the agent mid-task.** After a task reaches `COMPLETED`, a job proposes candidate knowledge items from the attempt record. Items derived from a *verified, merged* change are auto-accepted at moderate confidence; items derived from an abandoned task require human confirmation.
- *Why:* if an agent can write memory freely mid-task, a wrong belief formed during a failing attempt persists and poisons every future task on the repo. Memory corruption in an autonomous system is a compounding error, and it is very hard to notice.

### Retrieval — deterministic, not vector search

The `context` module builds a budgeted bundle with priority tiers:

```
Tier 0 (always)     task goal, acceptance criteria, current phase, plan
Tier 1 (always)     failed approaches on THIS task + their failure classes
Tier 2 (high)       repo conventions + constraints for the touched paths
Tier 3 (medium)     module knowledge for files in plan scope
Tier 4 (as fits)    architecture digest, related incidents
Tier 5 (as fits)    file excerpts from tool calls
```

Tiers fill against a token budget; lower tiers are dropped, never Tier 0–1. Selection is by **path overlap, recency, and confidence** — plain SQL. No embeddings in MVP.

- *Why not pgvector now:* semantic retrieval solves a problem we have not yet demonstrated we have. With tens to low hundreds of knowledge items per repo, structured filtering is more precise than nearest-neighbour and infinitely more debuggable. Add pgvector when a measured retrieval-miss rate justifies it — the schema is ready (`repo_knowledge` gains an `embedding` column, retrieval gains a hybrid ranker).
- *What could go wrong:* knowledge grows unbounded and dilutes context. Mitigate with a per-repo item cap enforced by the consolidation job, evicting lowest `confidence × recency`.

---

## 15. Tool architecture

Tools are the **only** way a model affects the world, so the tool layer is the security perimeter.

```java
public record ToolDefinition(
    String name,
    String description,
    JsonSchema argumentSchema,
    RiskLevel risk,                 // LOW | MEDIUM | HIGH
    SideEffect sideEffect,          // READ | WRITE_SANDBOX | EXEC | WRITE_GITHUB
    Set<Authority> requiredAuthority,
    boolean idempotent,
    Duration timeout
) {}
```

### Dispatch pipeline — every call, no exceptions

```
1. Resolve  — name in the per-attempt allowlist? (unknown/hallucinated → reject, log, feed error back)
2. Validate — arguments against JSON schema (reject on failure, do not coerce)
3. Authorize— requiredAuthority ⊆ effectiveAuthority(task, policy, installation, approvals)
4. Dedupe   — idempotency_key = hash(attempt_id, step_no, tool, args_hash); replay cached result
5. Execute  — through the owning adapter, with timeout + cancellation
6. Persist  — tool_calls + tool_results + evidence, in one transaction
7. Truncate — summarise for the model, full output to blob (§11)
```

**The allowlist is computed per attempt and per phase, and enforced twice.** Tools outside it are not offered to the model *and* are rejected at dispatch. A model that hallucinates `delete_branch` gets a structured error, not a deleted branch. Offering-only is insufficient — models invent tool names.

Phase gating is meaningful: `ANALYZING` gets read-only tools; `WRITE_GITHUB` tools exist only in `SUBMITTING`. An executor cannot open a PR mid-implementation even if it decides that would be helpful.

### MVP tool set

| Tool | Side effect | Risk | Notes |
|---|---|---|---|
| `list_directory`, `read_file`, `grep`, `find_files` | READ | LOW | Path-jailed to the workspace |
| `git_log`, `git_diff`, `git_blame` | READ | LOW | |
| `read_tool_output(tool_call_id, range)` | READ | LOW | Pull full output behind a truncated summary |
| `apply_patch(unified_diff)` | WRITE_SANDBOX | MED | **Preferred edit tool** |
| `write_file(path, content)` | WRITE_SANDBOX | MED | Escape hatch for new files |
| `run_command(cmd, args)` | EXEC | MED | **Allowlisted binaries only** |
| `run_tests(selector?)` | EXEC | MED | Runs the verification contract's test command |
| `record_evidence`, `record_finding` | READ | LOW | Structured note into `evidence` / knowledge candidates |
| `propose_transition(state, rationale)` | READ | LOW | A suggestion; guards decide (§10.3) |
| `request_human_decision(question, options)` | READ | LOW | Always available, in every phase |
| `github_open_pr`, `github_comment` | WRITE_GITHUB | HIGH | **Host-brokered**, `SUBMITTING` only, idempotent |

**`run_command` is an allowlist, not a shell.** The verification contract declares permitted binaries (`mvn`, `gradle`, `npm`, `pytest`, `go`, …); arguments are passed as an argv array with no shell interpretation — no `sh -c`, no pipes, no `&&`. This is not primarily about stopping a malicious model; it is about ensuring commands are reproducible, attributable, and analysable. Unrestricted shell also makes the sandbox's other controls largely decorative.

**`apply_patch` over `write_file`.** A unified diff fails loudly when the model's assumption about file content is wrong, whereas whole-file writes silently clobber concurrent state and quietly delete code the model forgot to include. Patch failures are a valuable signal — they mean the model's mental model has drifted, which is worth escalating on.

- *Alternative considered:* MCP for tool definitions. Attractive for ecosystem reuse and worth revisiting, but it adds a protocol and process boundary before our tool semantics have stabilised. The `ToolDefinition` record is deliberately MCP-shaped so an adapter is cheap later.
- *What could go wrong:* tool proliferation — 40 narrow tools degrade model selection accuracy and inflate every prompt. Cap the offered set (~12) and consolidate rather than add.

---

## 16. Sandbox / execution architecture

### The port

```java
public interface SandboxProvider {
    /** May throw CapacityExhaustedException — the caller must handle "not now". */
    SandboxHandle provision(SandboxSpec spec) throws SandboxException;

    /** Repeated exec against a live session. Chunks stream to the consumer; the
     *  full output is persisted by the caller, never held in memory. */
    ExecResult    exec(SandboxHandle h, ExecRequest r, Consumer<OutputChunk> sink);

    void          writeFile(SandboxHandle h, String relPath, byte[] content);
    byte[]        readFile(SandboxHandle h, String relPath);
    PatchResult   applyPatch(SandboxHandle h, String unifiedDiff);
    String        captureDiff(SandboxHandle h);          // vs base SHA
    HealthState   probe(SandboxHandle h);                // ALIVE | GONE | DEGRADED
    void          destroy(SandboxHandle h);
}

record SandboxSpec(String ociImage, int cpuMillis, int memoryMib, int diskMib,
                   Duration ttl, EgressPolicy egress, Map<String,String> env) {}
record SandboxHandle(UUID sandboxId, String provider, String externalId) {}  // opaque
```

Everything the runtime does to a working copy goes through this interface. Note what is *absent*: no host paths, no container IDs, no network names, no Docker flag strings, no image building. Resource limits are neutral units the adapter translates (`cpuMillis` → `--cpus` / k8s `limits.cpu` / Firecracker vCPU).

### Keeping the Docker adapter genuinely replaceable

An interface alone does not decouple anything — the coupling usually lives in the *assumptions* around it, and every one of these is a real trap:

| Leak | How it happens | Rule |
|---|---|---|
| **Host filesystem shortcuts** | The Docker adapter can bind-mount the workspace and let `readFile` read the host path directly. It is faster and easier — and impossible in k8s or a hosted provider | **No host path ever escapes the adapter.** All file access goes through the port, even when a shortcut exists |
| **Long-lived-container assumption** | Docker lets you exec into one container repeatedly. Naive k8s ports reach for `Job`, which is run-to-completion, and the model breaks | The port's contract is a **session with repeated exec**. A k8s adapter satisfies it with a long-running pod (sleep entrypoint), not a Job |
| **Provisioning latency baked into constants** | Docker on a warm VM provisions in ~2s; k8s with an image pull takes 30–60s; hosted is sub-second | Every timeout is config, never a constant. No code assumes provisioning is fast |
| **Placement assumptions** | "There is one Docker host" leaks into the scheduler | The provider owns capacity and placement. `provision` may refuse; the scheduler handles backpressure, not host selection |
| **Docker exception types** | `DockerException` propagates into runtime `catch` blocks | Adapters normalise to `SandboxException`: `CapacityExhausted`, `SandboxLost`, `ExecTimeout`, `ImageUnavailable`. The runtime only ever sees these |
| **Egress config as imperative setup** | Runtime tells the adapter to attach a network | `EgressPolicy` is declarative in the spec (`DENY_ALL`, `PROXY_ONLY`). Adapter translates to iptables / NetworkPolicy / provider config |
| **Verification contracts encoding the substrate** | A repo's contract drifts toward `docker run …` | Contracts declare `test command` + allowlisted binaries only (§15). Already true; keep it true |

**Two enforcement mechanisms, both in CI:**

1. **ArchUnit rule:** no `com.github.dockerjava..` import anywhere outside `dev.tushar.forge.sandbox.docker`. Same technique as the `sandbox ↛ githubapp` rule (§2) — a coupling constraint the build fails on, not a convention people remember.
2. **Provider conformance suite:** one `@TestFactory` test class, written against `SandboxProvider` only, run against *every* adapter. Covers provision → write → exec → patch → diff → probe → destroy, plus the nasty cases (exec timeout, OOM kill, sandbox vanishing mid-exec, capacity refusal, path-traversal attempts on `relPath`). Ship it in M5 alongside the Docker adapter and an in-memory fake. A future k8s or gVisor adapter then has an executable specification instead of a prose one — that is what turns "swap the adapter" from a claim into a measurable task.

**The deepest decoupling is already in the design and worth making explicit:** the runtime must *already* treat sandbox loss as routine (§20 — `ABORTED` does not consume the retry budget, and the cumulative patch is replayed onto a fresh sandbox). That assumption is what makes migration safe. Kubernetes evicts pods, drains nodes, and OOMKills far more aggressively than a quiet Docker host does; a runtime that only works because Docker containers rarely disappear would break on arrival. Because we assume unreliability from day one, a more hostile substrate surfaces no new failure mode — only a higher rate of one we already handle. **Test that path deliberately on Docker** (kill the container mid-attempt in CI), precisely because Docker will not exercise it for you.

### MVP adapter: Docker on a dedicated worker VM

`DockerSandboxProvider` drives the Docker Engine API (docker-java) on a **dedicated VM that runs nothing else** — not the API host, not a developer laptop. One container per attempt, from a small set of pre-built language images with toolchains and warm dependency caches baked in.

Hardening, every flag readable and verifiable with `docker inspect`:

```
--user 10001:10001                 non-root
--read-only                        immutable rootfs
--tmpfs /tmp:rw,noexec,nosuid,size=512m
-v forge-ws-{attempt}:/workspace   the only writable path
--cap-drop=ALL
--security-opt=no-new-privileges
--security-opt=seccomp=default
--pids-limit=512
--memory=4g --memory-swap=4g --cpus=2
--network=forge-sbx-{workspace}    per-workspace bridge, egress DENY by default
                                   iptables allowlist → Forge egress proxy only
(no docker socket mount, no host mounts, no --privileged, no host network)
```

Egress goes through a Forge-operated HTTP(S) proxy that allows package registries (Maven Central, npm, PyPI) and nothing else. No arbitrary internet from inside a sandbox.

### The decision that matters most: **no GitHub token ever enters the sandbox**

Repository content is attacker-controlled in the general case — issue bodies, PR comments, READMEs, code comments, dependency names. That content reaches a model that holds tools. A credential inside a sandbox with any egress is a one-prompt exfiltration of the customer's source and, worse, write access to their repos.

Therefore **all git operations against GitHub are host-brokered**:

```
Control plane                          Sandbox
─────────────                          ───────
mint scoped token (§7)
git clone --depth=N  ──► writes into the attempt volume
                                       (agent reads/edits/tests freely,
                                        no credentials present, no GitHub egress)
capture diff        ◄──  git diff via SandboxProvider
validate diff guards
commit + push + open PR (host, token never left the control plane)
destroy volume + token
```

`.git/config` in the sandbox has no remote credentials, and the `sandbox` module is structurally forbidden from depending on `githubapp` (§2), so this cannot be regressed by accident.

### Lifecycle

Sandboxes are ephemeral, one per attempt, with a hard TTL (default 60 min, configurable) recorded in the `sandboxes` table. A scheduler **reaper** destroys containers and volumes whose row is expired or whose attempt has ended — leaked containers are how a worker VM fills its disk on a Tuesday night. Sandbox loss is `ABORTED`, not `FAILED` (§10.2), and the cumulative patch is persisted to blob storage after each successful edit step so a lost sandbox loses minutes, not the attempt's work.

### Residual risk, stated plainly

Container isolation is **weaker than a VM boundary**. A kernel exploit from inside a container can reach the host. We accept this for a design-partner alpha because: the worker VM is dedicated and holds no credentials beyond its own; the code being run belongs to the customer whose sandbox it is; egress is denied by default; and per-workspace networks prevent cross-tenant reachability.

**This must be closed before public self-serve signup.** The trigger is untrusted repositories from unvetted accounts. At that point either (a) add gVisor as a runtime class — a per-container flag, low disruption, meaningful kernel-attack-surface reduction, and the cheapest upgrade; (b) move to Firecracker microVMs; or (c) adopt a hosted sandbox provider. The `SandboxProvider` port means this is an adapter swap, not a rewrite. Option (a) is the recommended next step and is worth doing as soon as there is a second customer.

- *Alternative considered:* running commands directly in the Spring Boot JVM's host. Rejected outright — arbitrary customer code in the application process is not a sandbox at all.
- *What could go wrong:* the single worker VM is a capacity ceiling and a single point of failure. Acceptable for alpha (a handful of concurrent attempts); the provider interface already permits a pool of VMs with a scheduler-side placement decision.
- *What could go wrong:* image drift — the agent's toolchain differs from CI's, so tests pass locally and fail in CI. Mitigate by deriving the sandbox image from the repo's own CI configuration where possible, and by treating CI as the final arbiter (§10.1 `AWAITING_EXTERNAL`).

---

## 17. Permission / security model

### Effective authority

```
EffectiveAuthority =
      installationPermissions          (what GitHub granted — §7)
    ∩ workspacePolicy                  (agent_policies, workspace scope)
    ∩ repositoryPolicy                 (agent_policies, repo scope — overrides workspace)
    ∩ autonomyLevel(managedRepository) (ceiling set by the human)
    ∩ riskCeiling(task.risk_level)     (what this class of work may do)
    ∪ grantedApprovals(task)           (explicit, bound to plan version + diff SHA)
```

Computed by `policy.EffectiveAuthorityResolver` — a **pure function of persisted rows**, with no model in the path. It is evaluated at token-mint time (§7) and again at tool-dispatch time (§15), so authority is enforced both as the shape of the credential and as a runtime gate.

```java
enum AutonomyLevel { OBSERVE_ONLY, SUGGEST, PR_WITH_APPROVAL, PR_AUTONOMOUS }
// MERGE_AUTONOMOUS deliberately does not exist yet (§25)
```

### Risk classification — the LLM may raise, never lower

Rules-based first, deterministic, from path globs and change shape:

| Risk | Triggers |
|---|---|
| LOW | `**/*.md`, docs, comments, test-only additions, lint/format |
| MEDIUM | Application source, refactors, dependency bumps (non-major) |
| HIGH | `**/migrations/**`, `**/*.sql`, `.github/**`, `Dockerfile`, infra as code, `**/security/**`, `**/auth/**`, secrets config, major version bumps, anything deleting >N lines |

The supervisor may then propose a risk level, and it is applied **only if it is higher** than the rules produced. A model can say "this is riskier than it looks"; it can never say "this migration is actually fine." That asymmetry is deliberate and non-negotiable: every persuasive argument for lowering risk is exactly what a prompt-injection payload would produce.

### Diff guards — the anti-cheat layer

Run in `VERIFYING`, before verification is allowed to pass. Violations are **policy failures**, not test failures, and force escalation:

- Test files deleted, or test count decreased.
- Tests newly `@Disabled` / `@Ignore` / `.skip` / `xit` / `pytest.mark.skip`.
- Assertions removed or weakened (assert → log, tightened expected values loosened).
- CI configuration modified (should be impossible without Workflows:write, but check anyway — defence in depth).
- Changes outside `plan.declared_file_scope`.
- Secrets or credential-shaped strings introduced.
- Dependency added that is not in the plan.

This exists because "make the tests pass" and "delete the failing test" are indistinguishable to a naive reward signal, and every autonomous coding system rediscovers this the hard way.

### Prompt injection

Repository content is **untrusted data, never instructions**. Controls:

- All repo-derived content is delivered inside explicit data fences with a standing instruction that content within them is data.
- Authority lives outside the model entirely (§15, §17) — a successful injection can make the model *want* to do something, but the tool allowlist and the minted token scope decide what happens.
- No credentials in the sandbox (§16), egress denied by default: even a fully compromised model has nothing to exfiltrate with and nowhere to send it.
- HIGH-risk actions require human approval regardless of how confident the model is.
- Injection-shaped patterns in issue/PR text ("ignore previous instructions", instructions addressed to an AI) raise task risk and are logged.

The design principle: **assume the model will be compromised at some point and ensure that a compromised model still cannot do serious damage.** Every control above holds even if the model is fully adversarial.

### Secrets

App private key and webhook secret in a KMS or sealed secret, never in the repo or a plain env var. Any tokens at rest use envelope encryption (`platform.crypto`) with a rotatable DEK. Installation tokens live only in Redis, encrypted, TTL-bounded. Prompts and tool outputs go to a separate access-controlled blob store with its own retention policy — they contain customer source code and must not land in application logs.

---

## 18. Multi-tenancy isolation

Defence in depth, four layers. Any one can fail without a cross-tenant breach.

**1. Schema.** Every tenant table carries `workspace_id uuid not null`. A Modulith/ArchUnit test asserts that every `@Entity` mapped to a tenant table has the column and that a matching RLS policy exists — new tables cannot silently opt out.

**2. Postgres RLS — the backstop.**

```sql
alter table tasks enable row level security;
alter table tasks force row level security;
create policy tenant_isolation on tasks
  using (workspace_id = current_setting('app.workspace_id', true)::uuid);
```

The application connects as a role that is **not** superuser and does **not** have `BYPASSRLS`. Migrations and system jobs use a separate role with explicit bypass.

**3. Session GUC binding — and the pooling footgun.**

```java
// inside the transaction, before any tenant query
jdbc.execute("SET LOCAL app.workspace_id = ?", ctx.workspaceId());
```

`SET LOCAL` (transaction-scoped) is mandatory. Plain `SET` is **connection**-scoped, and with HikariCP that connection returns to the pool still carrying the previous tenant's ID — a cross-tenant data leak that is intermittent, load-dependent, and nearly impossible to reproduce. Bound via a `TransactionSynchronization` in `platform.tenancy` so no developer has to remember it.

Workers set `TenantContext` from the task's `workspace_id` at job start; the queue message carries the workspace ID and the worker validates it against the task row.

**4. Application-layer filtering.** Repositories still filter by `workspace_id` explicitly. Redundant with RLS by design — RLS catches what code forgets, code catches what a missing GUC would expose.

**Beyond the database:** per-workspace Docker networks (§16); blob storage keyed `{workspace_id}/{task_id}/…`; per-workspace LLM rate limits and cost meters; per-workspace GitHub rate-limit buckets; workspace ID in every log MDC and metric tag.

- *Alternative considered:* schema-per-tenant or database-per-tenant. Rejected for alpha — migration fan-out across hundreds of schemas, connection-pool explosion, and cross-tenant analytics become painful. Shared schema with RLS is the right point on the curve; a dedicated-database tier for enterprise customers is a later commercial decision, not an architectural one.
- *What could go wrong:* a developer runs a query outside a transaction, the GUC is unset, and `current_setting(..., true)` returns null → policy matches nothing → confusing empty results rather than a leak. That failure direction is correct (fail closed), but the error message is bad. Add a `@TenantScoped` aspect that throws a clear exception when `TenantContext` is empty.
- *What could go wrong:* RLS on very large tables degrades plans. Monitor; the predicate is on an indexed column, so this should stay cheap.

---

## 19. Event / audit architecture

Three separate concerns the brief lumps together. Keeping them apart is what makes each one usable.

| | Domain events | Audit log | Execution trace |
|---|---|---|---|
| **Table** | `event_publication` (outbox) | `audit_events` | `task_steps`, `tool_calls`, `tool_results` |
| **Purpose** | Decouple modules | Compliance, "who did what" | Debugging an attempt |
| **Volume** | Medium | Low | Very high |
| **Audience** | Other modules | Humans, auditors, customers | Engineers |
| **Retention** | Days (post-relay) | Years | 90 days, then blob archive |
| **Mutability** | Deleted after relay | Append-only, no UPDATE/DELETE grant | Append-only, partitioned |

### Domain events + outbox

Spring Modulith's `@ApplicationModuleListener` gives transactional, persisted event publication out of the box — the outbox we would otherwise hand-roll. Publishing and the state change commit atomically; a relay then delivers to in-process listeners and Redis.

Key events: `TaskCreated`, `TaskStateChanged`, `AttemptStarted`, `AttemptEnded`, `VerificationCompleted`, `HumanInterventionRequested`, `PullRequestOpened`, `BudgetThresholdCrossed`, `SignalReceived`, `InstallationRevoked`.

**Every consumer must be idempotent** — the relay is at-least-once. This is a hard rule, tested with duplicate-delivery tests, not a convention.

### Audit log

Audited: every task state transition; every GitHub mutation; every policy decision that denied something; every human approval/rejection; every authentication event and workspace switch; every installation binding change; every budget action; every configuration change to policies, verification contracts, or agent profiles.

`before`/`after` JSONB for config changes. `actor_type ∈ {HUMAN, SYSTEM, SCHEDULER, SUPERVISOR, EXECUTOR}` — attributing an action to a *model role* rather than a generic "AI" is what makes "why did Forge do this?" answerable.

Monthly partitions. Optional later: a `prev_hash` chain for tamper evidence, if enterprise compliance demands it. Not MVP.

### Task replay

Because transitions, steps, tool calls, results, and evidence are all append-only and ordered, the UI can reconstruct any attempt exactly: every decision, its inputs, its cost, its outcome. This is the single most valuable debugging feature in the product and it comes free from the schema — but only if nothing is ever updated in place. Protect that property.

---

## 20. Failure recovery and resumability

Every failure is classified; the class determines the response.

```java
enum FailureClass {
    TRANSIENT_INFRA,      // sandbox died, network blip, worker killed
    PROVIDER_ERROR,       // LLM 5xx / rate limit / timeout
    GITHUB_ERROR,         // GitHub 5xx / rate limit / conflict
    BUDGET_EXCEEDED,
    VERIFICATION_FAILED,  // the agent's change is wrong  ← the only "real" agent failure
    POLICY_DENIED,
    UNSATISFIABLE,        // the goal cannot be achieved as stated
    HUMAN_REQUIRED
}
```

| Failure | Detection | Response | Counts against `max_attempts`? |
|---|---|---|---|
| Worker crash / OOM / deploy kill | Lease TTL expiry | Reconciler requeues; runtime resumes from last checkpoint | No |
| Sandbox lost | `exec` fails, container gone | Attempt → `ABORTED`; new sandbox from base SHA + persisted cumulative patch; **same plan** | No |
| LLM provider down | HTTP status | Backoff → failover model → task `SUSPENDED` | No |
| LLM rate-limited | 429 | Respect `Retry-After`, requeue with `scheduled_after` | No |
| GitHub rate limit | `x-ratelimit-remaining` | Task → `AWAITING_EXTERNAL` until `x-ratelimit-reset` | No |
| Verification failed | Contract exit code | → `DIAGNOSING` → new attempt | **Yes** |
| Policy denied | Authority check | → `AWAITING_HUMAN` | No |
| Attempt cap reached | `attempt_count ≥ max_attempts` | → `ABANDONED`, full evidence attached | — |
| Budget exhausted | Cost meter | → `SUSPENDED` (never silently degrade) | No |
| Poison task (repeats identical failure) | `approach_fingerprint` + `failure_class` repeat | Escalate, then `ABANDONED` | Yes |

**The critical distinction:** infrastructure failures must not consume the agent's retry budget or pollute its failed-approach history. Conflating "the pod restarted" with "the fix was wrong" makes the agent conclude a correct approach failed, and it will avoid the right answer on the next attempt.

### Resumability mechanics

1. **Checkpoint per step.** Resume recomputes from the last committed `step_no`; the unique index makes replay idempotent.
2. **Cumulative patch persisted after every successful edit step** to blob storage. This is what makes sandbox loss cheap — reprovision, replay the patch, continue. Without it, sandbox loss means redoing the whole attempt.
3. **Lease reconciler** (scheduler, every 30s): tasks in `RUNNING`/`QUEUED` with `lease_expires_at < now()` are reclaimed, `lease_epoch` incremented, and requeued.
4. **Graceful drain on deploy.** `SIGTERM` → stop claiming new work → finish the current step → checkpoint → release lease → exit. With a `terminationGracePeriod` longer than the longest single step (bounded by tool timeouts). Long tasks survive deploys because a task is not a process.
5. **Sandbox reaper** cleans orphans independently of the runtime's happy path.
6. **Redis rebuild.** Flush Redis → reconciler repopulates queues from Postgres within one cycle. Test this deliberately.

- *What could go wrong:* a checkpoint captures state the resumed worker cannot reconstruct (in-memory context, an open file handle). Rule: **checkpoints must be fully rehydratable from Postgres plus the sandbox**. Nothing in the runtime may hold durable state in a field. Enforce in review; test by killing workers at random steps in CI.

---

## 21. Concurrency / idempotency strategy

### Concurrency invariants

| Invariant | Mechanism |
|---|---|
| One in-flight attempt per task | `unique index on task_attempts(task_id) where ended_at is null` — a DB constraint, not app logic |
| One worker per task | Redis lease + `tasks.lease_owner/lease_epoch/lease_expires_at`, heartbeated every 15s, TTL 60s |
| Bounded concurrent tasks per repo | Redis semaphore `forge:sem:repo:{id}`, default 1 in MVP |
| Bounded concurrent tasks per workspace | Redis semaphore + admission control |
| No double-claim from the scheduler | `SELECT … FOR UPDATE SKIP LOCKED` |
| No lost update on `tasks` | `@Version` optimistic lock + `SELECT … FOR UPDATE` in `TaskStateService` |
| Single scheduler leader | Redis leader lock, short TTL |

**Per-repo concurrency defaults to 1 in MVP.** Two agents on one repo produce conflicting branches, duplicate PRs, competing CI runs, and confusing review load. Raise it per repo once branch-scope overlap detection exists.

### Fencing — the subtle one

A worker that stalls (long GC, network partition) can have its lease expire and be reassigned, then wake up and write. Every lease carries a monotonically increasing `lease_epoch`; **every write from a worker carries its epoch and is rejected if the task's current epoch is higher.**

```sql
update tasks set ... , version = version + 1
where id = :id and lease_epoch = :my_epoch and version = :my_version;
-- 0 rows → we are a zombie → abort immediately, do not retry
```

Without fencing, a lease is only a hint, and the "one worker per task" invariant is not actually enforced. This is the classic distributed-lock mistake and it is worth getting right up front.

### Idempotency, by boundary

| Boundary | Key | Behaviour on duplicate |
|---|---|---|
| Webhook delivery | `X-GitHub-Delivery` (unique index + Redis fast path) | 200, no-op |
| Signal creation | `unique(workspace_id, dedup_key)` on open signals | Increment `occurrence_count` |
| Task creation | `unique(workspace_id, idempotency_key)` | Return existing task |
| Queue message | `attempt_id + step_no` | Consumer skips committed steps |
| Tool call | `hash(attempt_id, step_no, tool, args_hash)` | Return cached `tool_results` row |
| **GitHub mutation** | `github_action_log.fingerprint` unique | Return recorded response |
| Outbox relay | Event ID | Consumer-side dedupe |

**GitHub mutations deserve special care.** "Did I already open this PR?" must be answerable without asking GitHub, because the failure mode is a crash *after* GitHub committed the change but *before* we recorded it. Pattern: write the intent row first, call GitHub, then update with the response. On resume, an intent row with no response triggers a **reconciling read** (`GET /repos/…/pulls?head=…`) rather than a blind retry. Blind retry on non-idempotent GitHub endpoints is how you get four identical PRs.

- *What could go wrong:* clock skew between workers corrupts lease timing. Use Redis TTLs (server-side clock) as the authority rather than comparing wall clocks across hosts.

---

## 22. Cost control / model routing strategy

An autonomous agent with a retry loop is an unbounded spend generator by default. Cost control is a correctness requirement, not a finance feature.

### Layered limits — every one a hard stop

| Scope | Limit | On breach |
|---|---|---|
| Tool call | timeout, output bytes | Kill, truncate, record |
| LLM call | max tokens, timeout | Fail the step, retry once |
| Step | max tool calls | End phase, escalate |
| Attempt | token budget, wall clock, step count | Attempt → `TIMED_OUT` |
| Task | `budget_usd_micros`, `max_attempts`, supervisor-call cap | Task → `SUSPENDED` |
| Workspace | monthly budget, concurrent attempts, spend velocity | Stop admitting tasks; alert |

Enforcement is pre-flight (estimate before the call and refuse if it would breach) plus post-flight accounting from actual usage. Estimate-only lets one huge response blow the budget; account-only means you discover the breach after paying for it.

**A breach suspends, it never silently degrades.** Quietly downgrading the supervisor to a cheap model when the budget runs low produces a worse change that still gets a PR, which is the most expensive possible outcome.

### Routing rules

```
phase          → role         → model (from AgentProfile)
PLANNING       → SUPERVISOR
REPLANNING     → SUPERVISOR
ESCALATION     → SUPERVISOR
ANALYZING      → EXECUTOR (+ ≤1 SUPERVISOR summary)
EXECUTING      → EXECUTOR
DIAGNOSING     → EXECUTOR first; SUPERVISOR on 2nd+ failure of the same class
log/text summarisation, signal triage, branch naming → UTILITY
```

### The levers that actually move the number

1. **Prompt caching** (§13) — the largest single lever. Stable prefix, volatile suffix. Track `cached_prompt_tokens / prompt_tokens` as a first-class metric; if it is low, the prompt structure is broken.
2. **Output truncation** (§11) — one untruncated test log can cost more than the fix.
3. **Deterministic escalation** (§9) — supervisor calls happen on measured conditions, not vibes.
4. **Context budget tiers** (§14) — a large repo cannot produce a 300k-token prompt because the assembler refuses.
5. **Early abandonment** — a task making no diff progress across two attempts is escalated or abandoned. The marginal value of attempt 6 is near zero and its cost is not.
6. **Circuit breaker** — workspace spend velocity above a threshold pauses new task admission and pages. This is what stops a $4,000 overnight surprise from a pathological repo.

### The metric that matters

Per-token cost is a distraction. Track **cost per merged PR** and **cost per human-accepted change**, segmented by task kind and model config. That is the number that determines whether the product has a business, and it is the only one that correctly penalises a cheap model that thrashes.

Every `llm_invocations` row carries `cost_usd_micros`; rollups aggregate to attempt, task, repo, and workspace. Alert on per-task cost outliers (>3σ) — they are almost always a loop bug, not a hard problem.

---

## 23. Observability

### Tracing

OpenTelemetry via Micrometer. One trace per **attempt** (not per HTTP request — HTTP is incidental here). Span hierarchy mirrors the domain:

```
attempt {task.id, attempt.no, repo, workspace}
├─ phase:INITIALIZING → sandbox.provision, git.clone
├─ phase:ANALYZING    → llm.call{role=EXECUTOR}, tool.grep, tool.read_file
├─ phase:PLANNING     → llm.call{role=SUPERVISOR, tokens, cost}
├─ phase:EXECUTING    → tool.apply_patch, tool.run_command
└─ phase:VERIFYING    → verification.run, diffguard.check
```

Span attributes always include `workspace_id`, `task_id`, `attempt_id`, `phase`, and — on LLM spans — `model`, `role`, token counts, and cost.

### Logging

Structured JSON with MDC (`workspace_id`, `task_id`, `attempt_id`, `step_id`, `phase`, `request_id`). **Never log repository source, full prompts, or full tool output to the application log sink** — that is customer intellectual property, and it will end up in a third-party log aggregator. Those go to the access-controlled blob store, referenced by ID.

### Metrics that matter

*Product health* (the ones that decide whether this works):
- `forge.task.completed` / `abandoned` / `failed` rate
- **Attempts-to-success histogram** — the single best proxy for agent quality
- **First-try verification pass rate**
- **Human edit rate on Forge PRs** — detects the "80% trap" (§26)
- **Cost per merged PR**
- Escalation rate per repo
- Human intervention wait time

*System health:*
- Queue depth and **oldest-pending-age** per stream (age matters far more than depth)
- **Lease expiry rate** — the zombie-worker detector; a rising rate means workers are dying quietly
- Outbox relay lag
- Sandbox provision latency and failure rate; orphan count from the reaper
- GitHub rate-limit headroom per installation
- LLM latency/error rate by provider and role; cache hit ratio
- RLS-context-missing exceptions (should be exactly zero)

### Health and debugging

Actuator with custom indicators: Docker host reachable, Redis, Postgres, GitHub App JWT mintable, LLM provider reachable. Readiness excludes the LLM provider (a provider outage should not remove the API from rotation); liveness excludes everything external.

**The task replay view** (§19) is the primary debugging tool: full reconstruction of any attempt — every prompt, tool call, result, decision, and cost. Build it early. Without it, diagnosing "why did the agent do that?" is archaeology, and you will do it every day.

---

## 24. MVP scope

**Goal of v1: one real task, on one real repository, end to end, with a merged PR — and full observability into how it went.** Not breadth. One narrow path that genuinely works.

| # | Capability | Notes |
|---|---|---|
| 1 | GitHub OAuth login, users, workspaces, membership | Single workspace per user is fine |
| 2 | GitHub App install, repo selection, `ManagedRepository` opt-in | With the admin verification of §7 |
| 3 | Webhook receipt → Signal inbox | `auto_create_tasks: false`; only the CI-failure and review resume rules are automatic |
| 4 | Manual task creation | "Fix issue #N", "fix this failing test" |
| 5 | **One language/build ecosystem** | Pick the one the design partner uses. Generic build detection is a tarpit |
| 6 | Human-declared `VerificationContract` | Setup/build/test commands + allowlisted binaries |
| 7 | Task FSM + Attempt FSM + full attempt/step/evidence history | The core invariant |
| 8 | Scheduler, leases, fencing, reconciler, outbox | |
| 9 | Docker sandbox + host-brokered git | §16 |
| 10 | Tool registry, dispatcher, authorization, truncation | ~12 tools |
| 11 | `ModelRouter`; executor loop; supervisor for planning/replanning only | |
| 12 | Verification contract execution + diff guards | |
| 13 | Idempotent PR creation; CI monitoring; resume on CI failure | |
| 14 | Human intervention + approval bound to plan version + diff SHA | |
| 15 | Risk classification (rules) + autonomy levels + effective authority | |
| 16 | Audit log, cost meters, budgets with hard stops | |
| 17 | RLS multi-tenancy | From day one — retrofitting is brutal |
| 18 | Task replay view | The debugging tool you cannot work without |
| 19 | Explicit dependency edges only (`GITHUB_EXPLICIT`, `USER`) | No inference |

Target task classes for v1, in order of increasing difficulty: **failing-test fix → dependency bump with test validation → small well-specified bug fix from an issue.** These have mechanical, unambiguous success criteria, which is exactly what makes autonomy tractable.

---

## 25. What NOT to build initially

Each of these is genuinely tempting, and each would cost weeks while making the core loop no more likely to work.

| Not now | Why | Revisit when |
|---|---|---|
| **pgvector / semantic retrieval** | Structured filtering beats ANN at this scale and is debuggable | Measured retrieval-miss rate justifies it |
| **Code dependency graph** | Huge, regenerable, not needed for scheduling | Context retrieval demonstrably fails without it |
| **LLM-inferred blocking edges** | Hallucinated blockers deadlock silently — worst failure mode in the system | Inferred-edge precision is measured and high |
| **Auto-merge (`MERGE_AUTONOMOUS`)** | Requires trust nobody has yet, and the blast radius is production | Months of merged-PR quality data exist |
| **Multi-repo / cross-repo tasks** | Multiplies sandbox, auth, and dependency complexity | Single-repo tasks are reliable |
| **Kubernetes / autoscaling** | Team cannot validate it; a VM is enough for alpha | Capacity forces it, or gVisor upgrade lands |
| **Kafka, microservices, GraphQL, CQRS** | Solutions to problems we do not have | Genuine scale pressure |
| **Fine-tuning** | Prompt and context engineering has orders of magnitude more headroom | Prompting demonstrably plateaus |
| **Real-time token streaming to the UI** | Demo appeal, near-zero operational value | Users ask for it |
| **BYO-cloud / self-hosted runners** | Enterprise feature, large surface | Enterprise deals require it |
| **Billing, invoicing, quota self-service** | Design partners are invoiced manually | Self-serve signup |
| **Rich RBAC beyond 4 roles** | Nobody has asked | Customers ask |
| **Monorepo-aware partial builds** | Deep per-ecosystem work | A design partner has a monorepo |
| **Agent-authored CI/workflow changes** | The agent must not edit its own grading rubric | Probably never |
| **Parallel speculative attempts** | Multiplies cost, complicates the single-attempt invariant | Cost per merged PR is comfortable |
| **Slack / Jira / Linear integrations** | Distribution, not product | Core loop works |

The through-line: **do not build anything that makes the agent look more capable without making it more reliable.** Reliability is the product.

---

## 26. Potential architectural risks

Ordered by expected damage.

**1. Verification is the actual product, and it is hard.**
If Forge cannot mechanically distinguish "correct change" from "tests are green," autonomy is impossible and the product is a PR-spam machine that makes reviewers' lives worse. Green tests do not mean correct: tests may not cover the change, may have been weakened, or the fix may be a symptom patch. *Mitigate:* human-declared verification contracts; diff guards (§17); require that changed lines are actually exercised by an executed test; treat CI as the final arbiter, not the sandbox. *Watch:* human edit rate on Forge PRs. If it stays high, nothing else matters.

**2. Prompt injection via repository content.**
Issues, PR comments, and READMEs are attacker-controlled and flow into a tool-wielding model. *Mitigate:* the entire §17 stack — authority outside the model, no credentials in the sandbox, egress denied, HIGH-risk actions gated on humans. *Residual:* an injected model can still waste budget and open a bad PR. Bounded by budgets and review.

**3. Cost nondeterminism.**
One pathological repo, one loop bug, one giant log. *Mitigate:* §22 layered hard stops and the spend-velocity circuit breaker. *Watch:* per-task cost outliers.

**4. Sandbox escape.**
Container-only isolation (§16). Accepted for alpha with a dedicated credential-free VM; **must be upgraded (gVisor is the cheap next step) before public signup.** This is a scheduled debt with a named trigger, not an oversight.

**5. The 80% trap.**
The agent gets close, a human finishes it. If reviewing and fixing a Forge PR costs more than writing the change, the product's value is negative — and this can be true while every dashboard looks healthy. *Mitigate:* measure human edit rate from day one; prefer narrow task classes where the agent is reliably at 100% over broad classes where it is at 80%.

**6. Bleeding-edge stack: Spring Boot 4.1, Java 25, Spring AI 2.0.**
Library gaps are likely, particularly in Spring AI's tool-calling and structured-output support, and Modulith may lag Boot 4.1. *Mitigate:* the `ModelRouter` port means dropping to a hand-written provider client is a contained change; ArchUnit is the Modulith fallback. *Watch:* budget real time for dependency friction in M0 — it will not be zero.

**7. State machine sprawl.**
Every edge case tempts a new state; twelve states become thirty and the transition table becomes unreasonable. *Mitigate:* the rule in §10.3 — new states must be lifecycle-distinct, everything else is a phase or a column.

**8. GitHub rate limits.**
5,000 requests/hour per installation; a chatty analyzer burns that fast. *Mitigate:* prefer local git operations over API calls, ETag caching in `githubapi`, per-installation token buckets, back off on headers.

**9. Silent dependency deadlock.**
Tasks blocked forever on an edge nobody understands. *Mitigate:* inferred edges never block (§12); alert on tasks blocked beyond N days.

**10. Long-lived tasks across deploys.**
A task spanning weeks must survive schema and code changes. *Mitigate:* checkpoints are data, not serialised objects; plans are versioned; forward-compatible JSONB; graceful drain.

**11. Concurrent agents conflicting on one repo.**
*Mitigate:* per-repo concurrency of 1 in MVP; file-scope overlap detection before raising it.

**12. Data exfiltration liability.**
Customer source code transits an LLM provider and lands in prompt archives and blob storage. *Mitigate:* zero-retention agreements with providers, encrypted archives with short retention, never log source to application logs, document the data flow before the first customer asks (they will).

**13. Runaway autonomy perception.**
Even a well-behaved agent feels dangerous to a customer who cannot see what it is doing. *Mitigate:* the replay view, the Signal inbox, explicit opt-in per repo, and conservative autonomy defaults are as much trust features as safety ones.

---

## 27. Suggested implementation order

This section assumes the **Appendix B recommendation is adopted**: build the outer loop, buy the inner loop.

**Organising principle: prove the entire mechanical substrate before the first LLM call.** Phases 1–2 contain no model at all. When the agent later misbehaves, you will know it is the prompt — not the queue, the lease, the harness, or the state machine. Teams that wire the LLM in first spend months unable to tell those apart.

**Scheduling note:** Phase 0 is a throwaway spike that depends on nothing. It runs **in parallel with Phase 1**, not before it. Serialising them wastes two weeks, because nothing in Phase 1 is affected by which harness wins.

```
   Phase 0  ├── spike: harness bake-off ──┐
            │                              ▼ decision gates Phase 3
   Phase 1  ├── foundation ── identity ── GitHub App
   Phase 2  │                    └── jobs ── task FSM
   Phase 3  │                              └── harness integration
   Phase 4  │                                    └── FIRST AUTONOMOUS TASK
   Phase 5  │                                          └── verify + PR
   Phase 6  │                                                └── event-driven
   Phase 7  │                                                      └── safety + memory
   Phase 8  │                                                            └── graph + polish
```

---

### Phase 0 — Harness bake-off (spike, throwaway, parallel with Phase 1)

**Goal:** replace the Appendix B hypothesis with data. This code is deleted afterwards.

Per B.8: stand up the OpenHands agent server in Docker and drive it from a throwaway Spring service over REST/WebSocket; do the same for the Claude Agent SDK behind a minimal Python wrapper. Run 20 seeded tasks on one real repository with known failing tests.

**Exit criteria — a written decision recording:**
- Resolution rate, cost per resolved task, wall-clock per task, for each harness.
- Crash-resume correctness: kill the harness mid-task; does it resume, and is the resumed state correct?
- **Whether transition authority can stay on the Java side** — the harness reports outcomes, Forge decides transitions (§10.3). If this cannot be enforced, that is the signal to build instead of buy.
- The §16 credential boundary holds: no GitHub token reachable from inside the harness sandbox.

**Not in scope:** production code, our schema, our FSM, multi-tenancy. Resist all of it.

---

### Phase 1 — Foundation, identity, GitHub authority

**Goal:** a multi-tenant Spring app a human can log into, install the GitHub App on, and enable a repository in. No agent anywhere.

| Step | Deliverable | Exit criteria |
|---|---|---|
| 1.1 | Spring Modulith package skeleton (§2), role profiles `api`/`worker`/`scheduler`, docker-compose for Postgres + Redis, Testcontainers | `ApplicationModules.verify()` passes in CI |
| 1.2 | ArchUnit suite: module dependency rules, `@Port` hygiene, no-`Impl`, no `dockerjava` outside its adapter | A deliberate `FooService`/`FooServiceImpl` on a scratch branch fails the build |
| 1.3 | Flyway V1 baseline: tenancy, identity, audit. RLS enabled + forced, non-`BYPASSRLS` app role, `TenantContext` with `SET LOCAL` via `TransactionSynchronization` | Tenant A's query returns zero of tenant B's rows; missing GUC throws a clear exception rather than returning everything |
| 1.4 | `platform.crypto` (envelope encryption), `audit` (append-only, no UPDATE/DELETE grant) | Audit rows cannot be modified by the app role |
| 1.5 | GitHub OAuth login (§6): `read:user`, `user:email` only; opaque sessions; users/identities/workspaces/members | Log in with GitHub; a workspace exists; **assert no `repo` scope is requested** |
| 1.6 | GitHub App (§7): install flow **with installation-admin verification**, app JWT → per-task scoped installation tokens, Redis token cache, repo sync, `ManagedRepository` opt-in | Install, pick repos, enable one; a narrowed token is minted and cached; binding a stranger's `installation_id` is rejected |

**Not in scope:** tasks, agents, sandboxes, LLM calls.

---

### Phase 2 — Mechanical substrate (still zero LLM)

**Goal:** the state machine and job platform, provably correct, driven by fake handlers.

| Step | Deliverable | Exit criteria |
|---|---|---|
| 2.1 | `platform.jobs`: transactional outbox relay, Redis Streams queue, leases with **fencing epochs**, reconciler, scheduler leader election, graceful drain | A trivial job survives `kill -9` and resumes; `FLUSHALL` on Redis loses no work |
| 2.2 | Task/attempt/step/transition schema (§4.4), including the `one_live_attempt_per_task` partial unique index | Concurrent attempt creation fails at the database, not in application logic |
| 2.3 | `TaskStateService` + declared transition table + guards (§10.3) | Every illegal (state, event) pair throws; `COMPLETE` is refused when any single guard precondition is removed |
| 2.4 | Task REST API, **fake phase handlers** that simulate success/failure/escalation | A task runs end to end through the FSM with no model and no sandbox |

**This is the phase to over-invest in.** Everything after it inherits these guarantees, and FSM bugs found later are found in production.

---

### Phase 3 — Harness integration *(gated on the Phase 0 decision)*

**Goal:** Forge can make a real code change in a real repository, driven by a scripted sequence — still no autonomous loop.

| Step | Deliverable | Exit criteria |
|---|---|---|
| 3.1 | `ExecutionHarness` port (§B.6): `startAttempt`, `sendGuidance`, `streamEvents`, `pause`, `resume`, `captureDiff`, `destroy`. In-memory fake + conformance suite | Conformance suite green against the fake |
| 3.2 | Winning-harness adapter, containerised, driven over REST/WebSocket | Conformance suite green against the real adapter |
| 3.3 | **Credential boundary**: host-brokered clone, no token in the harness sandbox, egress deny-by-default + proxy allowlist, per-workspace network | `docker inspect` shows non-root, cap-dropped, read-only rootfs, no credentials in env or `.git/config` |
| 3.4 | Harness event stream → our `task_steps` / `tool_calls` / `evidence` rows; sandbox lifecycle table + reaper | A scripted change is fully reconstructable from Postgres; orphaned containers are reaped |
| 3.5 | Kill-the-container test | Attempt ends `ABORTED` (not `FAILED`), retry budget untouched, cumulative patch replays onto a fresh sandbox |

**Not in scope:** planning, escalation, autonomy. The harness is a tool Forge drives, not yet a loop.

---

### Phase 4 — First autonomous task ← **the moment of truth**

**Goal:** a real task completes with no human in the loop.

| Step | Deliverable | Exit criteria |
|---|---|---|
| 4.1 | `ModelRouter` (§13) with role bindings + cost accounting per `llm_invocations` | A supervisor call returns a schema-valid `Plan`; cost is recorded |
| 4.2 | Phase handlers wired to the harness; attempt loop with per-step checkpoints (§9) | A crash mid-attempt resumes at the last committed step |
| 4.3 | Deterministic escalation triggers + `approach_fingerprint` (§9, §11) | A repeated failed approach injects prior failure into context and escalates |
| 4.4 | Budgets and hard stops (§22) | Budget exhaustion moves the task to `SUSPENDED`, never silent degradation |

**Exit criteria: failing test → passing test, autonomously, on a fixture repo.** Plus the negative case — a deliberately unfixable break must reach `ABANDONED` with evidence, not loop and not claim success.

---

### Phase 5 — Verification and PR submission

| Step | Deliverable | Exit criteria |
|---|---|---|
| 5.1 | `VerificationContract` execution (human-declared, model may not author it) | Verdict is mechanical; the model can summarise a failure but not decide the outcome |
| 5.2 | **Diff guards** (§17) | On a fixture where deleting the test is the easiest path to green, the guard catches it and the task escalates instead of completing |
| 5.3 | Idempotent commit/push/PR via `github_action_log` fingerprints (§21) | A crash after GitHub commits but before we record it produces a reconciling read, not a duplicate PR |

---

### Phase 6 — Event-driven operation

| Step | Deliverable | Exit criteria |
|---|---|---|
| 6.1 | Webhook receipt: HMAC over raw body, store-then-ack, 202, dedup on delivery GUID | Replaying a delivery GUID is a no-op |
| 6.2 | Signal normalisation + coalescing + inbox; `auto_create_tasks: false` | Five CI retries on one SHA produce one Signal |
| 6.3 | Rules-only resume triggers (CI failure, review changes-requested) + bot-sender loop guard + CI polling fallback | Break CI on a Forge PR; the task resumes and fixes it unprompted, with no LLM in the triage path |

---

### Phase 7 — Safety, policy, memory

| Step | Deliverable | Exit criteria |
|---|---|---|
| 7.1 | Rules-based risk classification; LLM may raise but never lower (§17) | A task touching `**/migrations/**` is HIGH regardless of model opinion |
| 7.2 | `EffectiveAuthorityResolver` + autonomy levels + per-task token scoping | A docs-only task's token carries one repo and `contents:write` alone |
| 7.3 | Human intervention bound to `plan_version` + `diff_sha` | Approve, then mutate the diff → approval is void and re-requested |
| 7.4 | Long-term knowledge with SHA stamping, supersede-not-mutate, gated consolidation | Knowledge is written only after a verified merged change |

---

### Phase 8 — Dependency graph and operability

| Step | Deliverable | Exit criteria |
|---|---|---|
| 8.1 | Explicit `work_dependencies` (`GITHUB_EXPLICIT`, `USER`), runnability query, write-time cycle detection | A blocked task refuses to run until its blocker completes; a confirmed cycle is rejected at insert |
| 8.2 | Task replay view (§19) | Any attempt is fully reconstructable — prompts, tool calls, costs, decisions |
| 8.3 | Dashboards: attempts-to-success, first-try verification pass rate, **human edit rate**, cost per merged PR | The metrics that decide whether this works are visible before the first design partner |

---

### What "start" means this week

1. `build.gradle`: add Spring Modulith, ArchUnit, Testcontainers. Keep Spring AI — the Java side still needs a `UTILITY` model for signal triage and knowledge consolidation even after buying the harness, though `ModelRouter` becomes smaller than §13 originally implied.
2. Create the §2 package skeleton with `package-info.java` module descriptors.
3. `docker-compose.yml` for Postgres + Redis.
4. Flyway `V1__baseline.sql` with tenancy, identity, audit, and RLS policies.
5. In parallel, start Phase 0: pull the OpenHands agent server image and get one conversation running against a throwaway repo.

Steps 1–4 are unambiguous and unaffected by every open question. Step 5 is what resolves the remaining ones.

---

## Verification of this plan's implementation

Per milestone, the check is behavioural, not unit-level:

- **M0:** `ApplicationModules.verify()` in CI. An integration test opens two workspaces and asserts, against a real Postgres (Testcontainers), that a query under tenant A's GUC returns zero of tenant B's rows — and that omitting the GUC throws rather than returning everything.
- **M0 abstraction hygiene:** The ArchUnit suite is itself tested — commit a throwaway `FooService`/`FooServiceImpl` pair on a scratch branch and confirm the build fails on both the orphan-interface and `Impl`-suffix rules. A rule nobody has seen fail is a rule nobody knows works. The `@Port` inventory prints as exactly four.
- **M3:** Chaos test in CI — start a job, `kill -9` the worker mid-step, assert the reconciler reclaims and the job resumes from the checkpoint with no duplicated side effects. Separately, `FLUSHALL` Redis and assert the queue rebuilds.
- **M4:** Property test over the transition table: for every (state, event) pair not in the table, `apply()` throws; for every guard on `COMPLETE`, a test that removes exactly that precondition and asserts the transition is refused.
- **M5:** Against a real throwaway GitHub repo — clone, run tests, apply a patch, capture the diff, destroy. Assert with `docker inspect` that the container ran non-root, read-only, cap-dropped, and with no credentials in the environment or `.git/config`. Assert the reaper removes an orphaned container.
- **M5 decoupling:** The provider conformance suite passes against both the Docker adapter and the in-memory fake. ArchUnit asserts no `dockerjava` import outside `sandbox.docker`. A dedicated test kills the container mid-`exec` and asserts the attempt ends `ABORTED` (not `FAILED`), the retry budget is untouched, and a fresh sandbox replays the cumulative patch to the same head state — the migration-safety property (§16), exercised deliberately because Docker will not trigger it on its own.
- **M6:** A scripted tool sequence (no model) that fixes a known failing test, asserting `tool_calls`, `tool_results`, and `evidence` rows are written and that a replayed duplicate call returns the cached result.
- **M8/M9:** End-to-end on a purpose-built fixture repository with a deliberately broken test. Run the task; assert it reaches `COMPLETED` with a merged PR, and inspect the replay view for the full attempt record. Then break it in a way the agent *cannot* fix and assert it reaches `ABANDONED` with evidence rather than looping or claiming success.
- **M9 anti-cheat:** A fixture where deleting the test is the easiest path to green. Assert the diff guards catch it and the task escalates instead of completing.
- **M10:** Open a Forge PR, force CI red, assert the task transitions `AWAITING_EXTERNAL → RUNNING` from the webhook alone. Replay the same webhook delivery GUID and assert a no-op.
- **M11:** A task touching `**/migrations/**` must land in `AWAITING_HUMAN`. Approve it, then mutate the diff, and assert the approval is void and re-requested.
- **Continuous:** cost per merged PR, attempts-to-success, and human edit rate tracked from M8 onward. These are the metrics that decide whether the architecture is working.

---

## Appendix A — Design principles: where SOLID binds, and where it doesn't

**The default is not to apply these.** A principle earns its place only when there is a concrete pressure that already exists in the code — not an anticipated one. If you cannot name the pressure in one sentence, write the plain concrete class and move on.

The test for introducing an abstraction — at least one must be true *today*:

1. A second implementation exists, or a test genuinely cannot be written without a seam.
2. The boundary is on the extraction list (§1: runtime and sandbox as future services).
3. A guarantee must be enforced mechanically rather than by discipline (§2 module rules, §10.3 FSM).

None of the above → concrete class, no interface. Revisit when the pressure shows up. **This is enforced in CI, not by review discipline** — see the `@Port` rules in §2.

This asymmetry is why: a concrete class that later needs an interface is a 10-minute refactor an IDE performs. A wrong abstraction propagates into every call site and into how people think about the domain, and it is rarely removed once code has been written against it. **Late abstraction is cheap; wrong abstraction is not.** So the sections below are a record of where a pressure has already been identified — not a checklist to satisfy.

### Where each principle is load-bearing (i.e. the pressure already exists)

**S — Single Responsibility.** The brief's central fear — *"these must not become one giant AI service"* — is SRP stated at module scale, and §2 is the answer. The separation that matters most: LLM reasoning (`llm`) / agent runtime (`runtime`) / state machine (`task`) / scheduler (`scheduler`) / GitHub (`githubapi`) / sandbox (`sandbox`) / memory (`memory`) each change for entirely different reasons. Also applied to data in §19: domain events, audit log, and execution trace are three tables because they have three audiences and three retention policies.

*Concrete debt to avoid at M6:* the §15 dispatch pipeline lists seven steps — resolve, validate, authorize, dedupe, execute, persist, truncate. That is seven reasons to change. Build it as a chain of small collaborators (`ToolResolver`, `ArgumentValidator`, `ToolAuthorizer`, `IdempotencyGate`, `ToolExecutor`, `ResultPersister`, `OutputTruncator`) behind one `ToolDispatcher` facade. A single 300-line `dispatch()` method is the most likely place this codebase grows a god class, because every new concern feels like it belongs there.

**O — Open/Closed.** Three extension points must be open: adding a tool touches only the registry (`ToolDefinition`), never the dispatcher; adding a phase handler touches only the handler map, never the attempt loop; adding a model provider touches only an adapter, never a call site.

*The deliberate exception:* the **state transition table (§10.3) is intentionally closed.** Adding a transition *should* require editing a reviewed, central table. Making the FSM "open for extension" — pluggable transition contributors, annotation-scanned states — would destroy the one guarantee the whole system rests on: that the set of legal state changes is small, enumerable, and auditable in one place. OCP is a means to safe change, not an end; here safety comes from concentration, not extensibility.

**L — Liskov Substitution.** Only genuinely relevant for `SandboxProvider`, and it is exactly the risk the Docker/k8s discussion surfaced. **The provider conformance suite (§16) *is* the Liskov contract, made executable.** An adapter that satisfies the signatures but violates the behavioural contract — throws `UnsupportedOperationException` from `probe`, silently ignores `ttl`, or only works because the caller happens to be on the same host — is an LSP violation that a compiler cannot catch and the conformance suite can. Same applies to `ModelRouter` adapters: one that ignores `max-tokens` or cannot do structured output is not substitutable, no matter what it implements.

**I — Interface Segregation.** The sharpest application: phase handlers receive `AgentContext` — a purpose-built, read-only projection — not the `Task` aggregate plus a fistful of repositories. A handler that can reach `taskRepository.save()` will eventually call it, and the FSM guarantee evaporates. Narrow the *capability*, not just the visibility. Likewise the runtime never gets a general GitHub client; it gets the specific host-brokered operations it is allowed to perform.

**D — Dependency Inversion.** The four justified ports: `SandboxProvider` (§16), `ModelRouter` (§13), the queue port in `platform.jobs`, and `platform.blob`. In each case high-level policy defines the interface and adapters depend inward. At module scale the same principle is enforced mechanically by the §2 dependency rules — `policy` must not depend on `llm`, `runtime` must not depend on `api`, `sandbox` must not depend on `githubapp`. Those are DIP constraints that fail the build, which is worth more than DIP as an intention.

### Where SOLID should *not* be applied

| Anti-pattern | Why it is wrong here |
|---|---|
| `XService` + `XServiceImpl` for every service | The classic Spring cargo cult. An interface with exactly one implementation, forever, is indirection with no payoff. **Create a port only when there is a real second implementation, a genuine test seam, or a planned extraction boundary.** Our four ports each satisfy that test; `TaskStateService` does not and stays a concrete class |
| Splitting the `Task` aggregate to satisfy SRP | §3 argues for the large aggregate deliberately. SRP is about *reasons to change*, not size — attempts, steps, and evidence all change for the same reason and are written in the same transaction. Splitting them to look tidy would put a crash-resume path across four transactions |
| Interfaces over entities, records, DTOs | `Task`, `Attempt`, `Plan`, `SandboxSpec` are data with behaviour. Abstracting them adds mapping layers and buys nothing |
| Abstracting the persistence layer behind custom repository interfaces | Spring Data already is that abstraction. A second layer over it is pure cost. We are not swapping Postgres — the plan depends on Postgres-specific features (RLS, JSONB, partial indexes, recursive CTEs, `SKIP LOCKED`) and says so |
| Generic `Handler<T>` / plugin frameworks before the second case exists | Two concrete implementations, then abstract. Abstracting from one example reliably produces the wrong abstraction |

### The framing that matters

Most of the leverage here is not at the class level. The failure mode this architecture is defending against is not a badly factored class — it is **a well-factored class inside a module that reaches somewhere it shouldn't**. That is why the important boundaries (§2, §16) are enforced by Modulith and ArchUnit rules that fail CI, rather than by principle adherence at review time. Prefer a mechanically enforced boundary over a well-intentioned one; the intentions do not survive month four.

---

## Appendix B — Build vs. buy: should we build the agent runtime ourselves?

**Status: research findings and recommendation. Not yet applied to §1–§27.** If accepted, §9, §14, §15 and §16 change substantially; §1–§8, §10–§13 and §17–§22 are unaffected.

### B.1 This is three decisions, not one

The question "build or buy the agent runtime" hides three independent choices at different layers. Conflating them is how teams end up adopting a framework that solves one layer and fighting it on the other two.

| Layer | What it does | Candidates |
|---|---|---|
| **L1 — Durable orchestration** | Survive crashes across long-running work; retries, timers, resume | Temporal, Restate, Inngest, Hatchet, DBOS, or our hand-rolled Postgres+Redis |
| **L2 — Coding-agent harness** | The inner loop: read/edit/run/test in a sandbox, tool dispatch, context compaction | Claude Agent SDK, Claude Managed Agents, OpenHands SDK, SWE-agent, Aider, OpenCode, Codex CLI |
| **L3 — Generic agent framework** | Graph/DAG orchestration of LLM calls, multi-agent patterns | LangGraph, PydanticAI, OpenAI Agents SDK, Microsoft Agent Framework, CrewAI, AutoGen |

Forge needs L1 and L2. It does **not** need L3 — see B.5.

### B.2 The constraint that reshapes everything: we are a Java shop

Every serious coding-agent harness in 2026 is Python or TypeScript. There is no Java option at L2, and there will not be one.

| Candidate | Language | Java? |
|---|---|---|
| Claude Agent SDK | Python, TypeScript (spawns a `claude` CLI subprocess) | No |
| OpenHands Software Agent SDK | Python (Pydantic, FastAPI, LiteLLM) | No — but ships a REST/WebSocket agent server |
| SWE-agent | Python | No |
| Aider | Python (single-process, terminal-oriented) | No |
| OpenCode | TypeScript/Effect, client-server with SQLite | No |
| OpenAI Agents SDK | Python, JS | No |
| PydanticAI | Python | No |
| Microsoft Agent Framework | .NET, Python (1.0 GA April 2026) | No |
| LangGraph | Python, JS | No |
| CrewAI / AutoGen | Python (AutoGen merged into MS Agent Framework) | No |
| **Temporal** | Go, Python, TS, **Java**, .NET | **Yes, first-class** |
| **Restate** | TS, Python, **Java/Kotlin**, Go, Rust — **Spring Boot starter** | **Yes** |
| Hatchet | Python, TS, Go, Ruby | No |
| DBOS | Python, TS | No |

So adopting anything at L2 means **a polyglot service boundary is unavoidable**. That sounds like a cost, but our architecture already has one: §1 defines an execution plane separate from the control plane, and §16 defines a `SandboxProvider` port whose whole purpose is to hide what runs over there. A Python agent server behind that port is not a new architectural concession — it is the concession we already made, cashed in for something valuable.

### B.3 The uncomfortable finding: most of §9/§15/§16 is commodity

The OpenHands Software Agent SDK paper ([arXiv:2511.03690](https://arxiv.org/abs/2511.03690)) describes an architecture that is close to a line-by-line match for what we designed independently:

| Our design | OpenHands SDK |
|---|---|
| §11 append-only steps/tool calls, replay | Event-sourced `ConversationState`, append-only `EventLog`, deterministic replay |
| §9 checkpoint per step, resume after crash | Auto-detects incomplete sessions, continues from last processed event |
| §10 pause for human, resume | `conversation.pause()` → `PauseEvent` → `run()` |
| §16 `SandboxProvider` port, Docker adapter, future remote | `Workspace` abstraction: `LocalWorkspace` / `DockerWorkspace` / `APIRemoteWorkspace`, same agent code unchanged |
| §15 `ToolDefinition` → dispatch → result → evidence | Action–Execution–Observation with Pydantic-validated Action models |
| §13 `ModelRouter` with role bindings | `RouterLLM` with `select_llm()`, 100+ providers via LiteLLM |
| §17 risk classification, approval gate | `SecurityAnalyzer` (LOW/MED/HIGH/UNKNOWN) + `ConfirmationPolicy`, `WAITING_FOR_CONFIRMATION` state |
| §11 output truncation, §14 context budget | `Condenser` / `LLMSummarizingCondenser`, ~2× cost reduction |
| §15 MCP-shaped tool definitions | Native MCP integration |

That convergence is worth two conclusions, and they point in opposite directions:

1. **Our design is sound.** Independent teams solving this problem arrive at event sourcing, replay, workspace abstraction, and risk-gated tool dispatch. We did not get it wrong.
2. **Therefore it is commodity, and building it is not where our time should go.** MIT-licensed, benchmarked at 72.8% on SWE-Bench Verified, hardened over 18 months of production. We would spend M5–M8 rebuilding it and land somewhere worse.

**What is genuinely ours** — and what no harness at L2 provides — is everything the plan describes *outside* the inner loop: the task lifecycle FSM (§10), the dependency graph and runnability (§12), effective authority and diff guards (§17), the verification contract (§9), multi-tenancy and RLS (§18), GitHub App scoping (§7), signal triage (§8), long-term repo knowledge (§14), audit (§19), and cost governance (§22). That is the "continuously maintains it" product. The inner loop is table stakes underneath it.

### B.4 L2 candidates assessed

**Claude Managed Agents** ([docs](https://platform.claude.com/docs/en/managed-agents/overview)) — hosted REST harness; Anthropic runs the loop *and* the sandbox. Sessions are long-running, resume cleanly, support steering/interrupt, and there are scheduled deployments. Self-hosted sandboxes ([docs](https://platform.claude.com/docs/en/managed-agents/self-hosted-sandboxes)) keep tool execution on our infrastructure while orchestration stays with Anthropic.
- *Fastest path by a wide margin.* No harness, no sandbox, no loop to operate.
- **Disqualifying for v1:** Managed Agents is **not eligible for Zero Data Retention**, by design — sessions persist conversation history and sandbox state server-side. For a product whose entire job is handling customers' proprietary source code, "we cannot offer ZDR" is an objection we would hit in the first enterprise conversation. Also beta, and Claude-only.
- *Reconsider* if Anthropic adds ZDR coverage, or for a self-serve tier where customers accept it.

**Claude Agent SDK** ([hosting docs](https://code.claude.com/docs/en/agent-sdk/hosting)) — the harness behind Claude Code, self-hosted. Spawns a `claude` CLI subprocess per session; `SessionStore` adapters (S3/Redis/Postgres) persist transcripts across hosts; OTEL built in; documented multi-tenant isolation (`settingSources: []`, per-tenant `CLAUDE_CONFIG_DIR`, per-tenant `cwd`); hooks and permission gating.
- *Strongest raw capability*, and the hosting docs are unusually honest about the operational model (subprocess-per-session, ~1 GiB RAM each, memory growth over long sessions, no top-level session timeout).
- *Cost:* Claude-only, which contradicts our §13 decision to stay provider-agnostic. Library, not a service — we would write and operate a thin Python/TS wrapper.

**OpenHands Software Agent SDK** — MIT, Python, model-agnostic via LiteLLM, ships a REST + WebSocket **agent server** with Docker images, and `APIRemoteWorkspace` for driving it over HTTP.
- *Best architectural fit.* It already is the execution plane we specified, it is drivable from Java over REST without us writing a wrapper service, and being model-agnostic it preserves §13.
- *Cost:* smaller ecosystem than Anthropic's; Python operational surface; the paper's comparison table is self-authored and unflatteringly framed toward competitors, so treat its claims about rival SDKs with suspicion (it asserts Claude/OpenAI SDKs "cannot sandbox execution," which the Claude hosting docs plainly contradict).

**SWE-agent / Aider / OpenCode / Codex CLI** — all real and good, none the right shape. SWE-agent is a research harness (small core, YAML tool bundles, SWE-ReX runtime) optimised for benchmarks, not multi-tenant SaaS. Aider is a single-process terminal pair-programmer — its repo-map via tree-sitter is genuinely excellent prior art worth stealing conceptually for §14, but it is not embeddable as a service. OpenCode and Codex CLI are terminal-first products.

### B.5 L1 and L3: what to reject, and why

**Reject L3 entirely.** LangGraph, PydanticAI, OpenAI Agents SDK, Microsoft Agent Framework, CrewAI, AutoGen all solve "orchestrate LLM calls in a graph." We do not have that problem — we have a *domain* state machine (§10) whose states are product concepts with guards, approvals, and audit obligations. Expressing `BLOCKED_ON_DEPENDENCY` as a LangGraph node would be a category error. CrewAI and AutoGen are additionally wrong on substance: role-play multi-agent conversation, with documented production problems around token consumption and error propagation. None have Java support.

**Do not adopt Temporal/Restate/Inngest/Hatchet in v1 — but for a sharper reason than "hand-rolled is fine."**

The decisive point: **a durable execution engine solves durability of execution; it does not solve domain state.** Our §10 FSM is not a durability mechanism — it is product surface. Customers see task states, approvals gate on them, the audit log is built from transitions, and the dependency graph queries them. That must live in Postgres as queryable rows regardless of what orchestrates the work. Temporal would become a *second* source of truth competing with it, which is exactly the objection recorded when this was first decided.

And if we adopt an L2 harness, the inner loop's durability becomes *the harness's* problem — OpenHands' event log and replay, or Claude's `SessionStore`. What is left for us to orchestrate is a handful of coarse, long steps per attempt, which leases + outbox + reconciler (§20, §21) handle comfortably. **Adopting L2 makes the hand-rolled L1 decision safer, not riskier.**

Worth recording for later: Temporal is genuinely proven at this exact workload — OpenAI runs Codex on it, reportedly millions of coding-agent requests daily — and has a first-class Java SDK. **Restate** is the more interesting option for us specifically: single binary, Java/Kotlin SDK, and an official `dev.restate:sdk-spring-boot-starter`. If durability bugs accumulate in M3–M8, Restate is the escape hatch, and it fits a Spring shop better than Temporal does.

### B.6 Recommendation

**Build the outer loop. Buy the inner loop.**

```
KEEP (this is Forge, and nothing else provides it)
  Java/Spring control plane · Task FSM §10 · dependency graph §12
  policy + effective authority + diff guards §17 · verification contract §9
  multi-tenancy/RLS §18 · GitHub OAuth/App/webhooks §6–§8
  signals + triage §8 · repo knowledge §14 · audit §19 · cost governance §22
  hand-rolled orchestration: outbox + Redis Streams + leases + reconciler §5, §20, §21

BUY (commodity, and we would build it worse)
  inner attempt loop · tool dispatch · context compaction · sandbox lifecycle
  → OpenHands Software Agent SDK, run as the execution plane,
    driven from Java over its REST/WebSocket agent server

REJECT
  L3 agent frameworks (wrong layer, wrong language)
  Temporal/Restate/Inngest/Hatchet in v1 (domain state ≠ durable execution)
  Managed Agents in v1 (no ZDR — unacceptable for customer source code)
```

**Primary: OpenHands SDK**, because it preserves provider-agnosticism (§13), is MIT, and exposes a REST server we can drive from Spring without writing a wrapper.
**Fallback: Claude Agent SDK** behind the same port, if evaluation shows a decisive quality gap. The port makes this a swap, not a rewrite — and it is the same `SandboxProvider`-style discipline argued in §16.

Concretely, §16's `SandboxProvider` widens into an **`ExecutionHarness` port** (`startAttempt`, `sendGuidance`, `streamEvents`, `pause`, `resume`, `captureDiff`, `destroy`) with an `OpenHandsHarness` adapter and an in-memory fake. The `@Port` justification (§2) writes itself. Everything in §17 stays on the Java side: we do not delegate authority decisions to the harness's `SecurityAnalyzer` — we use it as an *additional* signal and keep our own gate, because the harness's risk rating is a model judgement and §17 requires that the LLM may raise risk but never lower it.

**Revised milestone impact:** M5 and M6 shrink dramatically (integrate and harden a harness rather than build sandbox + tools from scratch); M7 mostly disappears; M8 arrives materially sooner. M4 (FSM), M9 (verification + diff guards), M10 (webhooks), M11 (policy) are unchanged — which is the tell that we are keeping the right half.

### B.7 What could go wrong with this recommendation

1. **We inherit someone else's roadmap and bugs.** Mitigated by the port + conformance suite (§16) and by the fallback adapter being real, not theoretical. Pin versions; do not track `main`.
2. **The harness's abstractions fight our FSM.** Specifically, it wants to own "when is the task done" and we require that guards decide (§10.3). Rule: the harness reports *outcomes*; Forge decides *transitions*. If that boundary proves unenforceable in evaluation, that is the signal to fall back to building.
3. **Python operational surface in a Java shop.** Real cost — a second runtime, second dependency ecosystem, second on-call surface. Bounded by running it only as a containerised service behind REST, never as a library we embed.
4. **Evaluation cost is not zero.** Benchmarks in the paper cost $100–1000 per run. Budget a real spike (below) rather than deciding from documentation, including this one.
5. **Prompt-injection surface moves but does not shrink.** §16's rule stands unchanged and is now *more* important: no GitHub token inside the harness sandbox, host-brokered git, egress deny-by-default. A third-party harness must not be trusted with credentials we would not give our own sandbox.

### B.8 Proposed next step before committing

A two-week spike, run before M5, against one real repository with a known failing test:

1. Stand up the OpenHands agent server in Docker; drive `POST /conversations` and the event WebSocket from a throwaway Spring service.
2. Do the same with the Claude Agent SDK behind a minimal Python/FastAPI wrapper.
3. Measure on 20 seeded tasks: resolution rate, cost per resolved task, wall-clock, crash-resume correctness, and — most important — **whether we can keep transition authority on the Java side** in both.
4. Verify the §16 credential boundary holds in each.

Decide from that data. Everything above is a hypothesis formed from documentation, and documentation is written by people selling something — including the MIT-licensed ones.

### Sources

- [OpenHands Software Agent SDK (arXiv:2511.03690)](https://arxiv.org/abs/2511.03690) · [HTML](https://arxiv.org/html/2511.03690v1) · [docs](https://docs.openhands.dev/sdk) · [agent server source](https://github.com/OpenHands/software-agent-sdk/tree/main/openhands-agent-server/openhands/agent_server)
- [Claude Agent SDK — hosting](https://code.claude.com/docs/en/agent-sdk/hosting) · [overview](https://platform.claude.com/docs/en/agent-sdk/overview)
- [Claude Managed Agents — overview](https://platform.claude.com/docs/en/managed-agents/overview) · [self-hosted sandboxes](https://platform.claude.com/docs/en/managed-agents/self-hosted-sandboxes)
- [SWE-agent: Agent-Computer Interfaces (arXiv:2405.15793)](https://arxiv.org/abs/2405.15793) · [Inside the Scaffold: taxonomy of coding agent architectures (arXiv:2604.03515)](https://arxiv.org/pdf/2604.03515)
- [Aider repo map](https://aider.chat/docs/repomap.html) · [OpenCode agent system](https://deepwiki.com/sst/opencode/3.2-agent-system)
- [Temporal + OpenAI Agents SDK](https://temporal.io/blog/announcing-openai-agents-sdk-integration) · [Temporal Replay 2026 announcements](https://temporal.io/blog/replay-2026-product-announcements)
- [Restate Java SDK](https://www.restate.dev/blog/announcing-the-restate-java-sdk) · [Spring Boot starter](https://central.sonatype.com/artifact/dev.restate/sdk-spring-boot-starter)
- [Hatchet](https://github.com/hatchet-dev/hatchet) · [Pydantic AI durable execution](https://ai.pydantic.dev/durable_execution/overview/) · [Microsoft Agent Framework 1.0](https://devblogs.microsoft.com/agent-framework/microsoft-agent-framework-version-1-0/)
- [Durable AI agents 2026: Temporal, Inngest, DBOS, Restate](https://www.reactify-solutions.com/articles/durable-ai-agents-2026) · [Checkpoints aren't durable execution](https://www.diagrid.io/blog/checkpoints-are-not-durable-execution-why-langgraph-crewai-google-adk-and-others-fall-short-for-production-agent-workflows)

---

## Addendum — API naming: `/api/session` replaces `/api/me`

**Status: implemented, 43 tests green.** Retained as the record of why.

### Context

Step 1.5 shipped a single REST endpoint, `GET /api/me`, served by `api/MeController`. `me` is a pronoun, not a resource: it names the *caller* rather than the thing being fetched, so it does not compose (`/api/me/workspaces`? `/api/me/session`?) and it sets a precedent the rest of the API would inherit — while `api/` is still one file, which is the cheapest possible moment to change it.

Looking at what the endpoint actually returns settles the replacement. `userId`, `email`, `displayName`, `avatarUrl` come from the user row, but **`activeWorkspaceId` comes from `ForgePrincipal`, i.e. the server-side session**, and `workspaces` is the membership list. The payload is not a user — it is *who am I and what may I see right now*. That is session context, so the resource is the session.

Chosen: **`GET /api/session` → `SessionController` returning `SessionView`.**

Why over the alternatives considered: `/api/viewer` (GitHub's GraphQL convention) is idiomatic for a GitHub-native product but imports a GraphQL-ism into a REST surface; `/api/current-user` is unambiguous but puts workspace context under a user resource, which forces a second endpoint the moment the UI needs more session state. `/api/account` was rejected outright — "account" already means the *GitHub* account here (`github_installations.account_login`, `account_type`), and reusing it would collide with established domain vocabulary.

The naming also buys a coherent home for two things §6 requires and Phase 1.5 has not placed yet: **`DELETE /api/session` (logout)** and **`PUT /api/session/workspace` (workspace switch)**. Both are session mutations; neither has an obvious home under `/api/me`.

### Change

One file, plus its URL. Nothing else references the path — `SecurityConfig` (`githubauth/internal/SecurityConfig.java:48`) matches `/api/**` broadly, and there is no client in this repo, so no compatibility shim or redirect is needed.

`git mv src/main/java/dev/tushar/forge/api/MeController.java src/main/java/dev/tushar/forge/api/SessionController.java`, then:

| Before | After |
|---|---|
| `@RequestMapping("/api/me")` | `@RequestMapping("/api/session")` |
| `class MeController` | `class SessionController` |
| `record MeView` | `record SessionView` |
| `ResponseEntity<MeView> me(...)` | `ResponseEntity<SessionView> current(...)` |
| `private MeView toView(...)` | `private SessionView toView(...)` |

Record component names are unchanged, so the JSON body is identical — only the path and the Java types move. Use `git mv` rather than delete-and-create so the review diff shows one line changed per row above, not a new file.

### Naming note worth recording

There are now three `Session`-shaped names, deliberately at three layers rather than duplicated:

| Name | Layer | Is |
|---|---|---|
| `iam.internal.session.Session` | persistence | the JPA entity (module-private) |
| `iam.SessionService`, `iam.AuthenticatedSession`, `iam.IssuedSession` | domain API | issue, validate, revoke |
| `api.SessionController`, `SessionView` | HTTP | the wire representation |

Controller / service / entity sharing a root noun is normal Spring layering, and the boundary records already disambiguate direction (`IssuedSession` out of login, `AuthenticatedSession` into a request). No further rename needed — but a fourth `Session*` landing in `api/` is the signal that the API surface has outgrown a flat package and wants the `@NamedInterface` treatment discussed for `iam`.

### Verification

- `./gradlew test` — 42 tests, unchanged. `ModularityTest` (`ApplicationModules.verify()`) must still pass; the `api` module descriptor and its `allowedDependencies = {"iam", "githubauth"}` are untouched.
- `AbstractionHygieneTest` is unaffected — no interface, no `Impl`, no layer-named package introduced.
- Manual: log in via GitHub, then `curl -b <session cookie> localhost:8080/api/session` returns the same JSON body previously served at `/api/me`. Authenticated `GET /api/me` should now be a clean 404, not a 500.

**Not in this change:** `DELETE /api/session` and `PUT /api/session/workspace`. Logout currently lives on Spring Security's `/logout` handler (`SecurityConfig.java:54`); moving it belongs with the browser client, not with a rename. Recorded here so the resource shape is intentional rather than accidental.

---

## Addendum — the three `Session` names, and the real gap behind them

**Status: implemented** (`nothingDependsOnTheApiModule`, proven to fail before being kept). **Superseded in part** — Finding 1 argued the overlap was acceptable because Java resolves it by import. That defended the compiler, not the reader; see the next addendum for the standard that replaces it.

### Context

The `/api/session` rename left three `Session`-shaped names in the codebase and a note that "a fourth `Session*` in `api/` means the API surface has outgrown a flat package." That note deserved a straight answer: is the overlap a problem, and is the remedy as expensive as implied? Checking both turned up **no naming problem and one genuine enforcement hole** — which is the part worth fixing.

### Finding 1 — the name overlap needs no fix, and renaming would make it worse

Java resolves types by import, so a repeated simple name only bites when two of them appear in **one compilation unit**. Exactly one file qualifies, and it reads cleanly (`iam/SessionService.java:51-71`):

```java
Session session = Session.issue(userId, hash(token), userAgent, expiresAt);
sessions.save(session);
return new IssuedSession(session.getId(), token, expiresAt);
...
return new AuthenticatedSession(session.getId(), session.getUserId(), session.getWorkspaceId());
```

Four Session-ish types, zero ambiguity: the entity is the bare noun, the two boundary records are participles describing *direction* (`IssuedSession` leaves on login, `AuthenticatedSession` arrives on a request), the local is `session` and the repository field is `sessions`.

`api` **cannot** collide with the entity even in principle — `iam.internal.session.Session` is module-private, so Modulith fails the build on any import of it from outside `iam`. The overlap is three layers naming the same concept, which is what layers are for. Renaming to `SessionEntity` / `SessionDto` would import exactly the layer-suffix vocabulary `AbstractionRules.packagesAreNamedAfterConceptsNotLayers` exists to keep out.

**Action: none.** Recorded so it is a decision rather than an omission.

### Finding 2 — the remedy I implied is the wrong one, and cheaper than stated

The reason `iam`'s API side stays flat is Modulith's rule that sub-packages are invisible to **dependents** unless annotated `@NamedInterface`. That constraint is about the module's consumers — and **`api` has none**. §2 states "Nothing depends on `api`."

So sub-packaging `api` costs nothing: no `@NamedInterface`, no ceremony, no import churn, because there is nobody outside to break. `api/session/SessionController.java` is just a `git mv`.

**Action: do it with Task #15, not as a standalone commit.** Splitting a one-file package is the over-structuring flagged when `iam` was reorganised. But Task #15 (installation binding) adds installation endpoints next, so `api` reaches two or three resources within days — at which point the split is justified by real content and still costs two `git mv`s:

```
api/session/       SessionController        (GET /api/session)
api/installation/  InstallationController   (GitHub App install callback, repo list, opt-in)
```

Deferring is cheap; doing it now is speculative structure.

### Finding 3 — "Nothing depends on `api`" is not actually enforced ← the real gap

Only three modules declare dependencies at all, and none names `api`:

| Module | `allowedDependencies` |
|---|---|
| `api` | `iam`, `githubauth` |
| `githubapp` | `iam`, `platform`, `platform::tenancy` |
| `githubauth` | `iam` |

So the property holds only because nobody has listed it. Adding `"api"` to some future module's `allowedDependencies` would make `ApplicationModules.verify()` pass while inverting the dependency direction — a controller package becoming a shared library is the ordinary way an HTTP layer stops being replaceable, and §2's whole claim is that swapping transport touches only this package.

Everything else in §2 that matters is a build failure (`sandbox ↛ githubapp`, `policy ↛ llm`, no `dockerjava` outside its adapter). This one is a sentence in a javadoc.

**Fix — one test method** in `src/test/java/dev/tushar/forge/architecture/AbstractionHygieneTest.java`, alongside the existing directional rules it already hosts (`dockerApiStaysInsideItsAdapter:73`, `sandboxCannotReachGithubCredentials:86`, `policyDoesNotDependOnLlm:103`). Reuses the `productionClasses` field and `ROOT` constant already there; no new test class:

```java
/**
 * The HTTP layer is a leaf. Nothing may depend on it.
 *
 * <p>Modulith enforces this only as long as no module lists {@code api} in its
 * {@code allowedDependencies} — an omission, not a guarantee. Stated directly here, because
 * "moving to a different transport touches only this package" stops being true the moment a
 * controller package acquires a dependent.
 */
@Test
void nothingDependsOnTheApiModule() {
    noClasses()
            .that()
            .resideOutsideOfPackage(ROOT + ".api..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage(ROOT + ".api..")
            .because("the HTTP layer is the outermost ring; a dependent would invert it")
            .check(productionClasses);
}
```

No `allowEmptyShould(true)` here — unlike the `sandbox` and `policy` rules, the `that()` set is non-empty today (every non-`api` production class), so the rule is live immediately rather than arming later.

### Verification

- `./gradlew test` — expect **43** tests, up from 42, all passing.
- Prove the new rule fires rather than trusting it, per the standard set by `AbstractionRulesFireTest` ("a rule nobody has seen fail is a rule nobody knows works"): temporarily add a field of type `SessionController` to a class in another module, confirm the build fails, then revert. Do **not** commit the fixture — unlike `ReportGenerator`, a cross-module dependency cannot live in `architecture.fixtures` without being the very violation under test.
- `ModularityTest` must still pass unchanged; this rule is strictly additional to Modulith's own checks.

---

## Addendum — module names must not be confusable: `githubauth` / `githubapp`

**Status: implemented, 44 tests green.** Two things the plan got wrong and implementation corrected: `ApplicationModule.getName()` does not exist in Modulith 2.1 (it is `getIdentifier()`), and the nested module's identifier is `platform.tenancy`, not `tenancy`. The planned verification of `sandboxCannotReachGithubCredentials` was also wrong — flipping `allowEmptyShould(false)` tests the *sandbox* side of the rule, which is empty for unrelated reasons, and would have passed while proving nothing. It was instead proven by a throwaway class in `dev.tushar.forge.sandbox` referencing `TokenScope`, which made the rule fail as intended. That rule had never fired once since it was written.

### Context

The standard being applied here is **a reader's, not a compiler's**: opening this codebase cold, nobody should have to work out which of two similar names means what. The previous addendum failed that bar — it argued the three `Session` names were fine because Java resolves them by import. True, and beside the point.

Re-reading the whole main tree against the reader's bar, the `Session` cluster is not the problem. **`githubauth` and `githubapp` are.** They are near-synonyms in English separated by the most security-critical distinction in the product: one identifies a *human*, the other grants the *agent* authority to act on repositories. The tell is that both module javadocs open by explaining they are not the other one:

- `githubauth/package-info.java`: *"This module grants the agent nothing… Authority for the agent to act on GitHub… lives in `githubapp`."*
- `githubapp/package-info.java`: *"Distinct from `githubauth`, which only identifies humans."*

**When a name needs a disclaimer, the name is doing the wrong job.** And the failure mode is not cosmetic: confusing these two is precisely how a `repo` scope ends up on the login flow, which §6 exists to prevent and `GithubOAuthScopeTest` exists to catch.

### The rename

| From | To | Owns |
|---|---|---|
| `githubauth` | **`githublogin`** | identifying a human — `read:user`, `user:email`, sessions |
| `githubapp` | **`githubinstallation`** | the agent's authority — installations, scoped tokens |

Keeping the `github*` prefix preserves the family §2 plans to grow (`githubapi`, `githubwebhook`) so they still cluster in the file tree, while making the two impossible to confuse. Neither needs a disclaimer javadoc afterwards — delete those sentences, and say what each module *is*.

Also update the `displayName`s: `"GitHub OAuth"` → `"GitHub Login"`, `"GitHub App"` → `"GitHub Installation"`.

### What must NOT be renamed

**"GitHub App" is GitHub's own product name**, so these stay exactly as they are — the module is being renamed for what it *owns* (installations, tokens), not to purge a term that is correct:

- `GithubAppJwtService` — mints the *app-level* JWT, which is genuinely an App concern, not an installation one
- `GithubAppProperties` — App id, private key, slug
- `@ConfigurationProperties(prefix = "forge.github.app")` — a config key; renaming it would break every deployment's `application.yaml` for zero readability gain

Worth stating explicitly, because the natural next instinct is to "finish the job" and rename these too.

### Blast radius — four references outside the two packages

`git mv` the two directories, then fix:

| File | Change |
|---|---|
| `api/package-info.java:9` | `allowedDependencies = {"iam", "githubauth"}` → `"githublogin"` |
| `api/SessionController.java:3` | `import …githubauth.ForgePrincipal` |
| `iam/package-info.java:5` | javadoc `{@code githubapp}` → `{@code githubinstallation}` |
| `architecture/AbstractionHygieneTest.java:92` | `ROOT + ".githubapp.."` in `sandboxCannotReachGithubCredentials` |

Plus `package` declarations in the 7 + 7 files under each tree (5 main + 2 test, and 5 main + 2 test), and the two `package-info.java` javadocs that cross-reference each other.

That last row matters most: `sandboxCannotReachGithubCredentials` is the ArchUnit rule that structurally prevents a GitHub token from reaching the sandbox. It currently points at `.githubapp..` and is `allowEmptyShould(true)`, so **if the package is renamed and the rule is not, it silently keeps passing against a package that no longer exists** — a security control quietly disarmed by a refactor. Update it in the same commit and confirm by pointing it at the new name.

### The enforcement rule — and an honest correction

The rule floated earlier ("no module javadoc may define itself by contrast with another module") **cannot be written in ArchUnit**: javadoc is stripped by the compiler and ArchUnit reads bytecode. Nothing mechanical can detect that two names are confusable — that is a human judgement.

What *is* implementable is a gate that forces the judgement to be made. Add to `src/test/java/dev/tushar/forge/ModularityTest.java`, reusing the existing `MODULES` field:

```java
/**
 * The module inventory, declared. Adding or renaming a module must edit this list.
 *
 * <p>No tool can tell that two names are confusable — {@code githubauth} and {@code githubapp}
 * were both accurate and together unreadable. So the gate is a human one: this test fails until
 * someone writes the new name down next to its neighbours, which is the moment to ask whether it
 * can be mistaken for any of them.
 *
 * <p>Deliberately closed, for the same reason the transition table in §10.3 is closed: safety
 * here comes from concentration, not extensibility.
 */
@Test
void moduleNamesAreDeliberate() {
    assertThat(MODULES.stream().map(ApplicationModule::getName))
            .containsExactlyInAnyOrder(
                    "api", "audit", "githubinstallation", "githublogin", "iam", "platform", "tenancy");
}
```

Verify the exact nested-module name string against the existing `printModuleStructure` output before pinning it — `platform.tenancy` may render as `tenancy`.

This would not have *caught* `githubauth`/`githubapp` automatically, and claiming otherwise would be overselling it. It puts the standard in front of whoever adds module #8, which is the most any rule can do here.

### Deferred, deliberately

- **`IamQueries`** sits beside `SessionService` and `UserProvisioningService` — two `*Service` and one `*Queries`, and "Iam" reads as "I am". Real, minor, and better fixed when the module next changes.
- **The `Forge` prefix** is inconsistent: it earns its place on `ForgePrincipal` (shadows `java.security.Principal`) and `ForgeOAuth2UserService` (shadows Spring's), but `ForgeSessionCookie` shadows nothing.
- **Sub-packaging `api/` by resource** — still queued for Task #15, when installation endpoints make it two real resources.

### Verification

- `./gradlew test` — expect **43** tests, unchanged and green. No behaviour changes; this is a rename plus one new assertion (44 with `moduleNamesAreDeliberate`).
- `ModularityTest.printModuleStructure` output must show the new names with the same `+` exposed / `o` hidden shape as before — in particular `githubinstallation` still exposing only `InstallationTokenService` and `TokenScope`.
- Confirm `sandboxCannotReachGithubCredentials` was really updated: temporarily point it at `ROOT + ".githubinstallation.."` with `allowEmptyShould(false)` and check it errors on the empty set rather than passing vacuously, then restore `true`. The `sandbox` module does not exist yet, so a passing rule proves nothing on its own.
- `grep -rn "githubauth\|githubapp" src` should return only the intentional `GithubApp*` class names and the `forge.github.app` config prefix.

---

## Addendum — token-cache eviction is not installation-scoped

**Status: implemented, 46 tests green.** Test was watched failing against the old implementation before the fix landed.

### Context

A health check after the renames found nothing broken by them — the full context boots against real Postgres, 44 tests green, no stale references — but reading `githubinstallation/InstallationTokenService.java:100-107` closely turned up a **latent bug in the code Task #15 is about to call**:

```java
public void evictAll(long installationId) {
    var keys = redis.keys(CACHE_PREFIX + "*");        // every installation, every workspace
    if (keys != null && !keys.isEmpty()) redis.delete(List.copyOf(keys));
    log.info("Evicted cached installation tokens after change to installation {}", installationId);
}
```

The parameter is used **only in the log line**. Three problems, in order of severity:

1. **Cross-tenant blast radius.** Suspending one customer's installation flushes every tenant's cached tokens. It fails safe — tokens are re-minted on the next request — so this is a correctness and cost problem, not a security hole. But §18's premise is that a per-workspace boundary holds at every layer, and here it does not.
2. **`KEYS` blocks the Redis event loop** and is O(*whole keyspace*), not O(matches). §5 puts queues, leases, rate limiters and SSE fan-out in the same Redis, so this scan gets worse exactly as the system gets busier.
3. **Installation-scoped eviction is impossible with the current key shape.** `TokenScope.fingerprint()` is a SHA-256 digest (`TokenScope.java:63`), so the installation id is *inside* the hash and cannot be matched on. The comment ("scope fingerprints are opaque, so the cheap path is a scan") is an accurate description of a design that boxed itself in.

**No callers exist today** — which is the argument for fixing it now. Task #15 (installation binding, `installation.suspended` / `installation_repositories` webhooks) is the first caller, and would inherit a method whose signature lies about what it does.

### The fix — `githubinstallation/InstallationTokenService.java`

**Put the installation id in the key** so eviction can match on it. `fingerprint()` already includes the id in the hashed input, so uniqueness is unaffected; this only makes the key *enumerable*:

```
forge:ghtok:{installationId}:{fingerprint}      // was forge:ghtok:{fingerprint}
```

**Rename `evictAll` → `evict`** and scope it, using `SCAN` via the cursor `StringRedisTemplate` already exposes:

```java
/** Drops cached tokens for one installation — used when it is suspended or uninstalled. */
public void evict(long installationId) {
    ScanOptions options = ScanOptions.scanOptions()
            .match(CACHE_PREFIX + installationId + ":*")
            .count(256)
            .build();
    List<String> doomed = new ArrayList<>();
    try (Cursor<String> cursor = redis.scan(options)) {
        cursor.forEachRemaining(doomed::add);
    }
    if (!doomed.isEmpty()) {
        redis.delete(doomed);
    }
    log.info("Evicted {} cached tokens for installation {}", doomed.size(), installationId);
}
```

`SCAN` is incremental and non-blocking. It may miss a key added mid-scan, which is harmless here: a missed eviction costs one stale-but-valid token for at most the remaining TTL, and the token is already narrowly scoped.

**Also derive the TTL from GitHub's response.** `TokenResponse.expires_at` (`InstallationTokenService.java:54`) is parsed and never read, while `CACHE_TTL` hardcodes 50 minutes next to a comment asserting GitHub tokens last 60. Two sources of truth for one fact, one of them an assumption. Cache until `expires_at` minus ten minutes' headroom, floored so a surprise short-lived token cannot produce a negative TTL. Flagged separately because it is a behaviour change, not just a rename — veto it and keep the constant if you would rather not couple to the response.

### Test — `src/test/java/dev/tushar/forge/githubinstallation/InstallationTokenServiceTest.java`

Extends `support/AbstractIntegrationTest.java`, which already provides the singleton Redis container. **No GitHub stub needed** — seed the cache directly and exercise only eviction, so the test has no network dependency:

```
seed  forge:ghtok:111:aaa, forge:ghtok:111:bbb, forge:ghtok:222:ccc
evict(111)
assert 111's keys are gone and 222's survives
```

That is the assertion the current implementation fails, which is the point of writing it. The bean autowires cleanly with unconfigured properties — `GithubAppJwtServiceTest:89` already relies on `GithubAppProperties(null, …)` constructing — so the token-minting path never has to be reached.

### Commits — four, in this order

| # | Scope |
|---|---|
| 1 | `refactor(api): /api/session replaces /api/me` |
| 2 | `test(arch): assert nothing depends on the api module` |
| 3 | `refactor: githubauth→githublogin, githubapp→githubinstallation` (19 files; stands alone so the rename is reviewable as a rename) |
| 4 | `fix(githubinstallation): scope token-cache eviction to one installation` |

### Verification

- `./gradlew test` — expect **45** tests, all green.
- The new test must be seen to fail before the fix: run it against the current `evictAll` first and confirm the `222` key is wrongly deleted.
- `grep -rn "\.keys(" src` returns nothing — `KEYS` is gone from the codebase.
- `ModularityTest.printModuleStructure` still shows `githubinstallation` exposing only `InstallationTokenService` and `TokenScope`.

### Housekeeping, unrelated to the code

- **The task list has drifted:** #12 is titled "Add /api/me endpoint" (renamed), #17 "Reorganise modules" is complete but still `in_progress`, and #14 is `in_progress` though the JWT and token-minting work it describes is done — only the installation flow (#15) remains.
- **`/tmp/mjar`** is leftover scratch from inspecting the Modulith jar to find `getIdentifier()`. Outside the repo, but mine to clean up.

### Still open, deliberately

- Installation tokens are cached in Redis **in plain text**, pending `platform.crypto`. Documented in the class javadoc; unchanged by this fix.
- `SessionController` and the token-minting path have no tests. `TokenScope.fingerprint()` is well covered (`TokenScopeTest`, 7 cases) but nothing verifies it end-to-end through the service.
- `IamQueries` naming, the inconsistent `Forge` prefix, and sub-packaging `api/` by resource — all deferred as agreed.

---

# Task #15 — Installation binding with anti-hijack verification

## Context

Phase 1.6 can currently mint a scoped installation token but has no way to *acquire* an installation: nothing writes `github_installations`. This task closes the loop — a logged-in user installs the GitHub App, GitHub redirects back, and Forge binds that installation to their workspace only after proving they are entitled to it.

The attack this exists to stop (§7): installation ids are smallish integers that leak into logs and URLs. Without verification, anyone who learns one can hit the setup callback and bind a stranger's installation into their own workspace, gaining agent access to repositories they do not own. **The state nonce does not prevent this** — an attacker can generate a perfectly valid nonce bound to their own session and then substitute a victim's `installation_id`. The nonce stops CSRF; only an ownership check stops id substitution.

### A contradiction in §7, resolved

§7 asks us to "verify the current user is an admin/owner of that installation's account" while also refusing the `Members` and `Organization` App permissions. **Those are incompatible.** GitHub App user-to-server tokens carry no OAuth scopes — their reach is defined by the App's own permissions — so neither `GET /user/memberships/orgs/{org}` nor `read:org` is available to us.

Decision: **personal-account installations only in this task.** `account_type == "User"` is verifiable exactly, with no extra permission and no stored token, by comparing the installation's `account.id` against the GitHub numeric id already in `user_identities.provider_user_id`. `Organization` installs are **rejected and audited**, not silently accepted. Org support becomes its own task where the access-vs-admin tradeoff gets decided in the open rather than buried in an implementation.

This is a real product limitation, not a nicety — most design partners will want org repos — so the rejection message must say so plainly rather than reading like a bug.

## Prerequisite discovered while reading: sessions have no workspace

`Session.selectWorkspace()` (`iam/internal/session/Session.java:85`) has **zero callers**. `SessionService.issue()` never sets a workspace, so `sessions.workspace_id` is always NULL and `ForgePrincipal.activeWorkspaceId` has been null since it was written — `/api/session` has been returning null for it all along.

Binding needs a target workspace, and `TenantScope.runInTenant(null, …)` throws `MissingTenantContextException`. So this is a blocker, not a nice-to-have.

**Fix in `iam/SessionService.java`:** resolve the user's default workspace at issue time and call `selectWorkspace`. Keep the public signature `issue(userId, userAgent)` unchanged — the caller in `githublogin/internal/SecurityConfig.java:87` should not learn about workspace resolution. `SessionService` gains `WorkspaceRepository` (already module-internal, `findAllForUser` exists and is ordered). Every user has exactly one workspace today because `UserProvisioningService.createNew` makes a personal one; pick deterministically anyway (OWNER first, then first by name) so multi-workspace users do not get an arbitrary one later.

## Design

### Flow

```
GET /api/installations/start           (authenticated)
  → mint nonce, store in Redis bound to this session, 15 min, single-use
  → 302 to https://github.com/apps/{slug}/installations/new?state={nonce}

  … user picks account + repositories on GitHub …

GET /api/installations/callback?installation_id=&setup_action=&state=
  1. consume nonce (GETDEL) — must exist, and map to THIS session id
  2. GET /app/installations/{id} with the App JWT
       — authoritative; the query parameter is an assertion, not evidence
  3. account_type must be "User"        → else 409 + audit, org not yet supported
  4. account.id must equal the caller's user_identities.provider_user_id
                                        → else 403 + audit  ← the anti-hijack check
  5. insert github_installations        → unique(installation_id) rejects rebinding
  6. audit INSTALLATION_BOUND, redirect
```

Step 2 is the one that must not be skipped for convenience: everything after it reasons about GitHub's answer, never the caller's parameter.

### Files

| File | Role |
|---|---|
| `api/installation/InstallationController.java` | **new** — `start` + `callback`, no business logic |
| `api/session/SessionController.java` | **moved** — `api/` now has two resources, so the by-resource split agreed earlier lands here |
| `githubinstallation/InstallationBindingService.java` | **new**, public — the verification chain above |
| `githubinstallation/InstallationBinding.java` | **new**, public record — the bound result as other modules see it |
| `githubinstallation/internal/GithubAppClient.java` | **new** — owns the `RestClient`, exposes `fetchInstallation(long)` and the token mint |
| `githubinstallation/internal/InstallationSetupNonces.java` | **new** — Redis nonce issue/consume |
| `githubinstallation/internal/GithubInstallation{,Repository}.java` | **new** — JPA entity + Spring Data repository |
| `githubinstallation/InstallationTokenService.java` | delegate HTTP to `GithubAppClient`, keep the caching |
| `iam/IamQueries.java` | **+** `Optional<String> githubUserId(UUID userId)` |
| `iam/SessionService.java` | select default workspace at issue time |
| `audit/AuditLog.java` | **new**, minimal — first real auditable action needs somewhere to go |

**Reuse rather than rebuild:** `TenantScope.runInTenant` for every write (RLS `WITH CHECK` rejects inserts otherwise — this already bit us once when seeding audit rows); `GithubAppJwtService.mintAppJwt()` for step 2; `TokenScope.readOnly/contribute` unchanged; `AbstractIntegrationTest` for the Postgres + Redis containers.

**Extract the `RestClient`.** `InstallationTokenService` currently builds its own with `apiBaseUrl`, `Accept` and `X-GitHub-Api-Version` headers. The new call needs the identical setup, and two copies is how one of them silently misses a version bump. `GithubAppClient` owns transport; `InstallationTokenService` keeps caching. That split is worth having on its own.

**Package layout:** keep `githubinstallation/internal` flat at ~6 files. When #16 adds repository and managed-repository entities it reaches ~10, which is when the by-aggregate split (`installation/`, `repository/`) earns itself. Structure when there is content to justify it.

### Notes that will otherwise cost an hour each

- **`ddl-auto: validate` is strict** — the `citext` incident is precedent. `permissions` and `events` are `jsonb`; map with `@JdbcTypeCode(SqlTypes.JSON)`. If validation objects, map them as `String` rather than weakening validation.
- **The callback sits under `/api/**`**, so `SecurityConfig` already requires authentication. A session that expires mid-install yields a bare 401 rather than a login redirect. Acceptable for alpha; note it, do not fix it here.
- **`setup_action`** is `install` or `update`; treat `update` as a no-op re-sync rather than an error.
- New config: `forge.github.app.setup-redirect` (default `/`) for where to send the browser afterwards. `slug` already exists and builds the install URL.

## Verification

- `./gradlew test` — expect **~53** tests green.
- **The anti-hijack test is the point of the task.** Bind installation `A` owned by GitHub user `1`; then, as a user whose `provider_user_id` is `2`, replay the callback with `A`'s id and a nonce legitimately minted for user 2's own session. Must be rejected with an audit row, and `github_installations` must be unchanged. A test that only exercises the happy path proves nothing here.
- Nonce: reuse rejected (single-use), foreign session rejected, absent rejected.
- Organization `account_type` rejected with the "not yet supported" path, not a stack trace.
- Rebinding an already-bound `installation_id` to a second workspace fails at the **database** unique constraint, not only in application code — assert the constraint violation specifically, since that is the guarantee §7 relies on.
- `/api/session` now returns a non-null `activeWorkspaceId` — the regression test for the prerequisite fix.
- `ModularityTest.moduleNamesAreDeliberate` still passes; `api.installation` is a sub-package, not a new module.

## Out of scope — Task #16

Repository sync (`GET /installation/repositories`) and `ManagedRepository` opt-in. Deliberately separate: the verification chain is the security-critical piece and deserves to be reviewable without a repo-sync loop diluting the diff.

---

# Addendum — closing gap §1.1, and the local-setup facts needed before real credentials

## Context

Tasks #15 and #16 are done (66 tests green) and `docs/known-gaps.md` records what was deferred. The next step is the first run against a real GitHub App, and two things block it.

**The blocker in the code (§1.1).** `GithubAppClient.fetchInstallation` swallows every 4xx and returns `Optional.empty()`. That is correct for 404 — the binding flow must not tell a caller whether an installation id exists — but it treats **401 identically**. A 401 means GitHub is rejecting *Forge's own App credentials*: wrong `app-id`, a revoked key, a well-formed key belonging to a different App, or clock skew. Today that presents to every user as `403 NOT_YOUR_ACCOUNT` / `UNKNOWN_INSTALLATION`, with nothing in the logs pointing at the real cause. It is the most likely first-run failure and the hardest one to diagnose.

Worth being precise about the scope: a *malformed* key already fails loudly — `GithubAppJwtService.parsePrivateKey` detects PKCS#1 and throws with the exact `openssl` command. The silent case is specifically a key that parses fine and GitHub refuses. The other three GitHub calls (`listRepositories`, `mintDiscoveryToken`, `mintInstallationToken`) register no `onStatus` handler, so `RestClient` already throws on 4xx there. `fetchInstallation` is the only silent path.

**The blocker outside the code.** No frontend exists yet (`/home/user/Self/Forge/forge-frontend` is an empty directory), so the App and OAuth App must be configured entirely against the backend on `localhost:8080`. Which of GitHub's URL fields are load-bearing versus cosmetic is not written down anywhere, and getting the callback URLs wrong is a silent-ish failure too.

A second, newly found trap that will bite the manual test on step one is recorded in §4 below.

## 1. Fix §1.1 — `githubinstallation/internal/app/GithubAppClient.java`

Add an SLF4J logger (matching the four already in the module) and replace the empty `onStatus` handler at line 78 with a status-aware one.

**401 throws; everything else still returns empty.** A 401 is not a fact about the requested installation — it is a fact about Forge's configuration, identical for every id — so surfacing it leaks nothing and is not an oracle. Letting it fall through to the rejection path is what makes a misconfigured Forge tell every user their installation is not theirs.

```java
public Optional<InstallationView> fetchInstallation(long installationId) {
    InstallationView view = restClient
            .get()
            .uri("/app/installations/{id}", installationId)
            .header("Authorization", "Bearer " + appJwt.mintAppJwt())
            .retrieve()
            .onStatus(HttpStatusCode::is4xxClientError, (request, response) ->
                    handleLookupFailure(installationId, response.getStatusCode()))
            .body(InstallationView.class);

    return Optional.ofNullable(view);
}

/**
 * What a 4xx on an installation lookup means, and who it is about.
 *
 * <p>Only 404 is routine. Collapsing the rest into it is what turned a wrong App key into
 * "that installation is not yours" — a message about the caller, for a fault that is ours.
 */
private void handleLookupFailure(long installationId, HttpStatusCode status) {
    if (status.value() == HttpStatus.UNAUTHORIZED.value()) {
        // Not about this installation: GitHub is refusing the App credentials themselves, so
        // no binding by any user can succeed. Thrown rather than returned, because it is a
        // server fault and the caller-facing rejection path would bury it.
        throw new IllegalStateException("GitHub rejected the Forge App credentials (401). Check "
                + "forge.github.app.app-id and the private key — a well-formed key belonging to a "
                + "different App fails exactly here.");
    }
    if (status.value() == HttpStatus.NOT_FOUND.value()) {
        // The ordinary answer for a guessed id. Callers must not be able to tell this from 403,
        // so only the server-side record distinguishes them.
        log.debug("GitHub does not know installation {}", installationId);
        return;
    }
    // Usually a suspended App or an exhausted rate limit. Both are operator concerns and both
    // were invisible while this was folded in with 404.
    log.warn("Installation lookup for {} refused with {}", installationId, status.value());
}
```

Nothing else changes. `InstallationBindingService` catches `RuntimeException` only around `repositorySync.sync()` and `DataIntegrityViolationException` only around persist, so the throw propagates to Spring's default handler as a 500 with no controller change needed.

One consequence to accept, not fix: the nonce is consumed before `fetchInstallation` runs, so a 401 spends it and the user must restart the install flow. Single-use is the security property; weakening it to be tidy on a path that should never happen is the wrong trade.

## 2. Test support — `src/test/java/dev/tushar/forge/support/FakeGithub.java`

Add a registry of installation ids that answer 401, so the case is exercised without a global toggle that could bleed across tests sharing the static server:

```java
private static final Set<Long> UNAUTHORIZED = ConcurrentHashMap.newKeySet();

/** An installation id GitHub answers with 401 — the shape of a rejected App key. */
public static long unauthorized() {
    long id = NEXT_ID.incrementAndGet();
    UNAUTHORIZED.add(id);
    return id;
}
```

In `handleInstallations`, check `UNAUTHORIZED` in the non-token branch only (the `/access_tokens` branch returns early above it) and `respond(exchange, 401, "")`.

## 3. Test — `InstallationBindingServiceTest`

One test, beside the existing `unknownInstallationIsRejected` which already pins the 404 behaviour and must stay green:

```java
@Test
@DisplayName("GitHub rejecting Forge's own credentials is a server fault, not a user rejection")
void badAppCredentialsFailLoudly() {
    long installationId = FakeGithub.unauthorized();
    String nonce = bindings.beginSetup(sessionId);

    assertThatThrownBy(() -> bindings.completeSetup(installationId, nonce, sessionId, userId, workspaceId))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("401");
}
```

Per the standard used for the anti-hijack and discovery-never-implies-consent guarantees: run this against the current implementation first and watch it fail (it returns `Rejected(UNKNOWN_INSTALLATION)` rather than throwing) before the fix lands.

## 4. Newly found gap — `cookie-secure` defaults to `true`

`SecurityConfig.java:24` defaults `forge.security.cookie-secure` to `true`, and `ForgeSessionCookie.write` sets `Secure` from it. Over plain `http://localhost:8080`, **`curl` will not send a `Secure` cookie back**, so every authenticated step of the §7 manual checklist fails with a bare 401 that looks like broken auth. Browsers are more forgiving (Chrome and Firefox permit `Secure` on `localhost`), which makes this worse — it will work in the browser and fail in `curl`, in the same session.

**No yaml change.** Defaulting to insecure so local testing is convenient is exactly how it ships that way. Run the app with the override instead, and record it in the setup doc:

```
SPRING_APPLICATION_JSON='{"forge":{"security":{"cookie-secure":false}}}' ./gradlew bootRun
```

## 5. New file — `docs/local-setup.md`

Answers the question this addendum started from. **The rule: a URL GitHub *redirects the browser to* must be the backend; a URL GitHub merely *displays* is cosmetic.** Homepage URL is in the second category — it appears on the consent screen and the App's public page and never participates in either flow.

**OAuth App** (identifies humans — `read:user`, `user:email`):

| Field | Value | Load-bearing? |
|---|---|---|
| Homepage URL | `http://localhost:8080` | No — cosmetic. Becomes the frontend URL when one exists |
| Authorization callback URL | `http://localhost:8080/login/oauth2/code/github` | **Yes** — Spring Security owns this path |

**GitHub App** (grants the agent authority):

| Field | Value | Load-bearing? |
|---|---|---|
| Homepage URL | `http://localhost:8080` | No — cosmetic |
| Setup URL | `http://localhost:8080/api/installations/callback` | **Yes** — `InstallationController.callback` |
| Redirect on update | ticked | Yes — makes `setup_action=update` come back through the same check |
| Webhook | **Active unchecked** | Not built until Phase 6 |
| Install scope | Only on this account | Org installs are refused by design (§4.1) |

Permissions: Metadata read; Contents, Pull requests, Issues read+write; Checks, Commit statuses, Actions read. **Not Workflows** — that omission is what prevents the agent editing the CI config that grades its own work.

Environment (never in `application.yaml` — the App private key can act as the App on every account it is installed on):

```bash
export GITHUB_OAUTH_CLIENT_ID=...
export GITHUB_OAUTH_CLIENT_SECRET=...
export FORGE_GITHUB_APP_APP_ID=...
export FORGE_GITHUB_APP_SLUG=...
export FORGE_GITHUB_APP_PRIVATE_KEY_PEM="$(cat github-app.pkcs8.pem)"
openssl pkcs8 -topk8 -inform PEM -outform PEM -nocrypt \
  -in github-app.private-key.pem -out github-app.pkcs8.pem   # JDK cannot read GitHub's PKCS#1
```

Confirm `*.pem` is gitignored before the key lands in the working tree. Verify the exact `FORGE_GITHUB_APP_*` names against `GithubAppProperties` binding while writing this file rather than trusting the list above.

Also document where the user lands *after* each flow — `forge.security.login-success-redirect` and `forge.github.app.setup-redirect`, both currently `/`. These are Forge's own config, not GitHub's, and are the two knobs that will point at the frontend later. Leaving them at `/` now is deliberate: there is no dev server port to guess at, and no CORS bean is needed while everything is same-origin on 8080.

## 6. Update `docs/known-gaps.md`

- §1.1 — rewrite as fixed: 401 now throws with a diagnostic message, 403/404 remain indistinguishable to the caller and are logged apart server-side.
- §1 — add the `cookie-secure` trap from §4 above, with the override command.
- §5 — the "no real-GitHub smoke test" row stands; add that `FakeGithub` now covers 401.
- §7 — add a checklist item: run once with a deliberately wrong `FORGE_GITHUB_APP_APP_ID` and confirm a 500 plus the credentials message, rather than a 403.
- Fix the stray `**` at the start of line 1 and end of the last line — leftover bold markers that break rendering.

## Verification

- `./gradlew test` — expect **67**, up from 66, all green.
- Watch `badAppCredentialsFailLoudly` fail against the unfixed client before applying the fix; a test for a diagnostic path that was never seen failing proves nothing.
- `unknownInstallationIsRejected` must still return `Rejected(UNKNOWN_INSTALLATION)` — the 404 contract is unchanged, and this is the regression guard for it.
- `ModularityTest` and `AbstractionHygieneTest` unaffected; no new type, no new module, no interface.
- Manual, once credentials exist: start the app with a correct App id, complete an install, confirm binding; then restart with `FORGE_GITHUB_APP_APP_ID` set to a wrong number and confirm the callback returns 500 and the log names the credentials — not `403`.

## Housekeeping

Task list has drifted: #15 and #16 are both committed but still show `pending`.

---

# Follow-up — `local-setup.md` conflates "required" with "load-bearing"

**Status: implemented.** Superseded in part by the next section, which corrects a field name this
one left untouched.

## Context

`docs/local-setup.md` was written this session and immediately misled the person it was written for.
It labels Homepage URL **"cosmetic"** and says "anything resolvable is fine", so on hitting GitHub's
required-field asterisk the reasonable conclusion was that the doc must be wrong.

Both claims are sloppy in opposite directions:

- Homepage URL **is required** on both the OAuth App and GitHub App forms. "Cosmetic" answered *does
  Forge read this* when the reader was asking *must I fill this in*.
- "Anything resolvable" overstates it — GitHub validates that the value parses as a URL and never
  fetches it. Resolvability is not checked at all.

The distinction the doc needs, and currently collapses into one column:

> **Required** — the form will not submit without it.
> **Load-bearing** — GitHub redirects a browser here, so a wrong value breaks the flow.

Homepage URL is required on both forms and load-bearing on neither. Only the OAuth callback URL and
the App's Setup URL are load-bearing.

## Change — `docs/local-setup.md` only

1. **Rewrite the "The rule for every URL field" section** to lead with required-vs-load-bearing
   rather than redirects-vs-displays. Keep the redirect rule as the *test* for load-bearing, since
   that is the useful heuristic. State plainly that Homepage URL is required, displayed only, never
   fetched, and need not resolve. Offer `http://localhost:8080` or the repository URL, noting the
   latter is the more honest value for a field whose only job is to be displayed.

2. **Retitle the third column of both tables** from `Load-bearing?` to `What it does`, and replace
   the `**No** — cosmetic` entries:

   | Field | Value | What it does |
   |---|---|---|
   | Homepage URL | `http://localhost:8080` | Nothing — required by the form, displayed only |
   | Authorization callback URL | `…/login/oauth2/code/github` | **Load-bearing** — Spring Security owns this path |

   Same treatment for the GitHub App table's Homepage URL and Setup URL rows.

3. **Sharpen the opening paragraph.** "asks for five URLs and only three of them do anything" has
   the right instinct but reads as a grumble. Make it the thesis: most of these fields are required
   and inert, and knowing which two are not is the entire point of the document.

No code, no tests, no other file. The `known-gaps.md` cross-link added earlier stays valid.

## Verification

Not a testable change; the check is a reading one. Someone filling in both GitHub forms with only
this document open should never have to guess whether a required field matters — the Homepage URL
row must answer both "what do I type" and "does it matter" without the reader inferring either.
`./gradlew test` should still report **67**; nothing under `src/` is touched.

---

# Follow-up 2 — `local-setup.md` names a field GitHub has renamed, and omits two traps

**Status: implemented.**

## Context

Walking the forms against the doc turned up one error and three omissions. The error is the
expensive one: the doc calls the OAuth App's critical field **"Authorization callback URL"**, which
is what GitHub used to call it. The form now says **"Redirect URIs"** — plural, a repeatable list of
up to 10, with the single entry's label rendered as "Redirect URI". A reader scanning for the old
name does not find it, and the one genuinely load-bearing field on that form is the one they end up
guessing at.

Verified while checking: `application.yaml` sets no `redirect-uri`, so Spring Security uses the
default template `{baseUrl}/login/oauth2/code/{registrationId}` with `registrationId: github` —
`http://localhost:8080/login/oauth2/code/github`, no trailing slash.

The three omissions are all fields the doc says nothing about, two of which look like they need
filling in and must not be:

- The **GitHub App's own Redirect URI** and **"Request user authorization (OAuth) during
  installation"**. Both must stay blank/unchecked — Forge identifies humans through the separate
  OAuth App. The Redirect URI field in particular sits on the App form looking exactly like the one
  that *is* required on the OAuth form. Enabling the OAuth-during-installation checkbox is Option 1
  from the deferred org-support decision (§4.1) and would change the install flow.
- **Allow wildcard matching** on the OAuth redirect. Must stay off: a wildcard redirect means any
  matching subdomain or path can receive the authorization code.
- **Generating the private key.** The doc explains converting PKCS#1 → PKCS#8 but never says where
  the key comes from. It is at the bottom of the App's General page, and it is easy to finish the
  form believing setup is complete without one.

## Change — `docs/local-setup.md` only

1. **OAuth App table** — rename the row to `Redirect URI`, noting GitHub previously called this
   "Authorization callback URL" so anyone following an older guide can reconcile the two. Add rows
   for `Allow wildcard matching` (off, with the reason) and `Expire user access tokens` (irrelevant
   — Forge discards the user token after the profile fetch and never stores it, per §6).

2. **GitHub App table** — add the two leave-them-alone rows, `Redirect URI` (blank) and `Request
   user authorization (OAuth) during installation` (unchecked), each saying *why* rather than just
   what, since "blank" without a reason invites someone to helpfully fill it in later.

3. **New step: generate the private key.** Fold into the existing private-key section — where the
   button is, that it downloads PKCS#1, then the existing `openssl` conversion.

4. **New step: find your slug.** `FORGE_GITHUB_APP_SLUG` is currently described as "URL slug, e.g.
   forge-tushar" with no way to obtain it. Say to read it off the App's public page URL
   (`https://github.com/apps/<slug>`), and why it matters: `InstallationController.start` builds the
   install URL from it, so a wrong slug is a 404 at the moment the user clicks Connect.

5. **Note the unused credentials.** A GitHub App also issues a Client ID and client secret. Forge
   uses neither — App auth is private key → JWT — and saying so prevents someone wiring the client
   secret in somewhere on the assumption it must be needed.

No code, no tests, no other file.

## Verification

Reading check again, but a sharper one than last time: every field visible on either registration
form should appear in the doc, including the ones whose correct value is "leave it alone". The
specific regression to avoid is a field name that no longer matches GitHub's UI, so the OAuth row
must carry both the current name and the old one. `./gradlew test` still reports **67**; nothing
under `src/` is touched.

---

# Task A — make local configuration actually load, and finish GitHub setup

## Context

Setup stalled on two things a doc could not fix by describing harder.

**`.env` is inert.** A `.env` was created at the repo root holding four variables, and nothing reads
it. Spring Boot has no native `.env` support, and there is no dotenv dependency in `build.gradle`,
nothing in `docker-compose.yml`, and nothing in the app. `local-setup.md` says `export FORGE_…`
without ever saying how those exports are meant to happen, so reaching for a `.env` was the obvious
move and it silently does nothing.

**The private key had not been generated.** GitHub creates none when the App is created; it is a
button on the General page under a *Private keys* heading, below the Webhook section. "I can't see
any private key" was accurate — there was nothing to see. The doc says how to *convert* the key but
originally never said where it comes from (partly fixed in Follow-up 2; the remaining gap is that a
reader still has to scroll past what looks like the end of the form).

Current `.env` versus what Forge reads:

| Variable | Verdict |
|---|---|
| `GITHUB_OAUTH_CLIENT_ID` / `_SECRET` | correct — literal placeholders in `application.yaml` |
| `GITHUB_APP_CLIENT_ID` / `_SECRET` | **unused** — App auth is private key → JWT, never the client secret. Remove |
| `FORGE_GITHUB_APP_APP_ID` | missing — `4602643` |
| `FORGE_GITHUB_APP_SLUG` | missing — read from `https://github.com/apps/<slug>` |
| `FORGE_GITHUB_APP_PRIVATE_KEY_PEM` | missing — key not generated yet |

Boot-time behaviour is acceptable and needs no code change: `GithubAppJwtService:49-52` throws
`"GitHub App is not configured: set forge.github.app.app-id and forge.github.app.private-key-pem"`
on first use. Legible, actionable, already tested. No startup validator required.

## Change

**1. `scripts/dev.sh`** — new, executable. The three things that must happen together and are easy
to get wrong individually:

```bash
#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

[ -f .env ] || { echo "No .env — see docs/local-setup.md"; exit 1; }
set -a; . ./.env; set +a          # -a exports everything the file defines

# Secure cookies are withheld by curl over plain HTTP; see known-gaps.md §1.2.
# Overridden here rather than weakened in application.yaml.
exec ./gradlew bootRun --args='--forge.security.cookie-secure=false'
```

Sourcing rather than parsing is deliberate: it makes `FORGE_GITHUB_APP_PRIVATE_KEY_PEM="$(cat
github-app.pkcs8.pem)"` work inside `.env`, so the key stays in its own gitignored file instead of
being pasted as one long line into a second one.

Use `--args` rather than `SPRING_APPLICATION_JSON` — same effect, far more readable, and it shows up
in the process list where someone debugging can see it.

**2. `.env.example`** — new, committed. Names only, no values, with the App-client trap called out:

```bash
GITHUB_OAUTH_CLIENT_ID=
GITHUB_OAUTH_CLIENT_SECRET=
FORGE_GITHUB_APP_APP_ID=
FORGE_GITHUB_APP_SLUG=
FORGE_GITHUB_APP_PRIVATE_KEY_PEM="$(cat github-app.pkcs8.pem)"
# Not needed: a GitHub App also issues a Client ID and secret. Forge uses neither.
```

Confirm `.gitignore` already covers `.env` and `.env.*` but **not** `.env.example` — the current
pattern `.env.*` would swallow it, so add a `!.env.example` negation.

**3. `docs/local-setup.md`** — add a "Running it" rewrite: `cp .env.example .env`, fill it, run
`./scripts/dev.sh`. State plainly that Spring Boot does not read `.env` and that the script is what
loads it, so nobody later removes the script assuming the framework handles it. Fold the existing
`cookie-secure` paragraph into the script's rationale rather than repeating the raw command.
Sharpen the private-key step: GitHub generates none, keep scrolling past the Webhook section.

## Verification

- `./scripts/dev.sh` with an incomplete `.env` reaches the `GithubAppJwtService` message above
  rather than failing obscurely.
- With it complete, the app boots and `GET /api/session` over `curl` returns a session — this is the
  end-to-end proof that both the env loading and the cookie override work, and the first item on the
  `known-gaps.md` §7 checklist.
- `git status` shows `.env.example` tracked and `.env` untracked.
- `./gradlew test` still **67**; nothing under `src/` changes.

---

# Task B — rename Forge → ForgeStack (deferred until Task A is verified)

## Context

The registered apps are "ForgeStack OAuth" and "ForgeStack App"; the codebase says Forge
throughout. Decision taken: **ForgeStack is the product name and the codebase follows.**

**Sequencing: do this after Task A proves the GitHub flow works end to end.** The rename changes
`FORGE_GITHUB_APP_*` to `FORGESTACK_GITHUB_APP_*`, so doing it first means writing `.env` twice —
but that is the small reason. The real one is that renaming 82 files before the credentials have
ever succeeded means a first failure is ambiguous between "the rename broke it" and "the credentials
are wrong". Verify against a known-good baseline, then rename. Cost of deferring: three variable
names.

## Blast radius (surveyed, not estimated)

| Surface | Extent |
|---|---|
| Package root `dev.tushar.forge` | 82 files |
| Class names | `ForgeApplication`, `ForgeApplicationTests`, `ForgeOAuth2UserService`, `ForgePrincipal`, `ForgeSessionAuthenticationFilter`, `ForgeSessionCookie` |
| Config prefix `forge.*` | `forge.role`, `forge.github.app.*`, `forge.security.*` — and every matching env var |
| Database | `jdbc:…/forge`, roles `forge_app` / `forge_migrator`, Hikari `forge-pool` |
| Redis prefixes | `forge:ghtok:`, `forge:ghsetup:` |
| Docs | `local-setup.md`, `known-gaps.md`, plus this plan |

## Open decisions to settle when Task B starts

Not settled now, because each has a real cost and none blocks Task A:

1. **Does the database rename too?** `forge` → `forgestack` plus both roles means recreating the
   local database and editing `docker-compose.yml`. Purely cosmetic; the alternative is a permanent
   small inconsistency between product name and DB name, which is extremely common and harmless.
2. **Does the config prefix become `forgestack.*`?** Consistent, but every property key and env var
   gets four characters longer for no functional gain.
3. **Do the `Forge*` classes become `ForgeStack*`?** `ForgeStackSessionAuthenticationFilter` is a
   mouthful. The prefix exists only where it disambiguates from a framework type
   (`ForgePrincipal` vs `java.security.Principal`), so a short internal prefix is defensible.

Redis prefixes are safe to change whenever — the cache is derived state and losing it costs nothing.

## Verification

`./gradlew test` at **67** before and after, with `ModularityTest.moduleNamesAreDeliberate` updated
to the new package root. The rename is correct only if the test count and the module inventory are
identical on both sides; any change in either means something was renamed that should not have been.

---

# Task C — first real installation, and the §7 checklist

**Status: BLOCKED by Task D.** The first browser login succeeded and then every `/api/**` call
returned HTTP 500. Task D fixes that; the checklist below resumes unchanged afterwards.

## Context

Tasks A and B are done: the app boots, `.env` loads via `scripts/dev.sh`, and the rename landed at
67 tests green. Phase 1.6 has been *written* and unit-tested against `FakeGithub`, but **no line of
it has ever run against real GitHub**. Everything below exists to change that.

Two blockers were reported last session. One is now cleared, verified against the live API by
minting an App JWT from `forgestack-app.pkcs8.pem` and calling `GET /app`:

| Check | Before | Now |
|---|---|---|
| App permissions | `{}` | `actions:read, checks:read, contents:write, issues:write, metadata:read, pull_requests:write, statuses:read` — the seven from §7, **no `workflows`** |
| `installations_count` | 0 | **0 — still the blocker** |

The permission set is exactly right and needs no further edit. What remains is a browser action
plus the verification pass it unlocks.

## The one thing that will silently break the install

The App must be installed **through ForgeStack**, not from GitHub's own app page.

`InstallationBindingService.completeSetup:90-93` consumes a single-use nonce bound to the caller's
session, and a missing `state` fails closed with `INVALID_SETUP_STATE` → 400
(`InstallationController.statusFor:94`). Installing from `https://github.com/apps/forgestack-app`
directly produces a callback with no `state`, so GitHub records the installation and ForgeStack
records nothing — an inconsistency with no error message pointing at the cause.

The correct entry point is `GET /api/installations/start`
(`InstallationController.start:46-55`), which mints the nonce and builds the install URL from
`FORGESTACK_GITHUB_APP_SLUG` (`forgestack-app`, confirmed against the live API).

**Unverifiable remotely:** `GET /app` does not expose the App's Setup URL, so the one field that
cannot be checked from here is whether it reads
`http://localhost:8080/api/installations/callback`. If it is blank, GitHub never redirects back and
the binding never happens. Eyeball it on the App's General page before installing.

## Sequence

Steps 1–2 need a browser and are the user's; everything after is drivable from `curl` given the
session cookie.

1. Log in: `http://localhost:8080/oauth2/authorization/github`. Confirm the consent screen requests
   only `read:user` and `user:email` — the `GithubOAuthScopeTest` property, checked against the real
   screen for the first time.
2. Visit `http://localhost:8080/api/installations/start` in the same browser. Pick **QL-Tushar-Kumar**
   and a small number of repositories.
3. Export the session cookie and walk the `known-gaps.md` §7 checklist below.

## Checklist, and what each item actually proves

Ordered so the destructive items come last.

| # | Item | Proves |
|---|---|---|
| 1 | `GET /api/session` returns non-null `activeWorkspaceId` | The Task #15 prerequisite fix — `Session.selectWorkspace` had zero callers and this was null since it was written |
| 2 | Callback bound and redirected | The whole verification chain, first real run |
| 3 | `GET /api/repositories` lists the chosen repos, all `managed: false` | §4.2 — installation access ≠ ForgeStack maintenance |
| 4 | `POST /api/repositories/{id}/manage` flips exactly one | Opt-in is per repository |
| 5 | Change selection on GitHub → `POST /api/repositories/sync/{installationId}` | Resync reflects GitHub, not cached state |
| 6 | Remove a *managed* repo's access → status `ACCESS_LOST` | §7 lifecycle: silent failure here is the worst outcome |
| 7 | Replay the callback URL from history | Nonce is single-use |
| 8 | Hand-edit `installation_id` to another number | **The anti-hijack check.** Expect 403 + an `INSTALLATION_BIND_REJECTED` row in `audit_events` |
| 9 | Grep the boot log for the PEM body and OAuth secret | No credential reaches the log sink |
| 10 | Restart with a wrong `FORGESTACK_GITHUB_APP_ID`, replay the callback | **The §1.1 fix** — expect a 500 naming the credentials, not a 403. The only item `FakeGithub` cannot cover |

Items 8 and 10 are the two that justify the exercise. Everything else confirms the happy path; those
two confirm the guarantees, and per the standing rule a guarantee nobody has watched hold is one
nobody knows works.

Item 10 is last because it needs a restart with a deliberately broken `.env`, and item 6 is
second-to-last because it revokes access the earlier items depend on.

## Expected outcome

No code changes are anticipated. Anything this turns up gets a `known-gaps.md` entry and its own
fix commit, kept separate from the checklist run — the same discipline that kept the rename
reviewable. Checked boxes land in `docs/known-gaps.md` §7 in one commit at the end.

## Verification

`./gradlew test` still **67**. This task adds no tests: it exercises paths that are already covered
against `FakeGithub`, against the thing `FakeGithub` imitates.

---

# Task D — the OAuth servlet session shadows the ForgeStack session

**Status: implemented and committed (`6234e78`), 74 tests green.** Six of seven new tests were
watched failing first, three reproducing the browser's exact NPE. The token-retention finding was
corrected during implementation — the token was in Boot's autoconfigured
`InMemoryOAuth2AuthorizedClientService`, not the servlet session, so the first-guess assertion would
have passed without the fix. Written up as `known-gaps.md` §1.8 / §1.8b.

## Context

The first real browser login worked. The database proves it: one `users` row
(`QL-Tushar-Kumar`, `provider_user_id 213618763`), one `user_identities` row, and one `sessions`
row **with a non-null `workspace_id`** — which incidentally confirms the Task #15 prerequisite fix.

Sixteen seconds later, `GET /api/installations/start` returned a Spring Boot Whitelabel error page.
The log says why:

```
java.lang.NullPointerException: Cannot invoke
  "dev.tushar.forgestack.githublogin.ForgeStackPrincipal.sessionId()" because "principal" is null
```

**This is not a setup mistake. Every authenticated endpoint is unreachable from a browser** — all
seven, across `SessionController`, `InstallationController` and `RepositoryController`, which each
take `@AuthenticationPrincipal ForgeStackPrincipal`.

*(The Whitelabel page seen after step 1 was different and harmless: `login-success-redirect`
defaults to `/`, nothing maps `/`, so a successful login lands on a 404. Cosmetic until a frontend
exists.)*

### Root cause

Two authentication mechanisms are live at once, and the wrong one wins:

1. `SecurityContextHolderFilter` sits at **position 3** of the chain — verified against the Spring
   Security reference, and well before `ForgeStackSessionAuthenticationFilter`, which is registered
   `addFilterBefore(..., UsernamePasswordAuthenticationFilter.class)` at ~9. It restores the
   `OAuth2AuthenticationToken` that `oauth2Login` persisted into the servlet session.
2. `ForgeStackSessionAuthenticationFilter:38` guards on
   `SecurityContextHolder.getContext().getAuthentication() == null`. It is never null, so the filter
   **never reads the ForgeStack cookie**.
3. `authorizeHttpRequests` sees an authenticated request and admits it.
4. `AuthenticationPrincipalArgumentResolver` finds an `OAuth2User` where a `ForgeStackPrincipal` is
   declared. Per the Spring Security API docs, a type mismatch **returns null silently** unless
   `errorOnInvalidType` is set. Hence the NPE.

`SecurityConfig:58-60` already states the intent this code fails to implement:

> *The API is authenticated by the ForgeStack session cookie, not by a servlet session.*

The invariant that should hold and currently does not: **on `/api/**`, authenticated ⟺
`ForgeStackPrincipal`.** Every controller assumes those cannot diverge.

### Second finding — the GitHub user token is retained, but not where it looked

**Corrected after checking Boot 4.1's actual autoconfiguration.** The first guess was the servlet
session; it is wrong, and acting on it would have produced a test that passes vacuously.

`spring-boot-security-oauth2-client-4.1.0.jar` — on the classpath via
`spring-boot-starter-security-oauth2-client` — ships both
`OAuth2ClientConfigurations$OAuth2AuthorizedClientServiceConfiguration` and
`OAuth2ClientWebSecurityAutoConfiguration` (verified by listing the jar). Together they register an
`InMemoryOAuth2AuthorizedClientService` and an `AuthenticatedPrincipalOAuth2AuthorizedClientRepository`
over it. That repository routes to the **service** — not the session — whenever the principal is
authenticated, which it is when `OAuth2LoginAuthenticationFilter` calls `saveAuthorizedClient`.

So every GitHub user access token ForgeStack has issued is in a `ConcurrentHashMap` on the heap,
keyed by GitHub login, for the process lifetime. Nothing evicts it: the logout handler
(`SecurityConfig:54-57`) revokes the ForgeStack session and clears the cookie but never calls
`removeAuthorizedClient`.

Two consequences:

- §6's *"No GitHub user token is persisted"* is false as stated, and so is the
  `ForgeStackOAuth2UserService` class javadoc (*"used only to fetch the profile and is then
  discarded — it is never persisted"*). Both are true of **our** code and false of the framework's.
  Not configuring something is a decision, and this one was never made.
- Blast radius is bounded — the scopes really are only `read:user`/`user:email`
  (`GithubOAuthScopeTest` guards that), so a leaked token reads a profile and cannot touch code.
  Worth fixing, not worth alarm. Note GitHub issues no refresh token unless token expiration is
  enabled, which `local-setup.md` leaves optional, so assume access token only.

The assertion this implies is **not** "nothing in the servlet session" but
`OAuth2AuthorizedClientService.loadAuthorizedClient("github", login)` returning null — where the
principal name is the `login` attribute, per `ForgeStackOAuth2UserService:59`
(`new DefaultOAuth2User(authorities, enriched, "login")`).

### Why no test caught it

**Nothing in `src/test` authenticates a request.** `GithubOAuthScopeTest` inspects configured scopes
and is the only file under `githublogin/`; there are no tests under `api/`. This is the
`known-gaps.md` entry already ranked highest-value — *"no test boots the packaged application"* —
producing its fourth finding.

## Change — one commit

**1. Stop persisting the OAuth2 authentication** (`githublogin/internal/SecurityConfig.java`)

Add `.securityContext(sc -> sc.securityContextRepository(new NullSecurityContextRepository()))`.
Keep `SessionCreationPolicy.IF_REQUIRED`: the handshake still needs a session for the authorization
request, which `HttpSessionOAuth2AuthorizationRequestRepository` holds under a *different* attribute
and is unaffected. `STATELESS` would break the handshake — the existing comment says so and is right.

**2. Stop retaining the GitHub token** (same file, plus one small class)

New package-private `githublogin/internal/DiscardedGithubUserTokens.java` implementing
`OAuth2AuthorizedClientRepository` with three no-ops (`@Nullable` on the load return, matching the
project's existing jspecify usage). There is no built-in null implementation. Wire it in the DSL:

```java
.oauth2Login(oauth2 -> oauth2.userInfoEndpoint(userInfo -> userInfo.userService(oauth2UserService))
        .authorizedClientRepository(new DiscardedGithubUserTokens())
        .successHandler(issueForgeSession()))
```

`OAuth2LoginConfigurer.authorizedClientRepository` sets the shared object, so it beats Boot's
autoconfigured repository without touching autoconfiguration. Preferred over a `@Bean` override
because it sits where a reviewer of the login flow will actually look — beside `userInfoEndpoint`
and `successHandler`. Prevents the write rather than cleaning up after it, and makes §6 true.

**3. Delete the guard** (`githublogin/internal/ForgeStackSessionAuthenticationFilter.java`)

**Revised.** The earlier wording — change the guard to "not already a `ForgeStackPrincipal`" and
clear foreign authentication on `/api/**` — was wrong twice over.

*Wrong to keep any guard:* with step 1 in place nothing populates the context before this filter
(`AnonymousAuthenticationFilter` runs after it), so there is nothing to defer to. Simply delete the
`if (...getAuthentication() == null)` branch. That removes the exact line the NPE traces back to,
and with it the silently-do-nothing failure mode.

*Wrong to put path logic here:* "on `/api/**` only" is an authorization concern, and an
authentication filter that knows about URL patterns is the second copy of a rule that already has a
home. It goes in step 4 instead.

*Also rejected: making the cookie merely take **precedence**.* That leaves the OAuth2 session as a
fallback authenticator whenever the cookie is absent, expired, or revoked — which would quietly
break the promise in this filter's own javadoc that revocation "takes effect on the next request".
A browser holding `JSESSIONID` would stay logged in after `SessionService.revoke`.

Safe with respect to the callback either way: `AbstractAuthenticationProcessingFilter` does not
continue the chain after a successful authentication, so this filter never runs on
`/login/oauth2/code/github`.

**4. Require a ForgeStack principal at the authorization layer** (`SecurityConfig`)

Replace `.requestMatchers("/api/**").authenticated()` with `.access(forgeStackPrincipalRequired())`
— a private static `AuthorizationManager<RequestAuthorizationContext>` helper in the same class, in
the style of the existing `issueForgeSession()`. `authenticated()` is precisely the predicate that
an `OAuth2AuthenticationToken` satisfied on its way to a null principal; this states what the seven
endpoints actually need.

Steps 1 and 4 overlap deliberately. 1 removes today's cause; 4 makes the invariant
**authenticated ⟺ `ForgeStackPrincipal`** hold at the gate even if something later repopulates the
context — turning a future recurrence into a 403 instead of a 500 in seven places at once.

Preserves the behaviour documented in §1.5: an anonymous caller still yields a false decision, so
`ExceptionTranslationFilter` still calls the entry point and `/api/session` still 302s to
`/oauth2/authorization/github`.

**Not doing:** null checks in the seven controllers, or `errorOnInvalidType = true` on each
`@AuthenticationPrincipal`. With step 4 in place a null principal is unreachable, and seven
defensive copies of one rule is the noise Appendix A argues against.

## Test — extend `FakeGithub` to cover the login handshake

Chosen scope: **the full flow**, because the fast variant stubs past the handshake that broke.

`support/FakeGithub.java` currently serves only `/app/installations` and
`/installation/repositories`. Add GitHub's OAuth endpoints — the access-token exchange and `/user`
(plus `/user/emails`, per the `user:email` scope) — and point the provider at them in the test
context via `spring.security.oauth2.client.provider.github.token-uri` and `user-info-uri`.
`AbstractIntegrationTest` already has the `@DynamicPropertySource` hook.

Mechanism: **MockMvc with one `MockHttpSession` passed to every request** — a faithful stand-in for
a browser holding `JSESSIONID`, and explicit about the session surviving between requests, which is
the whole bug. A `RANDOM_PORT` test with a real cookie jar has higher fidelity but needs hand-rolled
cookie handling and a third `webEnvironment`, and buys nothing: the defect is entirely inside the
servlet filter chain, which MockMvc runs for real.

Put `@AutoConfigureMockMvc` and the two provider overrides on **`AbstractIntegrationTest`**, not the
new class: it holds the test-context count at two (`AbstractGithubAppTest`'s javadoc is explicit
about not wanting silent context proliferation) and makes "no test can reach github.com" a property
of the base class rather than of each test remembering.

Overriding only `token-uri` and `user-info-uri` is deliberate — `OAuth2ClientPropertiesMapper`
starts from `CommonOAuth2Provider.GITHUB` and overlays, so `user-name-attribute: id`, the
authorization URI and `CLIENT_SECRET_BASIC` all survive.

The authorization endpoint is never called: the test reads `state` out of the `Location` header of
`GET /oauth2/authorization/github` and calls the redirect URI itself.

New test, `githublogin/LoginSessionIntegrationTest`, driving the real chain:

| # | Assertion | Now | After | Pins |
|---|---|---|---|---|
| 1 | Callback issues a `forge_session` cookie | passes | passes | the handshake still works |
| 2 | `GET /api/session` with session **and** cookie → 200, non-null `activeWorkspaceId` | **500** | 200 | the regression itself |
| 3 | `GET /api/session` with session, **no** cookie → 302 | **500** | 302 | a servlet session is not a credential |
| 4 | `revoke()`, then request with both → 302 | **500** | 302 | revocation immediacy, which the shadowing silently defeated |
| 5 | `authorizedClientService.loadAuthorizedClient("github", login)` is null | **fails** | passes | §6's token claim |
| 6 | `get("/api/session").with(oauth2Login())` → **403** | **500** | 403 | step 4's gate, and *only* that |

**Two traps this table encodes, both found by checking rather than assuming:**

- **Row 5 is not "nothing in the servlet session".** The token is in an in-memory
  `OAuth2AuthorizedClientService`, not the session, so the session-shaped assertion would pass
  without the fix and prove nothing.
- **Row 6 cannot be made green by step 1.**
  `SecurityMockMvcRequestPostProcessors.oauth2Login()` wraps the repository in a
  `TestSecurityContextRepository` that prefers a request attribute over the delegate, so the token
  reaches the holder even with `NullSecurityContextRepository` installed. That makes it a good test
  of the step-4 gate and a useless test of step 1 — so rows 2–4 must **not** be built on it.

**Watch rows 2–6 fail first**, per the `watch-guarantees-fail-first` memory. Land `FakeGithub`, the
base-class change and the test class on the *unfixed* code, run, and record the actual failure text
rather than predicting it — in particular whether MockMvc rethrows the NPE or renders a 500, since
MockMvc performs no ERROR dispatch and may present the same defect differently from the browser.
Only then apply the fix.

## Files

| File | Change |
|---|---|
| `githublogin/internal/SecurityConfig.java` | steps 1, 2, 4; rewrite the comment at 58-60 to name `HttpSessionOAuth2AuthorizationRequestRepository` as why `IF_REQUIRED` survives |
| `githublogin/internal/DiscardedGithubUserTokens.java` | **new**, package-private, three no-ops |
| `githublogin/internal/ForgeStackSessionAuthenticationFilter.java` | delete the guard at :38, add the *why* comment |
| `githublogin/internal/ForgeStackOAuth2UserService.java` | amend the javadoc — the claim was true of this class and false of the running system |
| `support/FakeGithub.java` | add `/login/oauth/access_token`, `/user`, `/user/emails` + a `githubUser(...)` helper mirroring `installation(...)` |
| `support/AbstractIntegrationTest.java` | `@AutoConfigureMockMvc` + the two provider overrides |
| `githublogin/LoginSessionIntegrationTest.java` | **new**, rows 1-6 |
| `docs/known-gaps.md` | below |

Nothing under `api/` changes. All seven endpoints are fixed by the filter chain.

## Verification

- `./gradlew test` — **67 → 73**, all green. Every pre-existing test must be untouched; this is a
  security-chain change, and movement elsewhere means the chain shifted more than intended.
- Manual, the real proof: log in via browser, then `GET /api/session` returns 200 with a non-null
  `activeWorkspaceId` instead of a Whitelabel page. **That unblocks Task C**, which then runs
  unchanged.
- After login, assert the authorized-client service is empty and grep the log for the access token.

## Then update `docs/known-gaps.md`

New **§1.8** recording the shadowing bug and the §6 token-retention correction, quoting the observed
first-run failures the way §1.1 quotes `"Expecting code to raise a throwable"`.

Entries that stay **open**:

- **The category lesson, beside §1.2b.** This bug is invisible to `curl`: it needs a client holding
  `JSESSIONID` *and* `forge_session` at once. The entire §7 checklist is curl-shaped and would have
  passed end to end. *Anything that only manifests when the client keeps state is invisible to a
  stateless check* belongs next to *anything the suite does not touch is unverified*.
- **§1.5, vindicated.** That entry already warns this file records beliefs, after one was written
  from reading config instead of running it. `SecurityConfig:58-60` was the same mistake in
  production code: a comment describing intent is a belief, not an enforcement. Second instance in
  the same file's blast radius.
- **§5, carefully.** "No HTTP-layer tests" is now *partly* closed — one class covers login→API — but
  the other six endpoints have no status-code or JSON coverage, and **"no test boots the packaged
  application" stays fully open**: MockMvc overrides configuration and never starts the packaged
  app. Say so, so nobody reads this task as having closed the table's top entry.
- **New:** `ForgeStackSessionAuthenticationFilter` is a `@Component` implementing `Filter`, so Boot
  registers it as a container-level servlet filter *in addition* to its place in the security chain.
  Benign only because the chain has precedence `-100` and `OncePerRequestFilter` suppresses the
  second call — but it is the same shape as the bug just fixed: a second copy of an authentication
  mechanism running outside the chain. Fix is a `FilterRegistrationBean` with `setEnabled(false)`,
  or dropping `@Component` and constructing it in `SecurityConfig`. **Recorded, not folded in**, to
  keep this diff reviewable.

## Two defaults I chose without you

`AskUserQuestion` failed twice with a stream error, so these are taken as the recommended options —
say the word at approval and either flips:

1. **Scope: all four steps**, rather than the core fix alone. The token retention and the missing
   gate are both one-line-ish given the file is already open, and both are things this task
   *discovered*; deferring them means re-deriving the context later.
2. **Sequencing: tests first**, watched failing, before the fix. This delays your App install by the
   time it takes to build the `FakeGithub` OAuth endpoints. The argument for paying it: this bug is
   invisible to every check we currently run, so without the test nothing would catch a recurrence —
   and it is the fourth finding from running rather than testing the app.

---

# Task E — the setup nonce is too brittle, and its failure is unreadable

## Context

With Task D in, the install flow ran for real: `/api/installations/start` redirected to GitHub, the
App was installed, and GitHub returned the browser to the Setup URL. **The Setup URL is correct and
the App is installed** — `GET /app/installations` now reports one installation:

```
id=153999617  account=QL-Tushar-Kumar  type=User  accountId=213618763  selection=all
```

`accountId` matches `user_identities.provider_user_id` exactly, so the anti-hijack ownership check
would have passed. The binding still failed, and the browser showed *"This page is not working"*:

```
WARN  InstallationBindingService : Rejected GitHub installation binding:
      installation=153999617 reason=INVALID_SETUP_STATE
```

### Two defects, one of which hid the other

**1. The nonce is bound to a session id, and sessions churn.** `InstallationSetupNonces.issue`
stores `sessionId` and `completeSetup` requires the callback to arrive on that same session. The
`sessions` table shows three logins inside ten minutes — `bac56f25` (13:40, last used **18:33:26**,
the callback), `75f64547` (**18:25:30**), `6a7e9cbf` (18:35:48) — so the flow straddled two of them.
The GitHub install screen takes minutes, and the docs actively send a first-time user off to check
their Setup URL mid-flow, so re-authenticating inside the window is ordinary, not exotic.

**2. `INVALID_SETUP_STATE` cannot be diagnosed, and its own javadoc says so:** *"No such nonce,
already used, expired, or issued to a different session."* Four causes, one enum value, one WARN
line, one audit row. This is exactly the §1.1 pattern — a 401 and a 404 collapsed into one
indistinguishable outcome — recurring in a different module.

That second defect is why the root cause here is still **undetermined**. The browser holds exactly
one `forge_session` cookie (checked), which should make a session mismatch impossible; yet
`last_seen_at` proves session A served the callback while session B was current. Either the nonce
had simply expired past its 15-minute TTL, or something authenticated as an older session. **The
system did not record enough to tell**, and no amount of further inspection now will recover it. The
fix is to make the next occurrence self-explanatory rather than to keep guessing at this one.

**3. The callback returns an empty body.** `InstallationController.callback` answers every rejection
with `ResponseEntity.status(...).build()`. GitHub redirects a *real browser* here, so a bare 400
renders as Chrome's "This page is not working" — the user is told nothing at all. The javadoc's
"acceptable while there is no browser client" is no longer true: this endpoint's only caller *is* a
browser.

## Change

**1. Bind the nonce to the user, not the session** (`githubinstallation/internal/installation/InstallationSetupNonces.java`,
`InstallationBindingService.completeSetup`)

Store `userId`; compare against the caller's `userId`. Decided with the user.

The nonce's job, per its own javadoc, is that "a third party cannot cause someone else's browser to
complete an install flow it never began". User granularity satisfies that exactly — an attacker
cannot mint a nonce for someone else's account without being logged in as them. *Same human,
different session* was never the threat. The ownership check against the GitHub account id is
untouched and still independently blocks id substitution, which is the attack the nonce was never
able to stop anyway.

Single use stays. `InstallationController.start` passes `principal.userId()` instead of
`principal.sessionId()`.

**2. Raise the TTL to 30 minutes.** Fifteen assumes the user goes straight to GitHub and back. The
real flow includes reading GitHub's permission screen and picking repositories, and `local-setup.md`
tells a first-timer to verify their Setup URL on the way. The nonce is single-use and now
user-bound; a leaked link that goes stale in 30 minutes rather than 15 is not a meaningfully
different exposure.

**3. Split the rejection reason so the server can tell what happened**
(`InstallationBindingResult.Reason`, `InstallationSetupNonces.consume`)

`consume` returns enough to distinguish *absent* from *foreign*. Two reasons replace one:

| Reason | Means | Caller sees |
|---|---|---|
| `SETUP_STATE_EXPIRED` | no such nonce — expired, already used, or never issued | 400, **explained** |
| `SETUP_STATE_FOREIGN` | a live nonce belonging to another user | 400, explained the same way |

Both render identically to the caller and are distinct in the log and the `audit_events` row. Unlike
§1.1's 403/404 pair there is no oracle concern in telling a user their *own* setup link expired, so
the message can be genuinely helpful; a foreign nonce is the one worth alerting on, and it is now
greppable.

**4. Give the callback a body** (`api/installation/InstallationController.java`)

A rejection returns a short `text/plain` explanation and what to do — "This setup link has expired.
Start again from /api/installations/start." Still the same status codes. The ownership rejections
(`UNKNOWN_INSTALLATION`, `NOT_YOUR_ACCOUNT`) keep a single shared wording, because §7 requires them
to stay indistinguishable.

## Not doing

- **`ForgeStackSessionCookie.read` uses `findFirst()`** on the cookie list, so duplicate
  `forge_session` cookies would be resolved arbitrarily. It was the leading theory until the cookie
  count came back as 1. No evidence it is real → `known-gaps.md` entry, not speculative code.
- **The nonce is still consumed before the ownership check**, so a rejection burns it. Single use is
  the security property; with a readable error the user now knows to restart.

## Test

Extend `githubinstallation/InstallationBindingServiceTest` (which already covers nonce reuse,
foreign nonce, and absent nonce against the session-bound behaviour — those assertions move to the
user-bound equivalent):

| Assertion | Pins |
|---|---|
| A nonce issued in one session completes in **another session of the same user** | the actual regression — this is the flow that just failed |
| A nonce issued to a **different user** is rejected as `SETUP_STATE_FOREIGN` | CSRF protection survives the loosening |
| A consumed nonce is rejected as `SETUP_STATE_EXPIRED` | single use |
| An absent nonce is `SETUP_STATE_EXPIRED`, not `SETUP_STATE_FOREIGN` | the two are actually distinguished |

The first must be **watched failing** before the change — it is the one that reproduces the user's
failure, and per the standing rule a fix for a path never seen failing proves nothing.

## Verification

- `./gradlew test` — **74 → ~77**, green, with `LoginSessionIntegrationTest` untouched.
- Manual, the real proof and the thing that unblocks Task C: visit
  `http://localhost:8080/api/installations/start`. GitHub shows the configure page for the existing
  installation `153999617`; approving returns to the callback with `setup_action=update`, which
  `InstallationController` already handles identically to `install`. Expect a redirect, a
  `github_installations` row, and an `INSTALLATION_BOUND` audit row.
- Deliberately break it once: replay the same callback URL and confirm a readable
  `SETUP_STATE_EXPIRED` page rather than "This page is not working".

Then `known-gaps.md`: §1.9 for this, plus the `findFirst()` entry under §2, and tick the §7 items
this run covers.

## Progress and two late findings

**Implementation is ~90% done and uncommitted**, sitting on `6234e78`. Done: nonce bound to
`userId`, TTL 30 min, `Reason` split into `SETUP_STATE_EXPIRED` / `SETUP_STATE_FOREIGN`, controller
returns a `text/plain` body, `beginSetup`/`completeSetup` shed the now-dead `sessionId` parameter,
plus `support/BrowserLogin` extracted so `LoginSessionIntegrationTest` and the new
`InstallationSetupFlowTest` share one OAuth handshake.

Both new tests were **watched failing** on the unfixed code, with the user's exact symptoms:

```
an install survives the user logging in again ...  expected: 302 but was: 400
a rejection explains itself ...                    Expecting not blank but was: ""
a nonce from a different user is refused           PASS   (already strict enough)
```

Remaining: re-run the suite (last compile error in `RepositoryCatalogTest` is fixed but unverified),
then `known-gaps.md`, then commit.

### Finding: there is a second GitHub account in play

The user logged in with a different GitHub account to test. This is not a red herring — it changes
the guidance and slightly changes the fix.

Installation `153999617` belongs to **`QL-Tushar-Kumar`** (`accountId 213618763`). The ownership
check compares that against `user_identities.provider_user_id` of the *signed-in ForgeStack user*.
So finishing the flow signed in as the other account is refused with `NOT_YOUR_ACCOUNT` — correctly,
and it is the same rejection an attacker would get.

Binding the nonce to the user does **not** paper over this, and must not: switching accounts
mid-flow is genuinely a different human as far as the check is concerned. What it does mean is that
the rejection wording has to help, because "wrong GitHub account" and "expired link" are now both
live possibilities for this user and both currently render as the same blank page.

**Refinement to the messages** in `InstallationController.explain`: mention the account, without
becoming the oracle §7 forbids. `NOT_YOUR_ACCOUNT` and `UNKNOWN_INSTALLATION` keep one shared
wording that says to check which GitHub account is signed in — that reveals nothing about whether
the installation exists. The setup-state pair likewise mentions that switching accounts invalidates
a link.

### Finding: a successful login looks like a failure

Reported three times now as "redirected to whitelabel". It is not an error:
`forgestack.security.login-success-redirect` defaults to `/`, nothing maps `/`, so **every
successful login lands on Spring's Whitelabel 404.** The one signal the user gets from a working
login is indistinguishable from a broken one.

**Change the default to `/api/session`** (decided with the user). `SecurityConfig` reads it via
`@Value("${forgestack.security.login-success-redirect:/}")`; only the default moves. After logging
in the browser lands on its own session JSON — confirmation that it worked, plus *which account is
signed in*, which is exactly the thing needed before starting an install. Update the §5 table in
`docs/local-setup.md`, which documents the default as `/`.

### Environment note

Postgres, Redis and the app are all stopped (reboot). The `forge-backend_forgestack-pgdata` volume
survived, so the existing user, sessions and audit rows come back with `docker compose up -d`. The
GitHub-side installation is untouched and still present.

## Revised verification

1. `./gradlew test` — expect **74 → 78** green (three new flow tests, one new nonce test).
2. `docker compose up -d && ./scripts/dev.sh`.
3. Sign in **as `QL-Tushar-Kumar`** — the account that owns installation `153999617`. Landing on
   `/api/session` should now show a non-null `activeWorkspaceId`; confirm `email`/`displayName` are
   that account and not the other one.
4. `/api/installations/start` → GitHub shows the configure page for the existing installation →
   approve → expect a redirect and a `github_installations` row, not a blank page.
5. Deliberately break it once: replay the callback URL and confirm a readable expired-link message.






# Task F — one write owner per real repository

**Status: implemented.** `V5__single_write_owner.sql`, `WriteOwnershipTest` (4 tests). Full suite
80 → 84 green.

## Context

Asked whether Forge should move to "one repository → one workspace with WRITE authority, plus
explicitly authorized READ/OBSERVE workspaces". Decided **yes**, and adopted only the write half
now. Observation is designed but deliberately unbuilt.

Three findings shaped that split.

**The write case is stronger than coordination difficulty.** `github_action_log` is unique on
`(workspace_id, fingerprint)` and is the ledger that stops a crash between "GitHub committed" and
"we recorded it" from opening duplicate pull requests. A second writing workspace gets a *second*
ledger, and neither can see the other's fingerprints — so the guarantee does not degrade, it stops
applying, silently. That is a property already built and paid for, voided by a second writer.

**The motivating case for observation does not need observation.** "Agent B reads `auth-service` to
modify `payment-service`" is, in the common shape, two repositories on one account — same
installation, same workspace — and is already solvable with two narrow tokens,
`TokenScope.readOnly(...)` plus `TokenScope.contribute(...)`. Cross-workspace observation is only
required once workspaces are deliberately split, which needs a shared-installation model that does
not exist. Observation is therefore *downstream* of installation sharing, not an independent
feature, and is unreachable today even if built.

**Observation would open the first cross-tenant credential path.** Minting for repository X requires
the installation that exposes X, which belongs to the owning workspace. Today no code can express
one workspace reaching another's credentials, and that absence is a real safety property. Worth
opening once, against a live requirement — not speculatively.

## Change

`managed_repositories` keyed maintenance on `github_repository_id`, a workspace-local UUID. Since
V4 two workspaces hold *different* UUIDs for the same real repository, so a constraint there cannot
see a cross-workspace collision. V5 adds `github_repo_id` and the invariant:

```sql
CREATE UNIQUE INDEX managed_repositories_single_writer
    ON managed_repositories (github_repo_id)
    WHERE status = 'ACTIVE';
```

Partial on `ACTIVE` deliberately: `PAUSED` and `ACCESS_LOST` release the claim, so a workspace that
stopped maintaining a repository does not hold a global lock nobody else can see or appeal. The cost
is that re-enabling can now fail because someone else claimed it — the correct visible outcome
rather than two writers. `ManagedRepositoryService.enable` translates the constraint violation into
`Optional.empty()`, matching how `InstallationBindingService` handles `installation_id`; the
controller already renders that as 404 rather than 403 so as not to confirm the repository exists.

**The migration has an RLS trap worth remembering.** `FORCE ROW LEVEL SECURITY` applies to the table
owner, and migrations run as `forgestack_migrator` — the owner. With no `app.workspace_id` bound,
both tables read as *empty*, so the backfill would quietly update nothing. Verified directly: the
same count returns 0 with FORCE and 9 without. V5 lifts FORCE for the backfill and restores it
immediately; Postgres DDL is transactional and Flyway runs each migration in one transaction, so a
failure between those points rolls back the lift too.

## Deliberately not done

- `TokenScope` does **not** gain a `workspaceId`. It is unreachable today — no code path shares an
  installation — so the field would be carried, hashed, and never exercised, which is the same
  vacuity this project already flags in two ArchUnit rules. Guarded instead by the tripwire below.
- No observer grant table, no read-only observer path, no cross-tenant minting.
- No `repository_ownership` aggregate. The partial index expresses the invariant without inventing
  one; promote it when transfer semantics need their own history.
- Installation binding stays 1:1 for alpha.

## Test

`WriteOwnershipTest`. The setup builds a state GitHub cannot produce — two installations, two
accounts, one repository — because that is precisely the state the constraint defends and it is
otherwise unreachable. Watched failing first with the index commented out: the second workspace
successfully obtained an `ACTIVE` claim on the same repository, twice over.

The fourth test is a **tripwire**, not a behaviour test: it asserts `installation_id` is still
uniquely constrained, and its failure message lists what must change alongside it — `TokenScope`'s
fingerprint, `available()`'s tenancy assumption, and keying repository concurrency on
`github_repo_id`. Dropping that constraint should hand the next engineer a checklist, not a green
build.

One flaw found while writing it: counting active writers with a single unscoped query always
returned 0, because the app role is subject to RLS and holds no `BYPASSRLS`. Counting inside each
tenant scope and summing is the honest form, and demonstrates the property that makes the constraint
necessary — neither workspace can see the other's claim, so only the database can arbitrate.

## Decided for Phase 2, nothing to change yet

Repository concurrency must be keyed on **`github_repo_id`**, never on a `ManagedRepository` or
`GithubRepository` UUID. Free to decide now because no locking code exists — not a lease, not a
semaphore, not a stub. In a 1:1 world the two choices are indistinguishable, which is exactly why
Phase 2 would otherwise pick the workspace-local one and be silently wrong once installations are
shared.

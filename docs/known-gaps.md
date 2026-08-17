# Known gaps and caveats

Everything deliberately left undone, deferred, or accepted as a trade — with the trigger that
should force each one to be revisited. Written down because a gap nobody recorded is
indistinguishable from a bug nobody noticed.

Last updated at the end of Phase 1.6 (installation binding, repository sync, opt-in). 67 tests.

Setting up real credentials for the first time is a separate document: [local-setup.md](local-setup.md).

---

## 1. Will bite during the first manual test with real credentials

### 1.1 A bad App key looking like an unknown installation — **fixed**

`GithubAppClient.fetchInstallation` used to swallow every 4xx and return empty. Right for 404 — we
must not tell a caller whether an installation id exists — but it also swallowed **401**, so a wrong
`app-id` or a key from a different App presented as `UNKNOWN_INSTALLATION` with nothing in the logs.

`handleLookupFailure` now splits them:

| Status | Caller sees | Server-side |
|---|---|---|
| 401 | **500** — `IllegalStateException` naming `app-id` and the private key | thrown, not logged away |
| 403 | rejection, indistinguishable from 404 | WARN — usually a suspended App or a spent rate limit |
| 404 | rejection, indistinguishable from 403 | DEBUG — the ordinary answer for a guessed id |

Surfacing the 401 leaks nothing: it depends only on ForgeStack's own configuration and never on the id
asked for, so it is not an oracle. 403 and 404 stay deliberately identical to the caller.

Covered by `InstallationBindingServiceTest.badAppCredentialsFailLoudly`, watched failing
("Expecting code to raise a throwable") against the old behaviour before the fix landed.

*Never part of this gap:* a **malformed** key. `GithubAppJwtService.parsePrivateKey` already detects
PKCS#1 and throws with the exact `openssl` command.

### 1.2 The session cookie is `Secure`, and `curl` will not send it over HTTP

`forgestack.security.cookie-secure` defaults to `true`, so `ForgeStackSessionCookie` marks the cookie
`Secure`. Over plain `http://localhost:8080` **`curl` withholds it**, and every authenticated step
of the §7 checklist fails with a bare 401 that reads as broken authentication.

Browsers permit `Secure` cookies on `localhost`, which makes this worse rather than better: the flow
works in Chrome and fails in `curl` within the same session.

**Deliberately not fixed by changing the default** — a default that is insecure for local
convenience is how it reaches production. `scripts/dev.sh` passes the override at run time instead:

```
./gradlew bootRun --args='--forgestack.security.cookie-secure=false'
```

### 1.2b The compose Postgres had never worked — **fixed**

`docker-compose.yml` mounted the volume at `/var/lib/postgresql/data`, but `postgres:18` keeps data
in a version-specific subdirectory and wants the mount one level up, at `/var/lib/postgresql`. It
refused to start, exit 1, and had done since the first commit.

Nobody noticed for a month because **every test runs on Testcontainers**, which mounts no volume.
The compose stack is only needed to run the app for real, which had not happened until GitHub
credentials arrived.

Worth keeping as a category, not just a fix: anything the test suite does not touch is unverified,
however long it has been sitting in the repository looking correct.

### 1.3 GitHub hands out a PKCS#1 private key; the JDK cannot read it

Already handled with an actionable error naming the exact `openssl` command
(`GithubAppJwtService.parsePrivateKey`), but expect to hit it once. Convert with:

```
openssl pkcs8 -topk8 -inform PEM -outform PEM -nocrypt \
  -in forgestack-app.private-key.pem -out forgestack-app.pkcs8.pem
```

### 1.4 Organization installs are refused

Only personal-account installs bind. Install the App on a **personal account**, not an org, or you
will get `409 ORGANIZATION_NOT_SUPPORTED`. See §4.1 — this is a product limitation, not a bug.

### 1.5 An expired session on the setup callback — **not a gap; this entry was wrong**

Previously recorded as "you get a blank 401 rather than being sent to log in". Measured against the
running app, `/api/session` returns **302 to `/oauth2/authorization/github`**: Spring Security's
`oauth2Login()` installs a `LoginUrlAuthenticationEntryPoint`, and with exactly one registration it
skips the chooser and goes straight to GitHub. An expired session mid-install re-authenticates and
comes back.

Kept rather than deleted, as a reminder that this file records beliefs. This one was written from
reading the config instead of running it, and was wrong for a month.

### 1.6 Actuator was configured but never on the classpath — **fixed**

`application.yaml` had a `management.endpoints` block and `SecurityConfig` permitted
`/actuator/health/**`, but `spring-boot-starter-actuator` was never declared. Every actuator URL
404ed. Found the first time anyone asked the running app for its health.

### 1.7 Spring AI blocked startup with no model in use — **fixed**

Nothing calls a model until Phase 4, but Spring AI's OpenAI autoconfiguration builds its client
beans eagerly and throws `At least one credential source must be specified` without a key. The app
could not start at all. Tests never caught it because `AbstractIntegrationTest` sets
`spring.ai.openai.api-key=test-key`; nothing set it for `bootRun`.

`application.yaml` now defaults it to `${FORGESTACK_OPENAI_API_KEY:not-configured}`, matching how
the GitHub credentials are handled — boot succeeds, and the first real call fails loudly.

**§1.6 and §1.7 are the same finding as §1.2b**, three times over: the test suite runs on
Testcontainers and never boots the packaged application, so anything that only fails at startup was
invisible. A smoke test that starts the app with production autoconfiguration would have caught all
three. Recorded in §5.

### 1.8 The OAuth servlet session shadowed the ForgeStack session — **fixed**

The first real browser login succeeded — `users`, `user_identities` and a `sessions` row with a
non-null `workspace_id`, which incidentally confirmed the session/workspace fix from Task #15.
Sixteen seconds later, `GET /api/installations/start` returned a Whitelabel error page:

```
java.lang.NullPointerException: Cannot invoke
  "dev.tushar.forgestack.githublogin.ForgeStackPrincipal.sessionId()" because "principal" is null
```

**All seven authenticated endpoints were unreachable from a browser** — every controller under
`/api/**` declares `@AuthenticationPrincipal ForgeStackPrincipal`.

Two authentication mechanisms were live and the wrong one won:

1. `oauth2Login` persisted its `OAuth2AuthenticationToken` into the servlet session via the default
   `HttpSessionSecurityContextRepository`.
2. `SecurityContextHolderFilter` restored it early in the chain, well before
   `ForgeStackSessionAuthenticationFilter`.
3. That filter was guarded on `getAuthentication() == null`, so it **never read the cookie**.
4. `authorizeHttpRequests(...).authenticated()` was satisfied by the OAuth2 token.
5. `AuthenticationPrincipalArgumentResolver` answers a principal-type mismatch with **null**, not an
   exception. Hence the NPE, four frames from the cause.

The bitter part: `SecurityConfig` already carried the comment *"The API is authenticated by the
ForgeStack session cookie, not by a servlet session."* The sentence was true as intent and false as
description. **A comment describing intent is a belief, not an enforcement** — the same lesson §1.5
records about this file, now found in production code.

The fix makes the sentence structural rather than aspirational:

| Change | Why |
|---|---|
| `NullSecurityContextRepository` | Nothing is stored, so nothing can be restored to shadow the cookie |
| Guard deleted from `ForgeStackSessionAuthenticationFilter` | Nothing else may authenticate, so there is nothing to defer to. Making the cookie merely take *precedence* was rejected: it leaves the servlet session as a fallback whenever the cookie is absent or revoked, silently breaking the revocation-immediacy promise in that filter's own javadoc |
| `/api/**` requires a `ForgeStackPrincipal`, not just `authenticated()` | States the invariant the controllers assume. A recurrence is now a 403 at the gate, not a 500 in seven places |
| `DiscardedGithubUserTokens` | See below |

#### 1.8b The GitHub user token was retained on the heap

Found while fixing the above, and **not** where it first appeared to be. The token was never in the
servlet session: Boot autoconfigures an `InMemoryOAuth2AuthorizedClientService` behind an
`AuthenticatedPrincipalOAuth2AuthorizedClientRepository`, so every GitHub user access token
ForgeStack had issued sat in a heap map keyed by GitHub login for the process lifetime. Nothing
evicted it — the logout handler revokes the ForgeStack session but never calls
`removeAuthorizedClient`.

The plan's §6 (*"No GitHub user token is persisted"*) and the `ForgeStackOAuth2UserService` javadoc
were both false as stated. They described our code; the framework's default did something else.
**Not configuring something is still a decision.** Blast radius was bounded — the scopes really are
only `read:user`/`user:email`, so a leaked token reads a profile and cannot touch code.

Worth keeping: the first-guess assertion ("nothing in the servlet session") would have **passed
without the fix**. Verifying where the data actually was, rather than where it seemed to be, is what
made the test real.

#### What the tests said before the fix

Six of seven failed, watched failing before anything was changed:

```
a logged-in browser reaches the API as its ForgeStack principal
  -> NullPointerException: ... ForgeStackPrincipal.userId() because "principal" is null
the servlet session alone is not a credential                      -> same NPE
revoking the session logs the browser out immediately              -> same NPE
an OAuth2 token cannot reach the API                               -> same NPE
login writes no security context into the servlet session
  -> expected: null but was: SecurityContextImpl [Authentication=OAuth2AuthenticationToken ...]
the GitHub user token is not kept after login
  -> expected: null but was: org.springframework.security.oauth2.client.OAuth2AuthorizedClient@...
```

Three of them reproduce the browser's exact exception. `LoginSessionIntegrationTest` now covers the
login handshake end to end against `FakeGithub`; suite is 67 → 74.

**This bug was invisible to every check the project runs.** It needs a client holding `JSESSIONID`
*and* `forge_session` at once, and the entire §7 checklist is `curl`-shaped — it could have been run
to completion and passed. Recorded in §5 as its own category, beside §1.2b.

### 1.9 The setup nonce was bound to a session, and its failure was unreadable — **fixed**

With §1.8 in, the install flow ran for real. GitHub installed the App and returned the browser to
the Setup URL. The browser showed *"This page is not working"*:

```
WARN InstallationBindingService : Rejected GitHub installation binding:
     installation=153999617 reason=INVALID_SETUP_STATE
```

Everything else was right — `GET /app/installations` reported `id=153999617
account=QL-Tushar-Kumar accountId=213618763`, matching `user_identities.provider_user_id` exactly,
so the ownership check would have passed.

**Two defects, and the second hid the first.**

*The nonce was bound to a session id.* The flow spans the GitHub install screen, which takes
minutes, and `local-setup.md` actively sends a first-timer off to verify their Setup URL on the way.
The `sessions` table showed three logins in ten minutes and the flow straddled two of them. Now
bound to the **user**: an attacker still cannot mint a nonce for someone else's account, and *same
human, different session* was never the threat. TTL also raised 15 → 30 minutes. The `sessionId`
parameter disappeared from `beginSetup`/`completeSetup`, which is the real tell that it was carrying
a distinction that did not exist.

*`INVALID_SETUP_STATE` covered four causes*, as its own javadoc admitted: *"No such nonce, already
used, expired, or issued to a different session."* One enum, one WARN line, one audit row for both a
stale link and a possible CSRF attempt. Split into `SETUP_STATE_EXPIRED` and `SETUP_STATE_FOREIGN` —
identical to the caller, distinct in the log. **This is the §1.1 pattern recurring in another
module**: a 401 and a 404 collapsed into one indistinguishable outcome.

Because of that collapse the root cause of *this specific* failure is still **undetermined**, and no
longer recoverable — the nonce is gone and the log recorded only the enum. The browser held exactly
one `forge_session` cookie, which should make a session mismatch impossible, yet `last_seen_at`
proves an older session served the callback. The fix is to make the next one self-explanatory rather
than to keep guessing at this one.

*The callback returned an empty body.* GitHub redirects a **real browser** here, so
`ResponseEntity.status(...).build()` renders as Chrome's "This page is not working" and the user
learns nothing. It now returns `text/plain` saying what happened and what to do. The ownership
rejections still share one wording, per §7 — but that wording now names `/api/session`, because
"you are signed in as the wrong GitHub account" is a live cause and revealing it discloses nothing
about whether the installation exists.

Covered by `InstallationSetupFlowTest`, which walks the flow over HTTP because the re-login case
only exists there. Watched failing first:

```
an install survives the user logging in again ...  expected: 302 but was: 400
a rejection explains itself ...                    Expecting not blank but was: ""
```

### 1.10 A successful login (and a successful install) looked exactly like a failure — **fixed**

`forgestack.security.login-success-redirect` defaulted to `/`, and nothing serves `/`. **Every
successful login rendered Spring's Whitelabel 404.** It was reported as a failure three separate
times during first setup before it was recognised as the success path — each time costing a round of
diagnosis on a system that was working.

Default is now `/api/session`, so login lands on the session resource: proof it worked, and which
GitHub account is signed in — the fact that decides whether an install will be accepted at all.

**The same mistake existed on the other side of the flow and was found the same way.** After §1.9's
fix, the first genuinely-new install (via uninstall/reinstall) bound correctly — confirmed by an
`INSTALLATION_BOUND` audit row and a `github_installations` row — and then rendered the identical
Whitelabel 404, because `forgestack.github.app.setup-redirect` also defaulted to `/`. Default is now
`/api/repositories`: proof the binding worked, and the list of what it just granted.

Worth keeping as a category: **a success path with no signal borrows its output from the failure
path**, and it will keep doing so at every redirect target that shares the mistake, not just the
first one found. Nothing was broken and everything looked broken, twice. Cheap to fix, and each
instance was masking attention that belonged on §1.8 and §1.9's real defects.

### 1.11 A guessed installation id returned 500, and a wrong App ID still blamed the user — **fixed**

Both found by working the §7 checklist against real GitHub for the first time.

**The 500.** `GET /api/installations/callback?installation_id=99999999` answered **500 with a stack
trace** instead of 403 — on the one path whose entire purpose is refusing an id the caller does not
own. `fetchInstallation` used `retrieve()` with an `onStatus` handler that *returns* on 404; in
`RestClient` that means "handled, carry on", and carrying on deserialises the **error** body into
`InstallationView`. GitHub's 404 is `{"message":"Not Found",…}` with no `id`, so Jackson threw
`Cannot map null into type long`. Now uses `exchange()`, reading the body only after the status has
been judged successful.

**Why no test caught it:** `FakeGithub` answered 404 with an **empty** body, which deserialises to
null and looks exactly like a clean miss. The fake was wrong about GitHub in the one way that
mattered. It now returns GitHub's real 404 shape, and reproduces the failure before the fix.

**The wrong App ID.** §1.1's fix only handled 401, on the assumption that bad App credentials
produce one. They do not. Verified directly against GitHub:

| Credential fault | GitHub answers |
|---|---|
| Wrong App ID (`iss` is not a real App) | **404** `Integration not found` |
| Correct App ID, wrong private key | **401** `A JSON web token could not be decoded` |

So a completely misconfigured deployment took the 404 path and told **every** user "that
installation cannot be connected to the account you are signed in as" — precisely the complaint
§1.1 was written to fix, still true for the more likely of the two faults. On a 404 the client now
asks `GET /app`, which answers for the App alone and names no installation, and throws a 500 naming
`forgestack.github.app.id` when the App itself is unrecognised. Prose is not parsed; the status of a
different endpoint is.

Verified live on a second instance started with `FORGESTACK_GITHUB_APP_ID=1`: 500 naming the
credential, while a guessed id against correct credentials still answers 403 and a real bind still
redirects. The `GET /app` branch is **not** covered by the suite — `FakeGithub` is always driven with
credentials it issued itself, so an App id GitHub has never heard of cannot arise against it.

**The category, again:** every bug in this section was invisible to a green suite because the test
double was politer than the real system. A fake that never returns a body, never returns 404 for the
right reason, or never disagrees with itself is a fake that certifies code the world will reject.

### 1.12 Changing repository access on GitHub was refused as a stale link — **fixed**

Removing a repository from the installation on GitHub and clicking Save produced:

> This setup link is no longer valid — it expires, it only works once, and it belongs to the account
> that started the flow. If you switched GitHub accounts, sign in again…

Nothing about that was true. GitHub's **Redirect on update** setting sends the user to the setup URL
with an `installation_id` and **no `state`** whenever they change repository access, because that
flow never passed through `/api/installations/start` to be issued a nonce. `completeSetup` fed the
missing state straight to `nonces.consume`, got nothing back, and reported `SETUP_STATE_EXPIRED`.

**The damage was worse than the message.** The change had already taken effect on GitHub, so
ForgeStack's catalog now disagreed with reality and nothing said so — the rejection looked like the
update had been prevented. Only an unprompted `POST /api/repositories/sync/{id}` reconciled it.

**And it was believed.** The tester reported having abandoned the save part-way through, because the
error page said the link was invalid. GitHub's API, asked directly afterwards, listed three
repositories rather than four: the removal had committed before the redirect ever happened. So the
message did not merely fail to help — it produced a confident, wrong belief about the state of an
external system, and the only way to settle it was to go and ask GitHub. That is the most expensive
form §1.10's category takes: a success path borrowing the failure path's output, where the operation
being reported on is not ours to re-check cheaply.

A missing nonce and a failed nonce are different events and now take different paths. A missing one
may **refresh a binding the workspace already holds** — which grants no authority it did not already
have, and is what makes accepting it safe without the nonce. It may never **create** a binding;
that still requires starting from `/api/installations/start`, and is refused with the new
`SETUP_NOT_STARTED_HERE`, whose message explains which of the two situations the caller is in.

Verified live: a no-state callback for the connected installation redirects to the repository list,
and a no-state callback for an unknown id is still refused.

**Worth noting against §1.9.** The nonce was loosened once already, from session-bound to
user-bound. This is the second time it has been found refusing something legitimate, and the pattern
is the same both times: it was written to guard *binding*, and kept being applied to flows that
were not binding anything. Its remaining job is now exactly one sentence long, which is the right
size for it.

---

## 2. Security debt

| Gap | Risk | Trigger to fix |
|---|---|---|
| **Installation tokens cached in Redis as plaintext** | Redis is credential-bearing infrastructure today. Tokens are short-lived and narrowly scoped, which bounds it. | `platform.crypto` envelope encryption |
| **Database passwords in `application.yaml`** (`forgestack_app`/`forgestack_app`) | Local-only values, but the shape invites a real one being pasted there | Before any deployed environment |
| **CSRF disabled for `/api/**`** | Session cookie is `SameSite=Lax` and the API is same-origin, so exposure is limited | Browser client lands |
| **No sandbox isolation yet** | Not applicable until the sandbox exists, but the plan's residual-risk note (container ≠ VM boundary) stands | Before public self-serve signup — gVisor is the cheap next step |
| **`ForgeStackSessionCookie.read` takes the first matching cookie** | `findFirst()` over the cookie array, so two `forge_session` cookies would be resolved arbitrarily and could flip the caller between sessions request to request. It was the leading theory for §1.9 until the browser turned out to hold exactly one, so there is no evidence it is reachable — recorded rather than fixed speculatively | Anything that can set a second cookie: a `Domain` attribute, a path change, or a second host |

---

## 3. Operational gaps

### 3.1 `audit_events` partitions run out — **fixed**

`V1__baseline.sql` created partitions for **2026-08 and 2026-09 only**. Rows past that would land in
DEFAULT and keep working, so it failed quietly — and worse than it first looked: once October rows
sit in DEFAULT, the October partition can no longer be created without moving them out, so the cost
grew with the delay.

`V6__audit_partitions_ahead.sql` provisions through **2027-12** (18 partitions including DEFAULT)
and adds `create_audit_events_partition(date)`, which creates a partition *and* revokes the app
role's UPDATE/DELETE/TRUNCATE in one call. The scheduled job this gap originally asked for should
call that function rather than issue its own `CREATE TABLE`.

**The reason the function exists is a hole this investigation found.**
`docker/postgres/init/01-roles.sql` sets `ALTER DEFAULT PRIVILEGES … GRANT SELECT, INSERT, UPDATE,
DELETE ON TABLES TO forgestack_app`, so every table the migrator creates arrives fully writable, and
V1's per-partition `REVOKE` is what pulls it back. Verified directly: a partition created without
the revoke reports `DELETE,INSERT,SELECT,UPDATE` for the app role against `INSERT,SELECT` on the
others. Permission is checked on the relation actually named, so `UPDATE audit_events` stays refused
by the parent's grants while `UPDATE audit_events_2027_01` would have succeeded — **append-only
would have been false for every future month, with every existing test still green.**

`RowLevelSecurityIsolationTest.noAuditPartitionIsDirectlyWritable` now asserts it across all
partitions at once, so it covers the ones that do not exist yet.

**A second bug, found only because the test JVM is not UTC.** The first draft passed a bare `date`
through `format('%L', …)`, producing `'2026-10-01'` — which `timestamptz` resolves in the *session*
timezone, and the JDBC connection inherits the JVM's rather than the server's. On a `+05:30` machine
every boundary shifted back 5.5 hours. It failed loudly only because the first month collided with
an existing partition; the months after it would have been created crooked and silently misfiled
rows written near midnight UTC on the 1st. Bounds now carry an explicit `+00`, matching V1's
literals. Worth remembering as a category: **a migration that reads correctly can still be wrong on
a machine in a different timezone than the one it was written on.**

### 3.2 Uninstalls and suspensions are not noticed

There is no webhook handling (Phase 6). If a customer uninstalls the App or suspends it, ForgeStack keeps
believing it has access until something fails. `InstallationTokenService.evict` exists for this but
has no caller.

**Confirmed concretely** during the first real install: uninstalling and reinstalling on the same
account gets a *new* `installation_id` from GitHub every time, and the old `github_installations`
row — and its synced `github_repositories` — stayed exactly as valid-looking as the new one. Two
rounds of uninstall/reinstall left 18 repository rows for 9 real repositories, each doubled, with
`GET /api/repositories` unable to tell the difference. Cleaned up by hand (`DELETE FROM
github_installations WHERE installation_id = <stale>`, which cascades to `github_repositories` and
`managed_repositories`) — not a code fix, since the correct fix is the webhook this gap already
names.

**Until then:** `POST /api/repositories/sync/{installationId}` is the only way to notice a change,
and it only helps for the installation you tell it about — it does nothing about a *different*,
now-dead installation's rows sitting alongside it.

**This was not fully fixed by the webhook alone — investigated concretely 2026-08-16.** The
duplicate-listing symptom was two independent problems, and only one of them needs Phase 6:

1. `github_repositories` carries `workspace_id` directly (`V2__github_app.sql`), but its
   uniqueness constraint is `UNIQUE (github_installation_id, github_repo_id)` — scoped per
   installation, not per workspace. The database has no objection to two installations, one live
   and one dead, each owning a row for the same real repository.
2. `RepositorySyncService.available()` (backing `GET /api/repositories`) queries
   `findByWorkspaceIdAndRemovedAtIsNullOrderByFullName(workspaceId)` — it never looks at
   `github_installations.suspended_at` or `.deleted_at`. And `GithubInstallation.deletedAt` is in
   fact never assigned anywhere in the codebase today (checked directly); only `suspendedAt` is
   ever refreshed, in `refreshFrom()`.

So even once Phase 6 correctly marks a superseded installation `deleted_at`, `available()` would
keep listing its repositories as duplicates unless it is *also* taught to exclude dead
installations — or unless the constraint is tightened to `(workspace_id, github_repo_id)` so a
duplicate cannot be inserted in the first place, independent of whether or when a webhook arrives.

**Split verdict:** "notice the uninstall" genuinely is Phase 6 — it needs the webhook. "Stop
showing the same repo twice" was a data-integrity fix that did not need one.

**The second half is fixed** (`V4__repository_identity_is_workspace_scoped.sql`). A repository's
identity is now `(workspace_id, github_repo_id)`, and which installation exposes it is a mutable
attribute: `RepositorySyncService` matches per workspace and re-points the existing row, so a
reinstall adopts the catalog instead of duplicating it. This also fixes a quieter bug found while
testing it — the `managed_repositories` opt-in pointed at the *old* repository row, so a reinstall
left a repository the user had explicitly enabled sitting unmanaged next to its managed twin.
Losing consent silently is the same class of failure as losing access silently. Both cases are
covered by tests in `RepositoryCatalogTest`, each watched failing first
(`["octo/alpha", "octo/alpha", "octo/beta", "octo/beta"]`, and `managed=true` beside
`managed=false`).

**Still open, and genuinely Phase 6:** nothing marks a superseded installation dead.
`GithubInstallation.deletedAt` is still never assigned anywhere; only `suspendedAt` is refreshed,
in `refreshFrom()`. A dead installation's rows can no longer duplicate a live one's, but ForgeStack
still does not *know* it is dead, and `InstallationTokenService.evict` still has no caller.

### 3.3 No GitHub rate-limit handling

No ETag caching, no `Retry-After` handling, no per-installation token bucket. Arrives with the
`githubapi` module. A chatty sync loop against a large account could burn the 5000/hour limit.

### 3.4 The discovery token is minted fresh on every sync

Not cached, unlike scoped tokens. Fine at current volume; watch if sync becomes frequent, since
token minting itself is rate-limited per installation.

### 3.5 Repository sync runs inside the HTTP request

A very large installation makes the setup callback slow. Sync is best-effort and already off the
binding transaction, so the fix is to move it to a job once `platform.jobs` exists.

### 3.6 Redis `SCAN` may miss a key written mid-scan

Documented trade in `InstallationTokenService.evict`. Costs one narrowly-scoped token living out its
remaining minutes, versus stalling Redis with `KEYS`. Accepted.

### 3.7 Nothing forces a worker's writes to carry its lease epoch — **fixed**

The gap with the widest blast radius in Phase 2, and it was fixed one layer lower than planned.

Fencing used to work only for the three statements that remembered to ask for it: `TaskLeases` puts
`AND lease_epoch = ?` in its own `WHERE` clauses, so a superseded worker's renew and release do
nothing. Nothing checked the *next* write somebody added, and that write would not fail, would not
warn, and would not look wrong in review — it would simply mean two workers writing to a task they
both believed they held. The rule lived in a class javadoc, which is discipline, not enforcement.

The plan's fix for 2.3 was to route every task write through `TaskStateService` taking a `Lease`
parameter. That binds code that goes through the service, and nothing else. `V10__lease_fencing.sql`
puts the rule where it cannot be skipped instead: a `BEFORE UPDATE` trigger refuses any write to a
task under a live claim unless the session carries that exact claim in `app.lease_task` and
`app.lease_epoch` — the same mechanism as row-level security, aimed at a different question.
`LeaseScope` is the `TenantScope`-shaped way to bind it.

Stronger than RLS in one respect worth noting: RLS is bypassed by a superuser, and this is not.
Verified live — a `postgres` superuser `UPDATE` against a leased task is refused.

**Expiry is the only way past it, and that is deliberate.** A lapsed claim is exactly what the
reconciler exists to take back and cannot carry an epoch, because the point is that its holder is
gone. Making expiry the boundary means the escape hatch and the recovery path are the same thing, so
there is no bypass to add and none to reach for by mistake. The case that will eventually want one —
a human cancelling a task a worker is actively running — is §3.12.

### 3.12 There is no way to intervene in a task a worker currently holds

A consequence of §3.7's fix, and the one legitimate write the fence has no path for: a human
cancelling a `RUNNING` task cannot write to it while its lease is live, and bumping the epoch to
fence the worker off is itself a write. Nothing needs this yet — there is no cancellation endpoint —
so no bypass was built for it, on the principle that an escape hatch with no caller is one people
find and use for the wrong thing.

The shape to prefer when it arrives is a cancellation *request* the worker observes at its next
checkpoint, not a stomp: an `UPDATE` that wins a race against a running worker leaves a sandbox, a
branch, and possibly an open PR behind it. Only if that proves insufficient should a fenced override
exist, and it should look like `TenantScope.runWithoutTenant` — named, documented, and obvious in a
diff.

### 3.8 The reconciler sweep is O(active workspaces)

Row-level security has no all-tenants mode for `forgestack_app` — deliberately, since it is not
`BYPASSRLS` — so a cross-tenant scan returns nothing however it is written. `LeaseReconciler.sweep`
therefore enters each active workspace in turn, every 30 seconds.

Fine at any plausible near-term workspace count and the honest price of an isolation guarantee the
application cannot escape. **Revisit when one sweep stops fitting comfortably inside its interval.**
The replacement is a workspace-agnostic index of outstanding leases — a small table platform may read
without a tenant — not a wider grant to the application role.

### 3.9 No graceful drain on `SIGTERM` — **fixed**

Built with the worker loop in 2.4, since the reason for deferring it — nothing polled the queue —
stopped being true. `TaskWorker` stops claiming on `ContextClosedEvent`, finishes the attempt in
hand, and applies `YIELD` to hand the task straight back to the queue rather than letting the lease
lapse. That is the difference between a deploy costing nothing and costing a lease TTL per in-flight
task.

The check sits on `runAvailableWork` rather than on the scheduled method, and that placement was a
bug the test caught: with it on the timer, a draining process still took on work through any other
entry point.

**Not yet exercised by a real `SIGTERM`.** The test drives the flag directly, and no test starts and
kills the packaged application — the same gap as §5's "no test boots the packaged application".

### 3.10 `LeaseReconciler` writes `RUNNING → QUEUED` directly — **fixed**

It now calls `TaskStateService`, so the transition is declared in the table like every other and
`tasks.state` has exactly one writer. Both halves run in one transaction, which is not a preference:
between releasing a lapsed claim and moving the task back to `QUEUED`, a worker can claim the freed
task, and the transition would then be applied to something somebody is already running — which V10's
fence refuses, taking the sweep down with it.

### 3.13 Five of the eight completion guards decide nothing

`COMPLETE` declares §10.3's full precondition list, and five of those guards read data that does not
exist: no `evidence` table, no `human_interventions`, no diff guards, no policy engine, no record of
a merge or an acceptance. They are marked `PENDING` and pass.

**This is a real hole and it is meant to read like one.** What makes it survivable is that it is
never silent: every transition writes each guard's verdict into
`task_state_transitions.guard_results`, so a task completed today carries a permanent record that
five of its preconditions were `NOT_ENFORCED`, and nobody reading its history later has to
reconstruct what was actually checked. `CompletionGuardsTest.theUnenforcedGuardsAreKnown` pins the
set, so shrinking it is a deliberate edit and growing it is a conversation.

They pass rather than block because blocking every completion would make the phases that build the
missing data impossible to build. **Nothing autonomous may be allowed to complete a task until this
set is empty** — that is the gate on Phase 4, not a nice-to-have.

### 3.14 `COMPLETE` has never run outside a test — **fixed**

Closed by 2.4. The whole lifecycle now runs live over HTTP: create, admit, queue, claim, attempt,
complete — and the escalation round trip, and retry-to-abandon. See the plan's step 2.4 section for
what was actually driven against the running app.

### 3.15 `tasks.simulated_outcome` is scaffolding in the production schema

The fake phase handler needs to be told how an attempt turns out, and that instruction is a column
on `tasks`. A column rather than a marker parsed out of the task's goal, deliberately — deriving
behaviour from prose would make prose load-bearing in a system whose entire thesis is that guards
read committed rows rather than text.

It is still scaffolding sitting in the real schema. **Removal trigger: delete the column,
`SimulatedOutcome`, and `FakePhaseHandler` together when real phase handlers land in Phase 4.** A
`NULL` means "behave as though the work succeeded", because a default that made every task hang would
teach people the system is broken.

### 3.11 Nothing consumes the queue

Expected at this point — the attempt loop is Phase 3 — but worth stating plainly, because the queue
and the reconciler currently form a closed loop with no exit. A task that reaches `QUEUED` is queued,
re-queued a grace period later, and re-queued again after each subsequent grace period, forever.
`V9__reconciler_backoff.sql` bounds that to one message per grace period rather than one per sweep;
it does not stop it, and cannot, until something claims the work.

---

## 4. Product limitations

### 4.1 Organizations cannot be connected

**This is the significant one — most design partners will want org repositories.**

§7 asks us to verify the caller administers the installation's account. That is not achievable with
the permission set §7 itself specifies: `GET /user/memberships/orgs/{org}` needs the `Members`
permission, which the App deliberately does not request, and GitHub App user-to-server tokens carry
no OAuth scopes, so `read:org` is not available either.

Options when this is picked up, in rough order of preference:
1. Enable "Request user authorization during installation", exchange the returned `code`, and check
   `GET /user/installations` contains the id. Proves *access*, not *admin* — an org member with repo
   access could bind the org to their own workspace first.
2. Request `Members: read` and check `role == "admin"`. Genuinely correct, but widens the App's
   standing permissions for every customer to serve one check.
3. Bind on the `installation.created` webhook, where `sender` is the actual installer.

### 4.2 No workspace switching

`ForgeStackPrincipal.activeWorkspaceId` is now load-bearing for every installation and repository
endpoint, and it is set once at login. There is no `PUT /api/session/workspace`.

Harmless while every user has exactly one workspace. **Becomes necessary the moment invitations
exist** — `SessionService.defaultWorkspaceFor` already sorts owned workspaces first in anticipation.

### 4.3 Logout is not on the session resource

Lives on Spring Security's `/logout` handler rather than `DELETE /api/session`. Deliberate: moving
it belongs with the browser client, not with a rename.

### 4.5 Unauthenticated `/api/**` redirects to GitHub instead of refusing

A request with no session gets `302` to `/oauth2/authorization/github`. Right for a browser someone
typed a URL into; wrong for a `fetch`, which follows the redirect to github.com and fails on CORS
with nothing resembling "you are not signed in".

**This bites the moment a frontend exists**, which is the next thing likely to be built. The fix is an
`AuthenticationEntryPoint` that answers `401` for `/api/**` and redirects for everything else — a
change to the entry point rather than to any controller, which is why it was not made inside 2.4.
`TaskApiTest.unauthenticatedIsRedirectedToLogin` pins the current behaviour so the day it changes,
a test says so.

### 4.4 Private GitHub emails get a placeholder

When a user's email is private, GitHub omits it and provisioning falls back to a
`@users.noreply.github.com` placeholder. Should call `GET /user/emails` instead.

---

## 5. Testing gaps

| Gap | Note |
|---|---|
| **No HTTP-layer tests** | **Partly closed.** `LoginSessionIntegrationTest` drives login → API through the real filter chain, so `@AuthenticationPrincipal` binding is now verified — that was §1.8. The other six endpoints still have no status-code or JSON coverage, and every other test drives services directly. |
| **Stateful-client behaviour is untested** | §1.8 needed a client holding `JSESSIONID` *and* `forge_session` simultaneously to appear at all. Every manual check is `curl`-shaped and stateless, so the §7 checklist would have passed while the browser was broken. **Anything that only manifests when the client keeps state is invisible to a stateless check** — the client-side twin of the entry below. |
| **Pagination untested** | `GithubAppClient.listRepositories` pages at 100 with `MAX_PAGES=50`. No test exercises more than one page. |
| **No real-GitHub smoke test** | Everything runs against `FakeGithub`. The fake is a real HTTP server so serialization is genuine, but nothing has ever talked to github.com. |
| **Error responses barely covered** | `FakeGithub` now serves 401 (`FakeGithub.unauthorized()`) and 404, which is enough for §1.1. No test exercises 403, a 5xx, or a timeout on any GitHub call. |
| **`sandboxCannotReachGithubCredentials` is vacuous** | The `sandbox` module does not exist, so the rule passes trivially. It has been *proven to fire* against a temporary fixture — but it is not guarding anything yet. |
| **`policyDoesNotDependOnLlm` is vacuous** | Same: neither module exists. |
| **`interfacesMustJustifyThemselves` allows empty** | There are no production interfaces yet besides Spring Data ones. |
| **No test boots the packaged application** | **The highest-value gap in this table, and still fully open.** Every test uses Testcontainers and overrides configuration, so nothing exercises `application.yaml` as shipped — `LoginSessionIntegrationTest` included, since MockMvc never starts the packaged app either. Three startup failures (§1.2b, §1.6, §1.7) survived a month because of it, and §1.8 makes four findings that came from *running* the app rather than testing it. All surfaced within minutes of first use. |
| **`ForgeStackSessionAuthenticationFilter` is registered twice** | It is a `@Component` implementing `Filter`, so Boot registers it as a container-level servlet filter *in addition* to its place in the security chain. Benign only because the chain has precedence `-100` and `OncePerRequestFilter` suppresses the second invocation — but it is the same shape as §1.8: a second copy of an authentication mechanism running outside the chain. Fix is a `FilterRegistrationBean` with `setEnabled(false)`, or dropping `@Component` and constructing it in `SecurityConfig`. |

---

## 6. Dependency and platform notes

### 6.1 Jackson 2 and Jackson 3 are both on the classpath

Spring Boot 4 uses **Jackson 3** (`tools.jackson`). Jackson 2 (`com.fasterxml.jackson`) arrives
transitively via Spring AI and victools.

Verified: Jackson 3 deliberately **kept** the `com.fasterxml.jackson.annotation` package, so
`@JsonProperty` works. But `ObjectMapper` must be imported from `tools.jackson.databind`, and
Jackson 3 exceptions are **unchecked**.

**The trap:** a future dependency contributing a Jackson-2-annotated model would bind silently
wrong rather than failing. Anything deserialized from an external API should be covered by a test
that goes through a real HTTP round trip, not a mock.

### 6.2 `ddl-auto: validate` is strict, and that is the point

Two incidents so far: `citext` (fixed by normalising case at the write boundary) and a `jsonb`
column mapped as a bare `String` (fixed with `@JdbcTypeCode(SqlTypes.JSON)`). Never weaken
validation to make a mapping fit.

### 6.3 Spring Data interface naming is inconsistent, deliberately

`internal/catalog` uses plural (`GithubRepositories`, `ManagedRepositories`) because
`GithubRepositoryRepository` reads as a typo — "repository" means both a Git repository and the
Spring Data pattern. Everywhere else the `XRepository` convention holds. Normalise everything to
plural if this spreads.

---

## 7. Manual verification checklist

**Worked through completely on 2026-08-17.** Every item below has now been run against real
GitHub. Four of them failed the first time and are recorded in §1.9 through §1.12.

- [x] Log in with GitHub; a user, workspace and owner membership are created
- [x] `GET /api/session` returns a **non-null** `activeWorkspaceId`
- [x] Confirm the OAuth consent screen requests **only** `read:user` and `user:email` — no repo access
- [x] `GET /api/installations/start` redirects to the right App install page
- [x] Complete the install; the callback binds and redirects
- [x] `GET /api/repositories` lists the repositories chosen during install, all with `managed: false`
- [x] `POST /api/repositories/{id}/manage` flips exactly one to `managed: true`
- [x] Change the App's repository selection on GitHub, then `POST /api/repositories/sync/{installationId}` — the list updates
- [x] Remove a *managed* repository's access; its status becomes `ACCESS_LOST`
- [x] Replay the setup callback URL from browser history — rejected as a spent nonce
- [x] Hand-edit `installation_id` in the callback URL to any other number — rejected, and an
      `INSTALLATION_BIND_REJECTED` row appears in `audit_events`. **Ran 2026-08-17 and it failed
      with a 500** — see §1.11
- [x] Verify no GitHub token, and no App private key, appears in application logs
- [x] Restart with a deliberately wrong `FORGESTACK_GITHUB_APP_ID` and replay the callback — expect a
      **500** naming the credentials, not a `403`. This is the §1.1 fix, and the one item here that
      cannot be checked against `FakeGithub`.
      **Ran 2026-08-17 and it failed**, then was fixed — see §1.11.

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

### 3.1 `audit_events` partitions run out

`V1__baseline.sql` creates partitions for **2026-08 and 2026-09 only**, plus a DEFAULT partition.
Nothing creates future ones. Rows will land in DEFAULT and keep working, so this fails quietly
rather than loudly — which is why it is written down.

**Needs:** a scheduled partition-creation job, or partitions provisioned a year ahead.

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

Once real credentials exist, this is what has never been exercised end to end:

- [ ] Log in with GitHub; a user, workspace and owner membership are created
- [ ] `GET /api/session` returns a **non-null** `activeWorkspaceId`
- [ ] Confirm the OAuth consent screen requests **only** `read:user` and `user:email` — no repo access
- [ ] `GET /api/installations/start` redirects to the right App install page
- [ ] Complete the install; the callback binds and redirects
- [ ] `GET /api/repositories` lists the repositories chosen during install, all with `managed: false`
- [ ] `POST /api/repositories/{id}/manage` flips exactly one to `managed: true`
- [ ] Change the App's repository selection on GitHub, then `POST /api/repositories/sync/{installationId}` — the list updates
- [ ] Remove a *managed* repository's access; its status becomes `ACCESS_LOST`
- [ ] Replay the setup callback URL from browser history — rejected as a spent nonce
- [ ] Hand-edit `installation_id` in the callback URL to any other number — rejected, and an
      `INSTALLATION_BIND_REJECTED` row appears in `audit_events`
- [ ] Verify no GitHub token, and no App private key, appears in application logs
- [ ] Restart with a deliberately wrong `FORGESTACK_GITHUB_APP_ID` and replay the callback — expect a
      **500** naming the credentials, not a `403`. This is the §1.1 fix, and the one item here that
      cannot be checked against `FakeGithub`

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

Surfacing the 401 leaks nothing: it depends only on Forge's own configuration and never on the id
asked for, so it is not an oracle. 403 and 404 stay deliberately identical to the caller.

Covered by `InstallationBindingServiceTest.badAppCredentialsFailLoudly`, watched failing
("Expecting code to raise a throwable") against the old behaviour before the fix landed.

*Never part of this gap:* a **malformed** key. `GithubAppJwtService.parsePrivateKey` already detects
PKCS#1 and throws with the exact `openssl` command.

### 1.2 The session cookie is `Secure`, and `curl` will not send it over HTTP

`forge.security.cookie-secure` defaults to `true`, so `ForgeSessionCookie` marks the cookie
`Secure`. Over plain `http://localhost:8080` **`curl` withholds it**, and every authenticated step
of the §7 checklist fails with a bare 401 that reads as broken authentication.

Browsers permit `Secure` cookies on `localhost`, which makes this worse rather than better: the flow
works in Chrome and fails in `curl` within the same session.

**Deliberately not fixed by changing the default** — a default that is insecure for local
convenience is how it reaches production. Override at run time:

```
SPRING_APPLICATION_JSON='{"forge":{"security":{"cookie-secure":false}}}' ./gradlew bootRun
```

### 1.3 GitHub hands out a PKCS#1 private key; the JDK cannot read it

Already handled with an actionable error naming the exact `openssl` command
(`GithubAppJwtService.parsePrivateKey`), but expect to hit it once. Convert with:

```
openssl pkcs8 -topk8 -inform PEM -outform PEM -nocrypt \
  -in github-app.private-key.pem -out github-app.pkcs8.pem
```

### 1.4 Organization installs are refused

Only personal-account installs bind. Install the App on a **personal account**, not an org, or you
will get `409 ORGANIZATION_NOT_SUPPORTED`. See §4.1 — this is a product limitation, not a bug.

### 1.5 An expired session on the setup callback gives a bare 401

The callback lives under `/api/**`, which requires authentication and has no login redirect. If the
session expires mid-install you get a blank 401 rather than being sent to log in. Acceptable with no
browser client; revisit when there is one.

---

## 2. Security debt

| Gap | Risk | Trigger to fix |
|---|---|---|
| **Installation tokens cached in Redis as plaintext** | Redis is credential-bearing infrastructure today. Tokens are short-lived and narrowly scoped, which bounds it. | `platform.crypto` envelope encryption |
| **Database passwords in `application.yaml`** (`forge_app`/`forge_app`) | Local-only values, but the shape invites a real one being pasted there | Before any deployed environment |
| **CSRF disabled for `/api/**`** | Session cookie is `SameSite=Lax` and the API is same-origin, so exposure is limited | Browser client lands |
| **No sandbox isolation yet** | Not applicable until the sandbox exists, but the plan's residual-risk note (container ≠ VM boundary) stands | Before public self-serve signup — gVisor is the cheap next step |

---

## 3. Operational gaps

### 3.1 `audit_events` partitions run out

`V1__baseline.sql` creates partitions for **2026-08 and 2026-09 only**, plus a DEFAULT partition.
Nothing creates future ones. Rows will land in DEFAULT and keep working, so this fails quietly
rather than loudly — which is why it is written down.

**Needs:** a scheduled partition-creation job, or partitions provisioned a year ahead.

### 3.2 Uninstalls and suspensions are not noticed

There is no webhook handling (Phase 6). If a customer uninstalls the App or suspends it, Forge keeps
believing it has access until something fails. `InstallationTokenService.evict` exists for this but
has no caller.

**Until then:** `POST /api/repositories/sync/{installationId}` is the only way to notice a change.

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

`ForgePrincipal.activeWorkspaceId` is now load-bearing for every installation and repository
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
| **No HTTP-layer tests** | Controllers are untested; every test drives services directly. Status codes, `@AuthenticationPrincipal` binding, and JSON shape are unverified. A MockMvc slice is the obvious next step. |
| **Pagination untested** | `GithubAppClient.listRepositories` pages at 100 with `MAX_PAGES=50`. No test exercises more than one page. |
| **No real-GitHub smoke test** | Everything runs against `FakeGithub`. The fake is a real HTTP server so serialization is genuine, but nothing has ever talked to github.com. |
| **Error responses barely covered** | `FakeGithub` now serves 401 (`FakeGithub.unauthorized()`) and 404, which is enough for §1.1. No test exercises 403, a 5xx, or a timeout on any GitHub call. |
| **`sandboxCannotReachGithubCredentials` is vacuous** | The `sandbox` module does not exist, so the rule passes trivially. It has been *proven to fire* against a temporary fixture — but it is not guarding anything yet. |
| **`policyDoesNotDependOnLlm` is vacuous** | Same: neither module exists. |
| **`interfacesMustJustifyThemselves` allows empty** | There are no production interfaces yet besides Spring Data ones. |
| **No test that the app boots with real GitHub config** | Only the fake configuration is exercised at startup. |

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
- [ ] Restart with a deliberately wrong `FORGE_GITHUB_APP_APP_ID` and replay the callback — expect a
      **500** naming the credentials, not a `403`. This is the §1.1 fix, and the one item here that
      cannot be checked against `FakeGithub`

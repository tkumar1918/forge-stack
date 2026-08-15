# Local setup with real GitHub credentials

How to point ForgeStack at a real GitHub account for the first time. GitHub's two registration forms
demand a handful of URLs; **most of them are required and inert, and exactly two decide whether the
flows work.** Knowing which two is the point of this document.

There is no frontend yet, so **everything below is the backend on `localhost:8080`** (Spring Boot's
default; nothing in `application.yaml` overrides it).

---

## Required is not the same as load-bearing

Two separate questions, and conflating them means agonising over a field nothing reads:

> **Required** — the form will not submit without it.
> **Load-bearing** — GitHub redirects a browser here, so a wrong value breaks the flow.

Only two fields are load-bearing: the OAuth App's **Redirect URI** and the GitHub App's **Setup
URL**. Both must be the backend, because Spring Security and `InstallationController` own those
paths. That is the test to apply to any field GitHub adds later — *does a browser get
sent here?*

**Homepage URL is required on both forms and load-bearing on neither.** It is a display field: the
"Homepage" link on the App's public page, and a line on the install screen. GitHub checks only that
it parses as a URL — it never fetches it, and it does not have to resolve.

So `http://localhost:8080` is fine. Your repository URL is the more honest value for a field whose
only job is to be looked at, and for an App installable on one personal account nobody but you will
ever see it. Point it at the frontend when one exists.

---

## 1. OAuth App — identifies humans

*Settings → Developer settings → OAuth Apps → New OAuth App*

| Field | Value | What it does |
|---|---|---|
| Application name | `ForgeStack (local)` | Shown on the consent screen |
| Homepage URL | `http://localhost:8080` | Nothing — required by the form, displayed only |
| **Redirect URI** | `http://localhost:8080/login/oauth2/code/github` | **Load-bearing** — Spring Security owns this path |
| Allow wildcard matching | **unchecked** | A wildcard redirect lets any matching subdomain or path receive the authorization code |
| Enable Device Flow | unchecked | Not used |
| Expire user access tokens | either | Irrelevant — ForgeStack discards the user token after the profile fetch and never stores it |

GitHub used to call the Redirect URI field **"Authorization callback URL"**, and older guides still
do. It is now a repeatable list of up to ten; one entry is all ForgeStack needs.

That value is not a choice. `application.yaml` sets no `redirect-uri`, so Spring Security builds it
from the default template `{baseUrl}/login/oauth2/code/{registrationId}` and the registration is
named `github`. Exactly that string, no trailing slash.

Scopes are not configured here; ForgeStack requests `read:user` and `user:email` at authorization time
(`application.yaml`). If the consent screen ever asks for repository access, something is wrong —
that is the property `GithubOAuthScopeTest` exists to protect.

```bash
export FORGESTACK_GITHUB_OAUTH_CLIENT_ID=...
export FORGESTACK_GITHUB_OAUTH_CLIENT_SECRET=...
```

Those two names are literal — `application.yaml` references them as explicit placeholders.

## 2. GitHub App — grants the agent authority

*Settings → Developer settings → GitHub Apps → New GitHub App*

| Field | Value | What it does |
|---|---|---|
| GitHub App name | `ForgeStack (local)` | Also generates the slug — see `FORGESTACK_GITHUB_APP_SLUG` below |
| Homepage URL | `http://localhost:8080` | Nothing — required by the form, displayed only |
| Redirect URI | **leave blank** | This is the *App's own* user-auth flow. ForgeStack identifies humans through the separate OAuth App above, so nothing consumes it |
| Request user authorization (OAuth) during installation | **unchecked** | Would change the install flow. It is option 1 of the deferred org-support decision (`known-gaps.md` §4.1), not a setting to enable casually |
| Setup URL | `http://localhost:8080/api/installations/callback` | **Load-bearing** — `InstallationController.callback` |
| Redirect on update | ticked | Sends `setup_action=update` back through the same verification |
| Webhook → Active | **unchecked** | Not built until Phase 6 |
| Where can this be installed | Only on this account | Org installs are refused by design |

The two "leave it alone" rows are the easy mistakes: the App form has its own Redirect URI field
that looks exactly like the one that *is* required on the OAuth form. It is a different flow.

A GitHub App also issues a **Client ID and client secret**. ForgeStack uses neither — App authentication
is private key → JWT. They exist for the user-auth flow left disabled above.

**Install it on your personal account.** Organization installs are rejected with
`409 ORGANIZATION_NOT_SUPPORTED` — a deliberate limitation, not a bug (see `known-gaps.md` §4.1).

### Permissions

| Permission | Level |
|---|---|
| Metadata | Read |
| Contents | Read & write |
| Pull requests | Read & write |
| Issues | Read & write |
| Checks | Read |
| Commit statuses | Read |
| Actions | Read |

**Do not grant Workflows.** That omission is what physically prevents the agent from editing the CI
configuration that grades its own work. It is a design control, not an oversight.

### The private key

Saving the form does **not** create one. Scroll to the bottom of the App's General page and press
*Generate a private key*; the download starts immediately and is the only time you get that key.

What lands is **PKCS#1**, which the JDK cannot read. Convert once:

```bash
openssl pkcs8 -topk8 -inform PEM -outform PEM -nocrypt \
  -in forgestack-app.private-key.pem -out forgestack-app.pkcs8.pem
```

`GithubAppJwtService.parsePrivateKey` detects the unconverted form and throws with this exact
command, so getting it wrong is loud rather than mysterious.

`*.pem` is gitignored. Keep it that way — this key can act as the App on every account it is
installed on.

---

## 3. Environment

Property names below are the relaxed-binding form of `@ConfigurationProperties(prefix =
"forgestack.github.app")` on `GithubAppProperties`, bound via `@ConfigurationPropertiesScan` on
`ForgeStackApplication`.

```bash
export FORGESTACK_GITHUB_APP_ID=...            # numeric App ID, top of the App's General page
export FORGESTACK_GITHUB_APP_SLUG=...              # URL slug — see below, it is not the display name
export FORGESTACK_GITHUB_APP_PRIVATE_KEY_PEM="$(cat forgestack-app.pkcs8.pem)"
```

**The slug is not the App name.** Read it off the App's public page URL —
`https://github.com/apps/<slug>` — where GitHub has lowercased and hyphenated whatever you typed.
`InstallationController.start` builds the install redirect from it, so a wrong slug is a 404 at the
exact moment a user clicks Connect, with nothing else to indicate why.

Pass these as environment variables, never by pasting into `application.yaml`.

`FORGESTACK_GITHUB_APP_WEBHOOK_SECRET` and `FORGESTACK_GITHUB_APP_API_BASE_URL` also bind, but neither is
needed yet — webhooks arrive in Phase 6, and the base URL only changes for GitHub Enterprise.

---

## 4. Running it

```bash
docker compose up -d          # Postgres and Redis
cp .env.example .env          # then fill it in
./scripts/dev.sh
```

**Spring Boot does not read `.env`, and nothing else here does either.** `scripts/dev.sh` is what
loads it — do not delete the script assuming the framework handles this, because it does not. The
script *sources* the file rather than parsing it, which is what makes this work:

```bash
FORGESTACK_GITHUB_APP_PRIVATE_KEY_PEM="$(cat forgestack-app.pkcs8.pem)"
```

The key stays in its own gitignored file instead of being pasted as one enormous line.

The script also passes `--forgestack.security.cookie-secure=false`. The cookie is `Secure` by
default, and over plain `http://localhost:8080` **`curl` will not send it back** — every
authenticated request fails with a bare 401 that reads as broken authentication. Browsers permit
`Secure` on `localhost`, which makes it worse rather than better: the flow works in Chrome and
fails in `curl` in the same session. It is overridden at run time rather than weakened in
`application.yaml`, where it would follow the app to production.

If the GitHub App is misconfigured you will get, on first use:

```
GitHub App is not configured: set forgestack.github.app.id and forgestack.github.app.private-key-pem
```

That is the expected failure, not a bug — it means the environment did not reach the app.

---

## 5. Where the browser lands afterwards

Two knobs, both ForgeStack's own configuration rather than anything GitHub knows about:

| Property | Default | Set by |
|---|---|---|
| `forgestack.security.login-success-redirect` | `/` | `SecurityConfig` |
| `forgestack.github.app.setup-redirect` | `/` | `InstallationController` |

Both stay at `/` while there is no frontend, and no CORS configuration is needed while everything is
same-origin on port 8080. When `forge-frontend` becomes real, these two point at it and a CORS bean
allowing that origin with credentials gets added — in the same commit, when the dev server port is
actually known rather than guessed.

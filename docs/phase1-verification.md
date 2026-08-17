# Phase 1 — what was verified, and how

Closed **2026-08-17**. Written so that "Phase 1 is done" is a claim someone can check rather than
take on trust, and so the next person knows which guarantees are actually held up by something.

Two kinds of evidence appear below and they are not interchangeable. **Automated** means a test in
`./gradlew test`, which runs against Testcontainers and `FakeGithub`. **Live** means it was run
against real GitHub, by hand, on the dates given. Six real bugs were found in Phase 1 code, and
**every one of them was found live, against a green suite** — so the distinction is the most
important thing on this page.

Suite at close: **90 tests, 0 failures.**

---

## 1. Exit criteria

From the plan's Phase 1 table. Each row names what actually holds the criterion up.

| Step | Exit criterion | Evidence | Kind |
|---|---|---|---|
| 1.1 | `ApplicationModules.verify()` passes | `ModularityTest` (3) | Automated |
| 1.2 | A deliberate `FooService`/`FooServiceImpl` fails the build | `AbstractionHygieneTest` (8) + `AbstractionRulesFireTest` (2) | Automated |
| 1.3 | Tenant A sees none of tenant B's rows; missing GUC fails closed | `RowLevelSecurityIsolationTest` (8) | Automated |
| 1.4 | Audit rows cannot be modified by the app role | `RowLevelSecurityIsolationTest.auditIsAppendOnly` + `.noAuditPartitionIsDirectlyWritable` | Automated |
| 1.5 | Log in with GitHub; a workspace exists; no `repo` scope requested | `GithubOAuthScopeTest` (2), `LoginSessionIntegrationTest` (7), `UserProvisioningServiceTest` (4) | Automated + **Live** |
| 1.6 | Install, pick repos, enable one; narrowed token minted and cached; a stranger's `installation_id` rejected | `InstallationBindingServiceTest` (11), `TokenScopeTest` (7), `InstallationTokenServiceTest` (2), `RepositoryCatalogTest` (16), `WriteOwnershipTest` (4) | Automated + **Live** |

`AbstractionRulesFireTest` exists because an ArchUnit rule whose package pattern matches nothing
passes silently. It points the real rules at a fixture containing a deliberate violation and asserts
they reject it — the rule is known to still fire *today*, not to have fired once.

---

## 2. Manual verification, run live against real GitHub

The `known-gaps.md` §7 checklist, worked through completely on **2026-08-17** against account
`QL-Tushar-Kumar`, App `4602643`, installation `154233265`.

| # | Check | Result |
|---|---|---|
| 1 | Login creates user, workspace, owner membership | Pass |
| 2 | `/api/session` returns non-null `activeWorkspaceId` | Pass |
| 3 | Consent screen requests only `read:user`, `user:email` | Pass |
| 4 | `/api/installations/start` redirects to the App install page | Pass |
| 5 | Install callback binds and redirects | Pass |
| 6 | `/api/repositories` lists the chosen repos, all `managed: false` | **Failed → fixed** (§1.12 below) |
| 7 | `POST /{id}/manage` flips exactly one to `managed: true` | Pass — *first time ever run* |
| 8 | Change selection on GitHub, resync — list updates | **Failed → fixed** |
| 9 | Remove a *managed* repo's access → `ACCESS_LOST` | Pass |
| 10 | Replay the setup callback from history → rejected | Pass |
| 11 | Hand-edited `installation_id` → rejected + audit row | **Failed → fixed** |
| 12 | No token or private key in application logs | Pass |
| 13 | Wrong `FORGESTACK_GITHUB_APP_ID` → 500 naming credentials | **Failed → fixed** |

Checks 7 and 9 close the half of criterion 1.6 that had never been exercised: before this,
`managed_repositories` had never held a row against real GitHub.

---

## 3. Bugs found, all found live

Full write-ups in `known-gaps.md`; this is the index.

| # | Bug | Why the suite missed it |
|---|---|---|
| §1.8 | OAuth servlet session shadowed the ForgeStack session — every `@AuthenticationPrincipal` endpoint 500'd in a browser | `curl` carries no servlet session; only a real browser holds both cookies |
| §1.9 | Setup nonce bound to a session id, so a re-login mid-install invalidated it; one rejection reason covered four causes | Tests never span the minutes GitHub's install screen takes |
| §1.10 | A successful login, and a successful install, both rendered a Whitelabel 404 | Nothing asserted on what the redirect target actually serves |
| §3.2 | Reinstall duplicated the catalog, then left the dead installation's repos listed | Reinstall issues a new `installation_id`; no test modelled two installations |
| §1.11 | A guessed `installation_id` returned **500**, not 403; a wrong App ID blamed the user | `FakeGithub` answered 404 with an **empty body**; real GitHub sends JSON |
| §1.12 | Changing repository access on GitHub was refused as a stale link | GitHub's update redirect carries no `state`; no test modelled it |

**The pattern.** Every entry above was invisible to a green suite because the test double was
politer than the real system — it never returned a body, never took minutes, never issued a second
id, never disagreed with itself. A fake that is easier to satisfy than reality certifies code the
world will reject.

§1.12 is the sharpest instance: the operation had already committed on GitHub, and the error page
said it had not. Settling it required asking GitHub's API directly.

---

## 4. What is *not* held up by anything

Stated plainly so it is not mistaken for coverage.

- **No test boots the packaged application.** The suite runs on Testcontainers with overridden
  config and has never started the app the way `./scripts/dev.sh` does. Every startup-only failure
  in `known-gaps.md` §1.1, §1.6, §1.7 lived in that gap, and this session added another: a Flyway
  checksum mismatch that stopped the app dead while the suite stayed green. **Still the
  highest-value missing test.**
- **Two ArchUnit rules pass vacuously.** `sandboxCannotReachGithubCredentials` and
  `policyDoesNotDependOnLlm` guard modules that do not exist yet. Both are proven to fire against a
  fixture, and both arm themselves the moment their module appears.
- **The `GET /app` credential check is live-verified only.** `FakeGithub` is always driven with
  credentials it issued itself, so an App id GitHub has never heard of cannot arise against it.
- **`managed_repositories` uniqueness is enforced but unreachable.** `WriteOwnershipTest` constructs
  two installations exposing one repository — a state GitHub cannot produce — because the constraint
  guards a shared-installation future that does not exist yet.
- **RBAC is scaffolding.** `workspace_members` carries four roles; `IamQueries.roleIn` and
  `isMember` have **zero callers**. Any member of a workspace is currently as powerful as any other.
- **No webhook handling.** Suspensions and uninstalls are still not noticed on their own; binding a
  replacement installation is the only thing that retires a predecessor (`known-gaps.md` §3.2).

---

## 5. Reproducing this

Environment and step-by-step commands are in `docs/local-setup.md`. The short version:

```bash
docker compose up -d
./gradlew test          # expect 90 green
./scripts/dev.sh
```

Then work `known-gaps.md` §7 in a browser signed in as the account that owns the installation — the
install flow only accepts an installation owned by the same GitHub account you are logged into
ForgeStack with, and `/api/session` is what tells you which that is.

**One process note, learned the hard way four times in a day** — and now fixed in `scripts/dev.sh`
rather than left as advice, because advice did not work.

DevTools watches `build/classes`, so *any* `./gradlew test` in another terminal recompiles main
classes, triggers a restart, and re-runs Flyway against whatever migration files are on disk at that
instant. Editing a migration to watch a test fail — the standard way to prove a constraint works —
therefore applies the half-written version to the dev database. The next restart fails checksum
validation and the app will not start, reporting an `entityManagerFactory` dependency error that
names nothing to do with migrations.

`dev.sh` now passes `--spring.devtools.restart.enabled=false`. Restart by hand to pick up changes.


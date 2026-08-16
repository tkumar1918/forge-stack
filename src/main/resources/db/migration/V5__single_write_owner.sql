-- One real GitHub repository has at most one workspace maintaining it.
--
-- Two workspaces autonomously writing to one repository does not merely create coordination
-- difficulty — it silently voids guarantees that are already built. github_action_log is unique on
-- (workspace_id, fingerprint), so a second writing workspace gets its own idempotency ledger and
-- neither can see the other's fingerprints. The protection against "GitHub committed, we crashed
-- before recording it, retry blindly" stops applying across writers without anything failing.
--
-- Enforced here rather than in application logic because a constraint holds even when the code is
-- wrong or bypassed. This is the same reasoning as installation_id's unique constraint in V2, and
-- it resolves the same way: row-level security hides the conflicting row from the caller's SELECT,
-- so the collision surfaces as a constraint violation rather than as a row that was found.
--
-- Today the invariant is trivially satisfied. A repository can only reach one workspace's catalog,
-- because one GitHub account has exactly one installation of the App and installation_id is unique.
-- Adding the guard now is close to free; adding it after installations can be shared means
-- migrating data that may already violate it, with no safe way to choose a winner.

-- managed_repositories keys maintenance on github_repository_id — a workspace-local UUID. Since V4,
-- two workspaces hold *different* UUIDs for the same real repository, so a constraint on that
-- column cannot see a cross-workspace collision. The constraint has to be about GitHub's own
-- identifier for the repository, which therefore has to be present on the row.
ALTER TABLE managed_repositories ADD COLUMN github_repo_id bigint;

-- FORCE ROW LEVEL SECURITY applies to the table owner too, and migrations run as the owner
-- (forgestack_migrator). With no app.workspace_id bound, both tables read as empty here, and the
-- backfill below would quietly update nothing — leaving the NOT NULL to fail later for reasons that
-- look unrelated. Verified directly: the same count is 0 with FORCE and 9 without.
--
-- Lifted only for the backfill and restored immediately after. Postgres DDL is transactional and
-- Flyway runs each migration in one transaction, so a failure anywhere between these two points
-- rolls back the whole file — including the lift. The tables cannot be left unforced.
ALTER TABLE managed_repositories NO FORCE ROW LEVEL SECURITY;
ALTER TABLE github_repositories NO FORCE ROW LEVEL SECURITY;

UPDATE managed_repositories m
   SET github_repo_id = r.github_repo_id
  FROM github_repositories r
 WHERE r.id = m.github_repository_id;

ALTER TABLE github_repositories FORCE ROW LEVEL SECURITY;
ALTER TABLE managed_repositories FORCE ROW LEVEL SECURITY;

ALTER TABLE managed_repositories ALTER COLUMN github_repo_id SET NOT NULL;

-- The invariant itself.
--
-- Partial on ACTIVE, which is the deliberate half of this decision: PAUSED and ACCESS_LOST release
-- the claim. A workspace that has stopped maintaining a repository should not keep a global lock on
-- it that no other workspace can see or appeal, and ACCESS_LOST means the installation stopped
-- exposing it at all. The cost is that re-enabling a paused repository can now fail because someone
-- else claimed it in the meantime — which is the correct, visible outcome rather than two writers.
CREATE UNIQUE INDEX managed_repositories_single_writer
    ON managed_repositories (github_repo_id)
    WHERE status = 'ACTIVE';

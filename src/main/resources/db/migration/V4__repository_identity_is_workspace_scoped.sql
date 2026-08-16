-- A repository's identity is its workspace plus GitHub's numeric id — not the installation.
--
-- V2 scoped uniqueness to (github_installation_id, github_repo_id). GitHub issues a brand new
-- installation_id on every install, so uninstalling and reinstalling the App left the old
-- installation row looking exactly as valid as the new one, each carrying its own copy of the same
-- repositories. Confirmed live: two rounds of that left 18 rows for 9 real repositories, with
-- GET /api/repositories unable to tell them apart.
--
-- Waiting for the Phase 6 webhook to mark the old installation dead would not have fixed this on
-- its own — the listing would still have had to learn to exclude it. Scoping identity to the
-- workspace stops the duplicate being representable at all, whether or not a webhook ever arrives.
--
-- Safe as a widening of scope, not a narrowing: a repository belongs to exactly one GitHub account
-- and an installation is per-account, so two installations in one workspace expose disjoint
-- repositories. Nothing that satisfied the old constraint violates the new one.

ALTER TABLE github_repositories DROP CONSTRAINT github_repositories_uk;

ALTER TABLE github_repositories
    ADD CONSTRAINT github_repositories_uk UNIQUE (workspace_id, github_repo_id);

-- Which installation currently exposes a repository is now a mutable fact about it: a reinstall
-- re-points the existing row rather than inserting a rival. Indexed because sync still reconciles
-- one installation at a time and reads every row belonging to it.
CREATE INDEX github_repositories_installation_idx
    ON github_repositories (github_installation_id);

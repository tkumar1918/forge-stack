-- Make the V2 tenant policies survive an unset workspace.
--
-- `SET LOCAL app.workspace_id` reverts at transaction end, but a custom GUC that has once been
-- assigned reverts to the empty string rather than to NULL. So on any pooled connection that has
-- previously carried a tenant, current_setting('app.workspace_id', true) returns '' — and
-- ''::uuid raises "invalid input syntax for type uuid" instead of matching no rows.
--
-- The effect is a query that works on a fresh connection and fails on a reused one, which is the
-- kind of load-dependent bug that only shows up once there is traffic. V1 already guards against it
-- on audit_events with NULLIF; V2 was written without that guard. This brings the four V2 policies
-- into line.
--
-- Failing closed is the intent: no tenant bound must mean no rows visible, never an error and never
-- every row.

DROP POLICY github_installations_tenant_isolation ON github_installations;
CREATE POLICY github_installations_tenant_isolation ON github_installations
    USING (workspace_id = NULLIF(current_setting('app.workspace_id', true), '')::uuid)
    WITH CHECK (workspace_id = NULLIF(current_setting('app.workspace_id', true), '')::uuid);

DROP POLICY github_repositories_tenant_isolation ON github_repositories;
CREATE POLICY github_repositories_tenant_isolation ON github_repositories
    USING (workspace_id = NULLIF(current_setting('app.workspace_id', true), '')::uuid)
    WITH CHECK (workspace_id = NULLIF(current_setting('app.workspace_id', true), '')::uuid);

DROP POLICY managed_repositories_tenant_isolation ON managed_repositories;
CREATE POLICY managed_repositories_tenant_isolation ON managed_repositories
    USING (workspace_id = NULLIF(current_setting('app.workspace_id', true), '')::uuid)
    WITH CHECK (workspace_id = NULLIF(current_setting('app.workspace_id', true), '')::uuid);

DROP POLICY github_action_log_tenant_isolation ON github_action_log;
CREATE POLICY github_action_log_tenant_isolation ON github_action_log
    USING (workspace_id = NULLIF(current_setting('app.workspace_id', true), '')::uuid)
    WITH CHECK (workspace_id = NULLIF(current_setting('app.workspace_id', true), '')::uuid);

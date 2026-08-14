package dev.tushar.forge.platform.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tushar.forge.support.AbstractIntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The Phase 1 exit criterion: tenant isolation is enforced by the database, not by remembering to
 * write {@code WHERE workspace_id = ?}.
 *
 * <p>One forgotten predicate in one query is a cross-tenant data breach. RLS makes the database
 * the backstop, and these tests are what prove the backstop is actually armed — an RLS policy that
 * silently is not in force looks identical to one that is, until it doesn't.
 */
class RowLevelSecurityIsolationTest extends AbstractIntegrationTest {

    private static final UUID WORKSPACE_A = UUID.fromString("aaaaaaaa-0000-4000-8000-000000000001");
    private static final UUID WORKSPACE_B = UUID.fromString("bbbbbbbb-0000-4000-8000-000000000002");

    @Autowired
    private TenantScope tenantScope;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void seedAuditEvents() {
        // Each row must be written inside its own tenant scope: the policy's WITH CHECK clause
        // rejects an insert that does not match the bound workspace, which is the point.
        tenantScope.runInTenant(WORKSPACE_A, () -> insertAuditEvent(WORKSPACE_A, "workspace.a.event"));
        tenantScope.runInTenant(WORKSPACE_B, () -> insertAuditEvent(WORKSPACE_B, "workspace.b.event"));
    }

    @Test
    @DisplayName("a tenant sees only its own rows")
    void tenantSeesOnlyItsOwnRows() {
        List<String> seenByA = tenantScope.runInTenant(WORKSPACE_A, this::selectAllActions);
        List<String> seenByB = tenantScope.runInTenant(WORKSPACE_B, this::selectAllActions);

        assertThat(seenByA).contains("workspace.a.event").doesNotContain("workspace.b.event");
        assertThat(seenByB).contains("workspace.b.event").doesNotContain("workspace.a.event");
    }

    @Test
    @DisplayName("without a tenant bound, tenant-scoped reads return nothing rather than everything")
    void missingTenantContextFailsClosed() {
        List<String> seen = tenantScope.runWithoutTenant(this::selectAllActions);

        // The dangerous failure would be returning every tenant's rows. An unset GUC compares to
        // nothing, so the policy matches no rows: it fails closed.
        assertThat(seen).isEmpty();
    }

    @Test
    @DisplayName("requiring a tenant outside a scope raises a clear error, not an empty result")
    void requireWorkspaceIdThrowsOutsideScope() {
        assertThatThrownBy(TenantContext::requireWorkspaceId)
                .isInstanceOf(MissingTenantContextException.class)
                .hasMessageContaining("No tenant context is active");
    }

    @Test
    @DisplayName("a tenant cannot write rows belonging to another tenant")
    void writesAreConstrainedToTheActiveTenant() {
        assertThatThrownBy(() -> tenantScope.runInTenant(WORKSPACE_A, () -> {
                    insertAuditEvent(WORKSPACE_B, "smuggled");
                    return null;
                }))
                // The WITH CHECK clause rejects it; without that clause a tenant could write rows
                // it would then be unable to read. Asserting on the stack trace rather than the
                // top-level message because Spring wraps the driver error.
                .hasStackTraceContaining("row-level security");
    }

    @Test
    @DisplayName("the tenant binding does not survive the transaction")
    void tenantBindingIsTransactionScoped() {
        tenantScope.runInTenant(WORKSPACE_A, this::selectAllActions);

        // If TenantScope used SET rather than SET LOCAL, this pooled connection would still be
        // carrying workspace A and this read would return A's rows. That is the cross-tenant leak
        // this test exists to catch.
        List<String> afterScope = tenantScope.runWithoutTenant(this::selectAllActions);

        assertThat(afterScope).isEmpty();
        assertThat(TenantContext.currentWorkspaceId()).isEmpty();
    }

    @Test
    @DisplayName("audit history cannot be rewritten by the application role")
    void auditIsAppendOnly() {
        assertThatThrownBy(() -> tenantScope.runInTenant(WORKSPACE_A, () -> {
                    jdbcTemplate.update("UPDATE audit_events SET action = 'tampered'");
                    return null;
                }))
                .hasStackTraceContaining("permission denied");

        assertThatThrownBy(() -> tenantScope.runInTenant(WORKSPACE_A, () -> {
                    jdbcTemplate.update("DELETE FROM audit_events");
                    return null;
                }))
                .hasStackTraceContaining("permission denied");
    }

    private List<String> selectAllActions() {
        // Deliberately no workspace_id predicate: the point is that the database adds it.
        return jdbcTemplate.queryForList("SELECT action FROM audit_events", String.class);
    }

    private void insertAuditEvent(UUID workspaceId, String action) {
        jdbcTemplate.update(
                "INSERT INTO audit_events (workspace_id, actor_type, action) VALUES (?, 'SYSTEM', ?)",
                workspaceId,
                action);
    }
}

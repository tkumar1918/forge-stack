package dev.tushar.forgestack.platform.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tushar.forgestack.support.AbstractIntegrationTest;
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

    /**
     * The same guarantee, checked where it is actually easy to lose.
     *
     * <p>{@link #auditIsAppendOnly()} goes through the parent table, and permission is checked on
     * the relation the statement names — so it passes on the parent's grants no matter what any
     * partition allows. {@code UPDATE audit_events_2027_01} is a different check, against a
     * different set of grants.
     *
     * <p>Those grants default to permissive: {@code ALTER DEFAULT PRIVILEGES} hands the application
     * full DML on every table the migrator creates, and each partition is only pulled back to
     * INSERT + SELECT by an explicit REVOKE. Forgetting it on one month's partition would leave
     * that month rewritable while every test above still passed. Asserted over all partitions at
     * once so it covers the ones that do not exist yet.
     */
    @Test
    @DisplayName("no audit partition is directly writable by the application role")
    void noAuditPartitionIsDirectlyWritable() {
        List<String> writable = jdbcTemplate.queryForList(
                """
                SELECT c.relname || ':' || a.privilege_type
                  FROM pg_class c
                  JOIN pg_inherits i ON i.inhrelid = c.oid
                  JOIN pg_class parent ON parent.oid = i.inhparent
                 CROSS JOIN LATERAL aclexplode(c.relacl) a
                  JOIN pg_roles grantee ON grantee.oid = a.grantee
                 WHERE parent.relname = 'audit_events'
                   AND grantee.rolname = 'forgestack_app'
                   AND a.privilege_type IN ('UPDATE', 'DELETE', 'TRUNCATE')
                 ORDER BY 1
                """,
                String.class);

        assertThat(writable)
                .as("every audit_events partition must be INSERT + SELECT only for the app role")
                .isEmpty();
    }

    /** Partitions exist well past today, so rows are not quietly accumulating in DEFAULT. */
    @Test
    @DisplayName("audit partitions are provisioned ahead of time")
    void auditPartitionsAreProvisionedAhead() {
        Integer monthsAhead = jdbcTemplate.queryForObject(
                """
                SELECT count(*) FROM pg_class c
                  JOIN pg_inherits i ON i.inhrelid = c.oid
                  JOIN pg_class parent ON parent.oid = i.inhparent
                 WHERE parent.relname = 'audit_events'
                   AND c.relname ~ '^audit_events_[0-9]{4}_[0-9]{2}$'
                   AND to_date(right(c.relname, 7), 'YYYY_MM') > now()
                """,
                Integer.class);

        // Not a specific count: the point is runway, and pinning the exact horizon would make this
        // fail every time someone extends it.
        assertThat(monthsAhead)
                .as("audit_events has no future partitions, so rows will land in DEFAULT — and once "
                        + "they do, that month's partition can no longer be created without moving them")
                .isNotNull()
                .isGreaterThanOrEqualTo(6);
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

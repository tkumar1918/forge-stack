package dev.tushar.forgestack.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tushar.forgestack.platform.tenancy.TenantScope;
import dev.tushar.forgestack.support.AbstractIntegrationTest;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * That the database, and not the author of a statement, decides who may write to a running task.
 *
 * <p>The writes below are deliberately naive — plain {@code UPDATE tasks} through a {@code
 * JdbcTemplate}, exactly what somebody adding a feature in six months will write without thinking
 * about leases at all. Every guarantee here has to hold for that person, because the statement that
 * forgets is never the statement anyone is reviewing.
 */
class LeaseFencingTest extends AbstractIntegrationTest {

    private static final Duration TTL = Duration.ofMinutes(5);

    @Autowired
    private TaskLeases leases;

    @Autowired
    private LeaseScope leaseScope;

    @Autowired
    private LeaseReconciler reconciler;

    @Autowired
    private TenantScope tenantScope;

    @Autowired
    private JdbcTemplate jdbc;

    private TaskRows rows;
    private UUID workspaceId;
    private UUID taskId;

    @BeforeEach
    void aRunningTask() {
        this.rows = new TaskRows(tenantScope, leases, leaseScope, jdbc);
        this.workspaceId = rows.newWorkspace();
        this.taskId = rows.newTask(workspaceId, "RUNNING");
    }

    @Test
    @DisplayName("a write that carries no lease is refused while somebody holds the task")
    void anUnfencedWriteIsRefused() {
        leases.acquire(workspaceId, taskId, "worker-1", TTL).orElseThrow();

        assertThatThrownBy(() -> tenantScope.runInTenant(workspaceId, () -> retitle("stomped")))
                .hasStackTraceContaining("carried no lease")
                .hasStackTraceContaining(taskId.toString());

        assertThat(titleOf()).isNotEqualTo("stomped");
    }

    @Test
    @DisplayName("a write that carries the current claim goes through")
    void aFencedWriteIsAllowed() {
        Lease lease = leases.acquire(workspaceId, taskId, "worker-1", TTL).orElseThrow();

        assertThatCode(() -> leaseScope.runUnderLease(lease, () -> retitle("mine to change")))
                .doesNotThrowAnyException();

        assertThat(titleOf()).isEqualTo("mine to change");
    }

    @Test
    @DisplayName("a write that carries a claim taken over since is refused")
    void aStaleClaimIsRefused() {
        Lease stale = leases.acquire(workspaceId, taskId, "worker-1", TTL).orElseThrow();
        rows.expireLease(workspaceId, taskId);
        reconciler.reconcile(workspaceId);
        leases.acquire(workspaceId, taskId, "worker-2", TTL).orElseThrow();

        assertThatThrownBy(() -> leaseScope.runUnderLease(stale, () -> retitle("stomped")))
                .hasStackTraceContaining("this write carried epoch " + stale.epoch())
                .hasStackTraceContaining("stop rather than retry");
    }

    /**
     * A claim authorises writes to <em>one</em> task, not to whatever else the transaction touches.
     *
     * <p>Epochs are per-row counters starting at zero, so two tasks sitting at the same epoch is the
     * normal case rather than a coincidence. Binding only the number would let a scope opened for one
     * task wave through a write to any other task that had changed hands as often.
     */
    @Test
    @DisplayName("a claim on one task does not authorise a write to another")
    void aClaimDoesNotTravel() {
        UUID other = rows.newTask(workspaceId, "RUNNING");
        Lease lease = leases.acquire(workspaceId, taskId, "worker-1", TTL).orElseThrow();
        Lease otherLease = leases.acquire(workspaceId, other, "worker-2", TTL).orElseThrow();
        assertThat(otherLease.epoch())
                .as("both tasks are at the same epoch, which is what makes this worth testing")
                .isEqualTo(lease.epoch());

        assertThatThrownBy(() -> leaseScope.runUnderLease(lease, () -> retitle(other, "stomped")))
                .hasStackTraceContaining("but is writing to task " + other);
    }

    /**
     * The scope ends with the transaction, exactly as the tenant binding does.
     *
     * <p>{@code SET LOCAL} rather than {@code SET}, because a pooled connection returned while still
     * carrying a claim would authorise the next borrower's unfenced write against a task it has never
     * heard of. Same trap as leaking a tenant id, with a quieter symptom.
     */
    @Test
    @DisplayName("the claim does not outlive its transaction")
    void theBindingIsTransactionScoped() {
        Lease lease = leases.acquire(workspaceId, taskId, "worker-1", TTL).orElseThrow();
        leaseScope.runUnderLease(lease, () -> retitle("fenced"));

        assertThatThrownBy(() -> tenantScope.runInTenant(workspaceId, () -> retitle("unfenced")))
                .hasStackTraceContaining("carried no lease");
    }

    @Test
    @DisplayName("a lapsed claim blocks nobody — that is what lets the reconciler take it back")
    void aLapsedClaimIsNotProtected() {
        leases.acquire(workspaceId, taskId, "worker-1", TTL).orElseThrow();
        rows.expireLease(workspaceId, taskId);

        assertThatCode(() -> tenantScope.runInTenant(workspaceId, () -> retitle("reclaimed")))
                .as("expiry is the escape hatch and the recovery path at once, so there is no "
                        + "separate bypass to add later or to reach for by mistake")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a task nobody holds takes ordinary writes")
    void anUnclaimedTaskIsUnaffected() {
        assertThatCode(() -> tenantScope.runInTenant(workspaceId, () -> retitle("ordinary")))
                .doesNotThrowAnyException();

        assertThat(titleOf()).isEqualTo("ordinary");
    }

    // ---------------------------------------------------------------------------------------

    private int retitle(String title) {
        return retitle(taskId, title);
    }

    private int retitle(UUID id, String title) {
        return jdbc.update("UPDATE tasks SET title = ? WHERE id = ?", title, id);
    }

    private String titleOf() {
        return tenantScope.runInTenant(
                workspaceId, () -> jdbc.queryForObject("SELECT title FROM tasks WHERE id = ?", String.class, taskId));
    }
}

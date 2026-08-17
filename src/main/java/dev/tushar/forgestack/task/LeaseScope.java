package dev.tushar.forgestack.task;

import dev.tushar.forgestack.platform.tenancy.TenantScope;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Runs work inside a transaction that carries a worker's claim on a task.
 *
 * <p>The sibling of {@link TenantScope}, and the same idea applied to a different question. That one
 * answers "which tenant is this?"; this one answers "which claim is this write made under?". Both
 * bind a GUC that Postgres checks on every affected row, so the guarantee holds for statements whose
 * authors never thought about it — which is the only kind of guarantee worth having, because the
 * statement that forgets is never the one anybody is looking at.
 *
 * <p><strong>Why the binding is transaction-scoped.</strong> {@code set_config(..., is_local => true)}
 * is {@code SET LOCAL}: it reverts when the transaction ends. A plain {@code SET} is
 * <em>connection</em>-scoped, and with a pool that connection goes back carrying a claim on a task
 * the next borrower knows nothing about — so the next unfenced write would be waved through by a
 * lease belonging to something else entirely. That is the same trap as leaking a tenant id, with a
 * quieter symptom.
 *
 * <p>Cleared on the way out as well as bound on the way in, because a {@code TransactionTemplate}
 * joins an existing transaction rather than nesting: without the clear, a lease scope in the middle
 * of a longer transaction would authorise everything that came after it.
 */
@Component
public class LeaseScope {

    private final TenantScope tenantScope;
    private final JdbcTemplate jdbc;

    LeaseScope(TenantScope tenantScope, JdbcTemplate jdbc) {
        this.tenantScope = tenantScope;
        this.jdbc = jdbc;
    }

    /**
     * Runs {@code body} in a transaction bound to {@code lease}'s workspace and claim.
     *
     * <p>This is the only way to write to a task somebody is currently running. Passing a lease that
     * has since been taken over does not fail here — it fails at the write, which is the correct
     * place: whether the claim still holds is a fact about the database at the moment of writing,
     * and anything checked earlier is a fact about the past.
     */
    public <T> T runUnderLease(Lease lease, Supplier<T> body) {
        if (lease == null) {
            throw new IllegalArgumentException("no lease: worker writes are made under a claim, never bare");
        }
        return tenantScope.runInTenant(lease.workspaceId(), () -> {
            bind(lease.taskId().toString(), Long.toString(lease.epoch()));
            try {
                return body.get();
            } finally {
                bind("", "");
            }
        });
    }

    /** Runs {@code body} in a transaction bound to {@code lease}'s workspace and claim. */
    public void runUnderLease(Lease lease, Runnable body) {
        runUnderLease(lease, () -> {
            body.run();
            return null;
        });
    }

    /**
     * The claim the current transaction is writing under, for tests and for diagnostics.
     *
     * <p>Reads it back out of the database session rather than out of a field, so what it reports is
     * what Postgres will actually enforce.
     */
    public UUID currentTaskId() {
        String taskId = jdbc.queryForObject("SELECT nullif(current_setting('app.lease_task', true), '')", String.class);
        return taskId == null ? null : UUID.fromString(taskId);
    }

    private void bind(String taskId, String epoch) {
        // set_config rather than literal SQL: SET does not take bind parameters, and interpolating a
        // value into statement text is how injection happens.
        jdbc.queryForObject("SELECT set_config('app.lease_task', ?, true)", String.class, taskId);
        jdbc.queryForObject("SELECT set_config('app.lease_epoch', ?, true)", String.class, epoch);
    }
}

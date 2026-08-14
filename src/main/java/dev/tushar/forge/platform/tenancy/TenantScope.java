package dev.tushar.forge.platform.tenancy;

import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs work inside a transaction bound to one workspace.
 *
 * <p>This is the only sanctioned way to touch tenant-scoped data. It opens a transaction, binds
 * {@code app.workspace_id} to it, and runs the body. Postgres row-level security does the rest.
 *
 * <p><strong>Why the binding is transaction-scoped.</strong> The GUC is set through
 * {@code set_config(..., is_local => true)}, which is {@code SET LOCAL}: it reverts when the
 * transaction ends. A plain {@code SET} is <em>connection</em>-scoped, and with a connection pool
 * that connection returns to the pool still carrying the previous tenant's id — a cross-tenant
 * leak that is intermittent, load-dependent, and close to impossible to reproduce. This is the
 * single most dangerous mistake available in an RLS setup, so it is made structurally impossible
 * here rather than left to review.
 *
 * <p>{@code set_config} is used rather than literal SQL because {@code SET} does not accept bind
 * parameters, and interpolating a value into DDL-ish SQL is how injection happens.
 *
 * <p>Deliberately explicit rather than an around-advice on {@code @Transactional}: an aspect would
 * have to be ordered to run <em>inside</em> the transaction interceptor, and getting that ordering
 * subtly wrong fails open. An explicit scope is harder to misuse and trivial to test.
 */
@Component
public class TenantScope {

    private final TransactionTemplate transactionTemplate;
    private final JdbcTemplate jdbcTemplate;

    public TenantScope(TransactionTemplate transactionTemplate, JdbcTemplate jdbcTemplate) {
        this.transactionTemplate = transactionTemplate;
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Runs {@code body} in a transaction scoped to {@code workspaceId} and returns its result. */
    public <T> T runInTenant(UUID workspaceId, Supplier<T> body) {
        if (workspaceId == null) {
            throw new MissingTenantContextException();
        }
        return transactionTemplate.execute(status -> {
            TenantContext.set(workspaceId);
            try {
                bindToTransaction(workspaceId);
                return body.get();
            } finally {
                // Clear before the transaction commits so the thread never escapes with a tenant
                // still attached. The database side reverts on its own when the transaction ends.
                TenantContext.clear();
            }
        });
    }

    /** Runs {@code body} in a transaction scoped to {@code workspaceId}. */
    public void runInTenant(UUID workspaceId, Runnable body) {
        runInTenant(workspaceId, () -> {
            body.run();
            return null;
        });
    }

    /**
     * Runs {@code body} in a transaction with <em>no</em> tenant bound.
     *
     * <p>For the authentication path only: sessions, users, and workspace membership must be
     * readable before a workspace is known. Those tables carry no workspace-owned data and are
     * excluded from RLS for exactly this reason. Anything tenant-scoped read here returns nothing.
     */
    public <T> T runWithoutTenant(Supplier<T> body) {
        return transactionTemplate.execute(status -> {
            TenantContext.clear();
            return body.get();
        });
    }

    private void bindToTransaction(UUID workspaceId) {
        jdbcTemplate.queryForObject(
                "SELECT set_config('app.workspace_id', ?, true)", String.class, workspaceId.toString());
    }
}

package dev.tushar.forgestack.task;

import dev.tushar.forgestack.platform.tenancy.TenantScope;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Claiming a task, keeping the claim alive, and giving it back.
 *
 * <p>Three operations, and each is a single conditional statement carrying the epoch. That shape is
 * the point: a worker never reads the lease and then acts on what it read, because between the read
 * and the act it may already have been replaced. Every method here asks the database to do the thing
 * <em>if</em> the claim is still ours, and reports whether it did.
 *
 * <p><strong>A rule for everything built on top of this.</strong> Any write a worker makes to its
 * task must carry {@code AND lease_epoch = :epoch} in the same statement. A lease that is only
 * consulted, rather than made part of the write, does not stop the stalled worker it exists to stop.
 * Nothing enforces this mechanically yet — see {@code docs/known-gaps.md}.
 */
@Service
public class TaskLeases {

    private final TenantScope tenantScope;
    private final JdbcTemplate jdbc;

    TaskLeases(TenantScope tenantScope, JdbcTemplate jdbc) {
        this.tenantScope = tenantScope;
        this.jdbc = jdbc;
    }

    /**
     * Claims {@code taskId} for {@code owner}, if nobody holds a live claim on it.
     *
     * <p>Empty means somebody else has it. That is an ordinary answer, not an error: it is what a
     * duplicate queue delivery looks like from here, and duplicates are expected.
     */
    public Optional<Lease> acquire(UUID workspaceId, UUID taskId, String owner, Duration ttl) {
        return tenantScope.runInTenant(workspaceId, () -> {
            List<Lease> claimed = jdbc.query(
                    """
                    UPDATE tasks
                       SET lease_owner = ?,
                           -- Bumped on every handover, which is what makes the previous holder's
                           -- epoch stale and its next write a no-op.
                           lease_epoch = lease_epoch + 1,
                           lease_expires_at = now() + make_interval(secs => ?),
                           version = version + 1,
                           updated_at = now()
                     WHERE id = ?
                       AND (lease_expires_at IS NULL OR lease_expires_at <= now())
                    RETURNING lease_epoch, lease_expires_at
                    """,
                    (rs, row) -> new Lease(
                            taskId, owner, rs.getLong("lease_epoch"), rs.getTimestamp("lease_expires_at")
                                    .toInstant()),
                    owner,
                    (double) ttl.toMillis() / 1000,
                    taskId);
            return claimed.stream().findFirst();
        });
    }

    /**
     * Extends a claim. False means it is no longer ours, and the only correct response is to stop.
     *
     * <p>Not "retry the heartbeat": a false here means another worker is already running this task,
     * and continuing would mean two workers doing the same work with the same side effects.
     */
    public boolean renew(UUID workspaceId, Lease lease, Duration ttl) {
        return tenantScope.runInTenant(workspaceId, () -> jdbc.update(
                        """
                        UPDATE tasks
                           SET lease_expires_at = now() + make_interval(secs => ?),
                               updated_at = now()
                         WHERE id = ? AND lease_owner = ? AND lease_epoch = ?
                        """,
                        (double) ttl.toMillis() / 1000,
                        lease.taskId(),
                        lease.owner(),
                        lease.epoch())
                == 1);
    }

    /**
     * Gives the claim back so the next worker need not wait out the TTL.
     *
     * <p>Also epoch-conditional, so a worker that has already been superseded cannot release a lease
     * its successor now holds — releasing someone else's claim is the same bug as writing over it.
     */
    public boolean release(UUID workspaceId, Lease lease) {
        return tenantScope.runInTenant(workspaceId, () -> jdbc.update(
                        """
                        UPDATE tasks
                           SET lease_owner = NULL,
                               lease_expires_at = NULL,
                               updated_at = now()
                         WHERE id = ? AND lease_owner = ? AND lease_epoch = ?
                        """,
                        lease.taskId(),
                        lease.owner(),
                        lease.epoch())
                == 1);
    }

    /** The claim on a task as the database currently sees it, for tests and for operators. */
    public Optional<Lease> current(UUID workspaceId, UUID taskId) {
        return tenantScope.runInTenant(workspaceId, () -> jdbc
                .query(
                        "SELECT lease_owner, lease_epoch, lease_expires_at FROM tasks WHERE id = ?",
                        (rs, row) -> {
                            Instant expiresAt = rs.getTimestamp("lease_expires_at") == null
                                    ? null
                                    : rs.getTimestamp("lease_expires_at").toInstant();
                            return new Lease(taskId, rs.getString("lease_owner"), rs.getLong("lease_epoch"), expiresAt);
                        },
                        taskId)
                .stream()
                .findFirst());
    }
}

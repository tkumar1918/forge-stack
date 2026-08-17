package dev.tushar.forgestack.task;

import dev.tushar.forgestack.iam.IamQueries;
import dev.tushar.forgestack.platform.jobs.JobEnqueueRequested;
import dev.tushar.forgestack.platform.jobs.JobMessage;
import dev.tushar.forgestack.platform.jobs.LeaderLock;
import dev.tushar.forgestack.platform.tenancy.TenantScope;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Puts back the work that fell on the floor.
 *
 * <p>This is what makes the queue disposable. Every claim to be running a task carries an expiry,
 * and a worker that stops renewing — killed, deployed over, partitioned away, out of memory — stops
 * proving it exists. Nothing has to notice the death; the claim simply lapses, and this sweep takes
 * the task back and asks for it to be queued again.
 *
 * <p>Two things get rescued, because there are two ways work is lost:
 *
 * <ol>
 *   <li><strong>A claim that lapsed.</strong> A worker had the task and stopped. Reclaimed, epoch
 *       bumped so the old worker cannot write again, and returned to {@code QUEUED}.
 *   <li><strong>A task queued long ago that nobody took.</strong> This is what losing Redis looks
 *       like from Postgres: the row still says {@code QUEUED} and the message it refers to no longer
 *       exists anywhere. Re-enqueued after a grace period — long enough that a healthy queue is
 *       never second-guessed, short enough that a flushed one recovers on its own.
 * </ol>
 *
 * <p>Both paths re-enqueue through the outbox rather than writing to Redis, so a crash during the
 * sweep loses nothing either.
 */
@Component
public class LeaseReconciler {

    /** The stream tasks are queued on. Public because everything that runs tasks reads from it. */
    public static final String JOB_KIND = "task";

    private static final Logger log = LoggerFactory.getLogger(LeaseReconciler.class);

    private static final int BATCH = 200;

    private final IamQueries iam;
    private final TenantScope tenantScope;
    private final JdbcTemplate jdbc;
    private final ApplicationEventPublisher events;
    private final LeaderLock leaderLock;
    private final Duration queuedGrace;
    private final Duration leadershipTtl;

    LeaseReconciler(
            IamQueries iam,
            TenantScope tenantScope,
            JdbcTemplate jdbc,
            ApplicationEventPublisher events,
            LeaderLock leaderLock,
            @Value("${forgestack.jobs.queued-grace:PT2M}") Duration queuedGrace,
            @Value("${forgestack.jobs.leadership-ttl:PT2M}") Duration leadershipTtl) {
        this.iam = iam;
        this.tenantScope = tenantScope;
        this.jdbc = jdbc;
        this.events = events;
        this.leaderLock = leaderLock;
        this.queuedGrace = queuedGrace;
        this.leadershipTtl = leadershipTtl;
    }

    /**
     * Sweeps every workspace, if this process is the one doing the sweeping.
     *
     * <p>Workspace by workspace, and not because that is convenient. Row-level security has no
     * "all tenants" mode for this application — the role is deliberately not {@code BYPASSRLS} —
     * so a single cross-tenant scan would return nothing at all. Iteration is the price of an
     * isolation guarantee that holds even when the code asking is wrong.
     *
     * <p>Revisit when a single sweep stops fitting comfortably in its interval. The shape that
     * replaces this is a workspace-agnostic index of outstanding leases, not a wider grant.
     */
    @Scheduled(
            fixedDelayString = "${forgestack.jobs.reconcile-interval:PT30S}",
            initialDelayString = "${forgestack.jobs.reconcile-interval:PT30S}")
    public void sweep() {
        if (!leaderLock.isLeaderFor("scheduler", leadershipTtl)) {
            return;
        }
        int reclaimed = 0;
        int requeued = 0;
        for (UUID workspaceId : iam.activeWorkspaceIds()) {
            Outcome outcome = reconcile(workspaceId);
            reclaimed += outcome.reclaimed();
            requeued += outcome.requeued();
        }
        if (reclaimed > 0 || requeued > 0) {
            log.info("reconciler reclaimed {} lapsed lease(s) and re-queued {} task(s)", reclaimed, requeued);
        }
    }

    /**
     * Sweeps one workspace.
     *
     * <p>Public and returning counts so the recovery guarantees can be asserted directly rather than
     * inferred from a schedule firing.
     */
    public Outcome reconcile(UUID workspaceId) {
        List<UUID> reclaimed = reclaimLapsedLeases(workspaceId);
        List<UUID> stranded = findStrandedQueuedTasks(workspaceId, reclaimed);
        enqueue(workspaceId, reclaimed);
        enqueue(workspaceId, stranded);
        return new Outcome(reclaimed.size(), reclaimed.size() + stranded.size());
    }

    /** What one sweep did. */
    public record Outcome(int reclaimed, int requeued) {}

    // ---------------------------------------------------------------------------------------

    private List<UUID> reclaimLapsedLeases(UUID workspaceId) {
        return tenantScope.runInTenant(workspaceId, () -> {
            List<UUID> reclaimed = jdbc.query(
                    """
                    UPDATE tasks
                       SET state = 'QUEUED',
                           state_entered_at = now(),
                           lease_owner = NULL,
                           lease_epoch = lease_epoch + 1,
                           lease_expires_at = NULL,
                           version = version + 1,
                           updated_at = now()
                     WHERE id IN (
                         SELECT id FROM tasks
                          WHERE state = 'RUNNING'
                            AND lease_expires_at IS NOT NULL
                            AND lease_expires_at <= now()
                          ORDER BY lease_expires_at
                          LIMIT ?
                          -- Another sweep running concurrently takes a different batch rather than
                          -- waiting for this one, which is what keeps a duplicate scheduler harmless.
                          FOR UPDATE SKIP LOCKED)
                    RETURNING id
                    """,
                    (rs, row) -> rs.getObject("id", UUID.class),
                    BATCH);

            // Same transaction as the state change, because the schema's claim is that every state
            // change has exactly one row here. A transition log with holes in it answers nothing,
            // and the holes would be precisely the incidents anyone ever goes looking for.
            for (UUID taskId : reclaimed) {
                jdbc.update(
                        """
                        INSERT INTO task_state_transitions
                            (task_id, workspace_id, from_state, to_state, event, actor_type, reason)
                        VALUES (?, ?, 'RUNNING', 'QUEUED', 'LEASE_EXPIRED', 'SCHEDULER', ?)
                        """,
                        taskId,
                        workspaceId,
                        "the worker holding this task stopped renewing its lease");
            }
            return reclaimed;
        });
    }

    private List<UUID> findStrandedQueuedTasks(UUID workspaceId, List<UUID> alreadyReclaimed) {
        return tenantScope.runInTenant(workspaceId, () -> jdbc
                .query(
                        """
                        SELECT id FROM tasks
                         WHERE state = 'QUEUED'
                           AND state_entered_at <= now() - make_interval(secs => ?)
                           -- Without this the reconciler competes with itself: a task nobody has
                           -- capacity for looks lost on every single sweep, and one task becomes a
                           -- message every thirty seconds forever. One re-queue per grace period is
                           -- enough to recover from a lost message and few enough that queue depth
                           -- still means what an operator reads it to mean.
                           AND (requeued_at IS NULL OR requeued_at <= now() - make_interval(secs => ?))
                         ORDER BY state_entered_at
                         LIMIT ?
                        """,
                        (rs, row) -> rs.getObject("id", UUID.class),
                        (double) queuedGrace.toMillis() / 1000,
                        (double) queuedGrace.toMillis() / 1000,
                        BATCH)
                .stream()
                // The reclaim above just set these to QUEUED with a fresh state_entered_at, so they
                // cannot match the grace window — but the two statements are separate transactions,
                // and relying on that timing would be relying on a clock.
                .filter(id -> !alreadyReclaimed.contains(id))
                .toList());
    }

    private void enqueue(UUID workspaceId, List<UUID> taskIds) {
        if (taskIds.isEmpty()) {
            return;
        }
        tenantScope.runInTenant(workspaceId, () -> {
            for (UUID taskId : taskIds) {
                events.publishEvent(new JobEnqueueRequested(JobMessage.of(JOB_KIND, workspaceId, taskId)));
            }
            // Stamped in the same transaction as the intent, so a crash between the two cannot leave
            // a task marked as re-queued that never was.
            jdbc.batchUpdate(
                    "UPDATE tasks SET requeued_at = now() WHERE id = ?",
                    taskIds.stream().map(id -> new Object[] {id}).toList());
        });
    }
}

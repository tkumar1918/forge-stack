package dev.tushar.forgestack.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.tushar.forgestack.platform.jobs.DeliveredJob;
import dev.tushar.forgestack.platform.jobs.JobQueue;
import dev.tushar.forgestack.platform.tenancy.TenantScope;
import dev.tushar.forgestack.support.AbstractIntegrationTest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.config.ScheduledTaskHolder;

/**
 * The exit criteria for step 2.1: a job survives {@code kill -9}, and {@code FLUSHALL} loses no work.
 *
 * <p>Both are the same claim from two directions — Postgres is the only source of truth, and Redis
 * is a transport that may be destroyed at any moment without costing anything but latency. That is
 * an easy property to believe and a hard one to keep, because every convenient shortcut (read the
 * queue to find out what is running, trust the pending-entries list to survive) quietly makes it
 * false. Asserting it here is what stops it decaying later.
 */
class CrashRecoveryTest extends AbstractIntegrationTest {

    private static final Duration TTL = Duration.ofSeconds(60);

    @Autowired
    private LeaseReconciler reconciler;

    @Autowired
    private TaskLeases leases;

    @Autowired
    private JobQueue queue;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private LeaseScope leaseScope;

    @Autowired
    private TenantScope tenantScope;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ScheduledTaskHolder scheduledTasks;

    private TaskRows rows;
    private UUID workspaceId;

    /** Every task id seen on the stream during one test, in the order it arrived. */
    private final List<UUID> drained = new ArrayList<>();

    @BeforeEach
    void aWorkspace() {
        this.rows = new TaskRows(tenantScope, leases, leaseScope, jdbc);
        this.workspaceId = rows.newWorkspace();
        drained.clear();
    }

    /**
     * A worker is killed mid-task. Nothing gets to notice: the process is simply gone.
     *
     * <p>What rescues the task is that it stopped renewing. There is no death signal, no shutdown
     * hook, no cleanup — recovery has to work when the worker had no chance to participate in it,
     * because that is the case that actually happens.
     */
    @Test
    @DisplayName("a task whose worker was killed is reclaimed and queued again")
    void aKilledWorkerLosesItsTask() {
        UUID taskId = rows.newTask(workspaceId, "RUNNING");
        Lease lease = leases.acquire(workspaceId, taskId, "worker-1", TTL).orElseThrow();
        rows.expireLease(workspaceId, taskId);

        LeaseReconciler.Outcome outcome = reconciler.reconcile(workspaceId);

        assertThat(outcome.reclaimed()).isEqualTo(1);
        assertThat(rows.stateOf(workspaceId, taskId)).isEqualTo("QUEUED");
        assertThat(leases.current(workspaceId, taskId).orElseThrow().owner())
                .as("a reclaimed task must belong to nobody")
                .isNull();
        assertThat(rows.transitionsFor(workspaceId, taskId, "LEASE_EXPIRED"))
                .as("every state change writes exactly one transition row, including this one")
                .isEqualTo(1);
        assertThat(awaitQueued(taskId)).isTrue();
        assertThat(lease.epoch()).isLessThan(leases.current(workspaceId, taskId)
                .orElseThrow()
                .epoch());
    }

    /**
     * The stalled worker that wakes up — the reason a lease alone is not enough.
     *
     * <p>It is not dead, only late: a long garbage collection, a paused container, a partition that
     * healed. It still believes it holds the task, and by now somebody else does. Every write it
     * makes carries an epoch that has moved on, so every write does nothing.
     */
    @Test
    @DisplayName("a superseded worker cannot write again, even though it thinks it holds the task")
    void aZombieIsFencedOut() {
        UUID taskId = rows.newTask(workspaceId, "RUNNING");
        Lease stale = leases.acquire(workspaceId, taskId, "worker-1", TTL).orElseThrow();
        rows.expireLease(workspaceId, taskId);
        reconciler.reconcile(workspaceId);

        assertThat(leases.renew(stale, TTL))
                .as("renewing must fail: the correct response is to stop, not to try harder")
                .isFalse();
        assertThat(leases.release(stale))
                .as("nor may it release a claim that now belongs to someone else")
                .isFalse();

        Lease fresh = leases.acquire(workspaceId, taskId, "worker-2", TTL).orElseThrow();
        assertThat(fresh.epoch()).isGreaterThan(stale.epoch());
        assertThat(leases.renew(stale, TTL))
                .as("and still fails once the successor is actually running")
                .isFalse();
    }

    /**
     * The case that makes the epoch necessary rather than merely tidy.
     *
     * <p>Worker identities are reusable — a restarted pod comes back under the same name, and a
     * worker id derived from a host is the same id tomorrow. So "is this still my lease?" cannot be
     * answered by the owner column: the killed worker and the one now holding the task are the same
     * string. Only a number that never goes backwards can tell the two apart.
     *
     * <p>Written after the weaker version of this test passed with the epoch check removed, because
     * clearing the owner on reclaim happened to cover for it.
     */
    @Test
    @DisplayName("a restarted worker with the same name does not inherit its predecessor's claim")
    void aReusedWorkerNameDoesNotInheritTheClaim() {
        UUID taskId = rows.newTask(workspaceId, "RUNNING");
        Lease beforeTheCrash =
                leases.acquire(workspaceId, taskId, "worker-1", TTL).orElseThrow();
        rows.expireLease(workspaceId, taskId);
        reconciler.reconcile(workspaceId);

        // The same worker comes back — same name, new process, new claim.
        Lease afterTheRestart =
                leases.acquire(workspaceId, taskId, "worker-1", TTL).orElseThrow();
        assertThat(afterTheRestart.epoch()).isGreaterThan(beforeTheCrash.epoch());

        assertThat(leases.renew(beforeTheCrash, TTL))
                .as("the owner column matches and the claim is still not the old one's")
                .isFalse();
        assertThat(leases.release(beforeTheCrash))
                .as("nor may the old process release the new one's claim on its way out")
                .isFalse();
        assertThat(leases.renew(afterTheRestart, TTL))
                .as("while the current holder is unaffected")
                .isTrue();
    }

    /**
     * The exit criterion, stated as the plan states it: {@code FLUSHALL} on Redis loses no work.
     *
     * <p>Every queue, every consumer group, every pending entry — gone, with no warning and no
     * chance to drain. The only thing left is the row saying this task is queued, and that has to be
     * enough.
     */
    @Test
    @DisplayName("flushing Redis loses no work")
    void flushingRedisLosesNoWork() {
        UUID taskId = rows.newTask(workspaceId, "QUEUED");
        rows.backdateStateEntry(workspaceId, taskId, Duration.ofMinutes(10));

        reconciler.reconcile(workspaceId);
        assertThat(awaitQueued(taskId)).as("queued in the first place").isTrue();
        assertThat(redis.keys("forge:*")).as("and Redis is holding it").isNotEmpty();

        flushRedis();

        assertThat(redis.keys("forge:*"))
                .as("every queue, group and pending entry is gone, with no chance to drain")
                .isEmpty();

        // Recovery is not instant, and should not be: nothing distinguishes a flushed queue from a
        // busy one, so the reconciler waits out a grace period either way. Live, that was 110
        // seconds. Backdated here rather than waited out.
        rows.backdateRequeue(workspaceId, taskId, Duration.ofMinutes(10));
        reconciler.reconcile(workspaceId);

        assertThat(awaitQueued(taskId))
                .as("the row in Postgres was the whole of what was needed to rebuild the queue")
                .isTrue();
    }

    /**
     * The reconciler must not compete with itself.
     *
     * <p>A task nobody has capacity for looks lost on every sweep, so without a memory of having
     * just re-queued it, one task becomes a message every thirty seconds indefinitely. Duplicates
     * are safe — consumers are idempotent — but queue depth is the number an operator reads to
     * answer "are we behind", and a queue full of copies of one task answers it wrongly, in the
     * alarming direction.
     */
    @Test
    @DisplayName("a task is not re-queued again on the very next sweep")
    void reQueueingBacksOff() {
        UUID taskId = rows.newTask(workspaceId, "QUEUED");
        rows.backdateStateEntry(workspaceId, taskId, Duration.ofMinutes(10));

        assertThat(reconciler.reconcile(workspaceId).requeued()).isEqualTo(1);
        assertThat(reconciler.reconcile(workspaceId).requeued())
                .as("still queued, still stale, and deliberately left alone")
                .isZero();

        rows.backdateRequeue(workspaceId, taskId, Duration.ofMinutes(10));

        assertThat(reconciler.reconcile(workspaceId).requeued())
                .as("but a task still sitting there a grace period later is presumed lost again")
                .isEqualTo(1);
    }

    /**
     * A task that was queued and never picked up.
     *
     * <p>This is what a lost message looks like from the database's side, and it is deliberately not
     * treated as an error — there is no way to distinguish "the message was lost" from "no worker
     * has got to it yet" except by waiting. The grace period is that wait, which is why re-queueing
     * has to be harmless and consumers have to be idempotent.
     */
    @Test
    @DisplayName("a task queued long ago with nobody working on it is queued again")
    void strandedQueuedWorkIsRecovered() {
        UUID recent = rows.newTask(workspaceId, "QUEUED");
        UUID stranded = rows.newTask(workspaceId, "QUEUED");
        rows.backdateStateEntry(workspaceId, stranded, Duration.ofMinutes(10));

        LeaseReconciler.Outcome outcome = reconciler.reconcile(workspaceId);

        assertThat(outcome.requeued()).isEqualTo(1);
        assertThat(awaitQueued(stranded)).isTrue();
        assertThat(drained)
                .as("a task queued moments ago is not presumed lost; that would re-queue everything, always")
                .doesNotContain(recent);
    }

    /**
     * That the sweep is wired to a timer at all.
     *
     * <p>Every other test here calls the reconciler directly, which is the only way to assert what
     * one sweep did — and would keep passing if nothing ever scheduled it. Recovery that runs only
     * when a test asks for it is not recovery.
     */
    @Test
    @DisplayName("the reconciler is actually scheduled")
    void theSweepIsScheduled() {
        assertThat(scheduledTasks.getScheduledTasks())
                .anySatisfy(task -> assertThat(task.getTask().toString()).contains("LeaseReconciler.sweep"));
    }

    // ---------------------------------------------------------------------------------------

    /**
     * Waits for the relay to carry the task onto the stream, and reports whether it arrived.
     *
     * <p>Everything drained on the way is kept, so a test can assert on what was <em>not</em> queued
     * as well as what was — the reconciler re-queueing too much is as much a bug as too little, and
     * it is the one a passing test would otherwise hide.
     */
    private boolean awaitQueued(UUID taskId) {
        try {
            await().atMost(Duration.ofSeconds(10)).until(() -> {
                drained.addAll(drainStream());
                return drained.contains(taskId);
            });
            return true;
        } catch (ConditionTimeoutException e) {
            return false;
        }
    }

    /**
     * Takes whatever is on the task stream and reports which tasks it referred to.
     *
     * <p>Acknowledges everything it reads. This stream outlives each test, so leaving entries behind
     * would make later assertions depend on what happened to run before them.
     */
    private List<UUID> drainStream() {
        List<DeliveredJob> delivered = queue.poll(LeaseReconciler.JOB_KIND, "test-consumer", 100, Duration.ZERO);
        delivered.forEach(queue::acknowledge);
        return delivered.stream().map(job -> job.job().resourceId()).toList();
    }

    private void flushRedis() {
        redis.execute((RedisCallback<Void>) connection -> {
            connection.serverCommands().flushAll();
            return null;
        });
    }
}

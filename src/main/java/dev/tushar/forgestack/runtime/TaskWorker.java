package dev.tushar.forgestack.runtime;

import dev.tushar.forgestack.platform.jobs.DeliveredJob;
import dev.tushar.forgestack.platform.jobs.JobQueue;
import dev.tushar.forgestack.platform.jobs.LeaderLock;
import dev.tushar.forgestack.task.Actor;
import dev.tushar.forgestack.task.Lease;
import dev.tushar.forgestack.task.LeaseReconciler;
import dev.tushar.forgestack.task.SimulatedOutcome;
import dev.tushar.forgestack.task.TaskAttempts;
import dev.tushar.forgestack.task.TaskEvent;
import dev.tushar.forgestack.task.TaskLeases;
import dev.tushar.forgestack.task.TaskService;
import dev.tushar.forgestack.task.TaskState;
import dev.tushar.forgestack.task.TaskStateService;
import dev.tushar.forgestack.task.TaskView;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Takes work off the queue and runs it.
 *
 * <p>The attempt loop, in the shape it will keep: claim the task, open an attempt, walk its phases,
 * end it, and tell the state machine what happened. In Phase 2 the phases are simulated, and nothing
 * else here is.
 *
 * <p><strong>Everything is idempotent, because delivery is at-least-once.</strong> A duplicate
 * message finds the task already claimed, or no longer {@code QUEUED}, and does nothing. Neither is
 * an error — they are the ordinary shape of a queue that would rather deliver twice than lose one.
 */
@Component
public class TaskWorker {

    private static final Logger log = LoggerFactory.getLogger(TaskWorker.class);

    private final JobQueue queue;
    private final TaskLeases leases;
    private final TaskService tasks;
    private final TaskStateService taskStates;
    private final TaskAttempts attempts;
    private final FakePhaseHandler handler;
    private final String workerId;
    private final Duration leaseTtl;
    private final int batch;

    /**
     * Set once, on the way down, and never unset.
     *
     * <p>The whole of graceful drain: stop claiming new work, finish the attempt in hand, hand the
     * task back, and exit. A long task survives a deploy because a task is not a process (§20).
     */
    private final AtomicBoolean draining = new AtomicBoolean(false);

    TaskWorker(
            JobQueue queue,
            TaskLeases leases,
            TaskService tasks,
            TaskStateService taskStates,
            TaskAttempts attempts,
            FakePhaseHandler handler,
            LeaderLock processIdentity,
            @Value("${forgestack.runtime.lease-ttl:PT60S}") Duration leaseTtl,
            @Value("${forgestack.runtime.batch:8}") int batch) {
        this.queue = queue;
        this.leases = leases;
        this.tasks = tasks;
        this.taskStates = taskStates;
        this.attempts = attempts;
        this.handler = handler;
        // The process identity the leader lock already mints: random per process, so a restart never
        // inherits its predecessor's claims. Reusing it keeps one answer to "who is this worker".
        this.workerId = "worker-" + processIdentity.processId();
        this.leaseTtl = leaseTtl;
        this.batch = batch;
    }

    @Scheduled(
            fixedDelayString = "${forgestack.runtime.poll-interval:PT1S}",
            initialDelayString = "${forgestack.runtime.poll-interval:PT1S}")
    void poll() {
        try {
            runAvailableWork();
        } catch (RuntimeException e) {
            // A worker that dies on one bad message stops running every other task in the system.
            // The lease lapses, the reconciler requeues, and the next poll carries on.
            log.error("worker cycle failed", e);
        }
    }

    /**
     * Runs whatever is on the queue right now, and reports how many tasks it took on.
     *
     * <p>Public and synchronous so the lifecycle can be asserted directly. A test that had to wait
     * for a timer would be a test about the timer.
     */
    public int runAvailableWork() {
        // Checked here rather than on the timer, because "stop claiming new work" is a property of
        // claiming and not of what happened to call it. With the check on the schedule instead, a
        // draining process still took on work through any other entry point — which is the whole
        // failure drain exists to prevent, arriving through a different door.
        if (draining.get()) {
            return 0;
        }
        List<DeliveredJob> delivered = queue.poll(LeaseReconciler.JOB_KIND, workerId, batch, Duration.ZERO);
        int started = 0;
        for (DeliveredJob job : delivered) {
            try {
                if (runOne(job.job().workspaceId(), job.job().resourceId())) {
                    started++;
                }
            } finally {
                // Acknowledged either way. The message has been dealt with even when the answer was
                // "somebody else has this"; what protects the work is the lease and the reconciler,
                // never an unacknowledged message sitting in a pending list.
                queue.acknowledge(job);
            }
        }
        return started;
    }

    /** Stops claiming new work. Called on shutdown, and directly by tests. */
    @EventListener(ContextClosedEvent.class)
    public void beginDraining() {
        if (draining.compareAndSet(false, true)) {
            log.info("draining: no new tasks will be claimed");
        }
    }

    // ---------------------------------------------------------------------------------------

    private boolean runOne(UUID workspaceId, UUID taskId) {
        Optional<Lease> claim = leases.acquire(workspaceId, taskId, workerId, leaseTtl);
        if (claim.isEmpty()) {
            return false;
        }
        Lease lease = claim.get();
        try {
            Optional<TaskView> task = tasks.find(workspaceId, taskId);
            if (task.isEmpty() || task.get().state() != TaskState.QUEUED) {
                // A duplicate delivery, or a task somebody cancelled while it sat on the queue.
                // Both are ordinary; neither is worth a log line at anything above debug.
                log.debug("skipping {}: no longer queued", taskId);
                return false;
            }
            taskStates.apply(lease, TaskEvent.CLAIM, Actor.system(), "a worker picked it up");
            runAttempts(lease, task.get());
            return true;
        } finally {
            // Releasing rather than letting it lapse is what makes a handover cost nothing. If we
            // have already been fenced this does nothing, which is the correct outcome.
            leases.release(lease);
        }
    }

    /**
     * Attempts, one after another, until the task leaves the worker's hands.
     *
     * <p>Retrying inside the claim rather than going back through the queue, because a retry is a new
     * approach and not a new lifecycle — the plan's {@code ATTEMPT_FAILED} self-loop says the same.
     * The task only goes back on the queue when this worker is done with it, or stops existing.
     */
    private void runAttempts(Lease lease, TaskView task) {
        SimulatedOutcome simulation =
                task.simulatedOutcome() == null ? SimulatedOutcome.SUCCEED : task.simulatedOutcome();

        while (true) {
            if (draining.get()) {
                taskStates.apply(lease, TaskEvent.YIELD, Actor.system(), "the worker is shutting down");
                return;
            }
            if (!leases.renew(lease, leaseTtl)) {
                // Somebody else holds this task now. Stopping is the only correct response —
                // continuing would mean two workers doing the same work with the same side effects.
                log.warn("lost the claim on {} mid-task; stopping", lease.taskId());
                return;
            }

            TaskAttempts.OpenedAttempt attempt = attempts.open(lease);
            FakePhaseHandler.AttemptResult result =
                    handler.run(lease, attempt.id(), simulation, attempt.attemptNo());
            attempts.end(lease, attempt.id(), result.outcome(), result.failureClass(), result.summary());

            switch (result.outcome()) {
                case "SUCCEEDED" -> {
                    taskStates.apply(lease, TaskEvent.COMPLETE, Actor.system(), "the attempt succeeded");
                    return;
                }
                case "ESCALATED" -> {
                    taskStates.apply(lease, TaskEvent.ESCALATE_HUMAN, Actor.system(), result.summary());
                    return;
                }
                default -> {
                    if (attempt.attemptNo() >= task.maxAttempts()) {
                        taskStates.apply(lease, TaskEvent.ABANDON, Actor.system(), "the attempt cap was reached");
                        return;
                    }
                    taskStates.apply(lease, TaskEvent.ATTEMPT_FAILED, Actor.system(), result.summary());
                }
            }
        }
    }
}

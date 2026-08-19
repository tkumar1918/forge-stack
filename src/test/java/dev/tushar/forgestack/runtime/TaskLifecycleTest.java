package dev.tushar.forgestack.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.tushar.forgestack.harness.InMemoryHarness;
import dev.tushar.forgestack.platform.jobs.DeliveredJob;
import dev.tushar.forgestack.platform.jobs.JobQueue;
import dev.tushar.forgestack.platform.jobs.LeaderLock;
import dev.tushar.forgestack.platform.tenancy.TenantScope;
import dev.tushar.forgestack.support.AbstractIntegrationTest;
import dev.tushar.forgestack.task.Actor;
import dev.tushar.forgestack.task.NewTask;
import dev.tushar.forgestack.task.SimulatedOutcome;
import dev.tushar.forgestack.task.TaskEvent;
import dev.tushar.forgestack.task.TaskService;
import dev.tushar.forgestack.task.TaskState;
import dev.tushar.forgestack.task.TaskStateService;
import dev.tushar.forgestack.task.LeaseReconciler;
import dev.tushar.forgestack.task.TaskAttempts;
import dev.tushar.forgestack.task.TaskLeases;
import dev.tushar.forgestack.task.TaskView;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The exit criterion for step 2.4: a task runs end to end through the FSM with no model and no
 * sandbox.
 *
 * <p>Everything below is the real machinery — the outbox, the Redis stream, the lease and its fence,
 * the transition table, the guards, the attempt and step rows. The only thing simulated is what an
 * attempt concluded, which is exactly the thing Phase 4 replaces. Proving the substrate here is what
 * makes a misbehaving agent later a question about the prompt rather than about six other things.
 */
class TaskLifecycleTest extends AbstractIntegrationTest {

    @Autowired
    private TaskWorker worker;

    @Autowired
    private TaskService tasks;

    @Autowired
    private TaskStateService taskStates;

    @Autowired
    private TenantScope tenantScope;

    @Autowired
    private JobQueue queue;

    @Autowired
    private TaskLeases leases;

    @Autowired
    private TaskAttempts attempts;

    @Autowired
    private AttemptRunner runner;

    @Autowired
    private InMemoryHarness harness;

    @Autowired
    private LeaderLock processIdentity;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID workspaceId;
    private UUID userId;

    @BeforeEach
    void aWorkspace() {
        // The task stream outlives every test in this JVM, and this class asserts on how many tasks
        // a poll picked up. Anything another class left queued would be counted here.
        drainQueue();

        this.workspaceId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO workspaces (id, slug, name) VALUES (?, ?, ?)",
                workspaceId,
                "ws-" + workspaceId.toString().substring(0, 8),
                "Lifecycle test");
        this.userId = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, primary_email) VALUES (?, ?)", userId, userId + "@example.test");
    }

    @Test
    @DisplayName("a task is created, queued, claimed, attempted and completed")
    void theHappyPathRunsEndToEnd() {
        TaskView created = create("Make CI green", SimulatedOutcome.SUCCEED);

        assertThat(created.state())
                .as("creation admits and queues; the work has not happened yet")
                .isEqualTo(TaskState.QUEUED);

        workUntilItLeavesTheQueue(created.id());

        TaskView.Detail detail = detail(created.id());
        assertThat(detail.task().state()).isEqualTo(TaskState.COMPLETED);
        assertThat(detail.task().attemptCount()).isEqualTo(1);

        assertThat(detail.attempts()).singleElement().satisfies(attempt -> {
            assertThat(attempt.outcome()).isEqualTo("SUCCEEDED");
            assertThat(attempt.endedAt()).isNotNull();
        });

        assertThat(states(detail))
                .as("every step of the lifecycle is a row, in order, and none of it is inferred")
                .containsExactly(
                        TaskState.READY, TaskState.QUEUED, TaskState.RUNNING, TaskState.COMPLETED);

        assertThat(steps(created.id()))
                .as("provisioned, one tool call, verified, submitted — the work reported, not a script")
                // Four and not five, and the difference is the point. Steps used to come from a
                // hardcoded list of phases, so the log said the same thing however the attempt had
                // actually gone. They now come from the harness's own event stream — one row per
                // completed tool call — plus the phases ForgeStack runs itself. An agent that used
                // three tools writes three rows here.
                .isEqualTo(4);
    }

    /**
     * Failing, retrying, and eventually giving up.
     *
     * <p>{@code ABANDONED} rather than {@code FAILED}, and the difference is the point: we ran out of
     * attempts, which means a person should look, not that the task was impossible.
     */
    @Test
    @DisplayName("a task that keeps failing retries to its cap and is abandoned")
    void failureRetriesThenAbandons() {
        TaskView created = create("Fix the flaky test", SimulatedOutcome.FAIL, 3);

        workUntilItLeavesTheQueue(created.id());

        TaskView.Detail detail = detail(created.id());
        assertThat(detail.task().state()).isEqualTo(TaskState.ABANDONED);
        assertThat(detail.task().attemptCount()).isEqualTo(3);
        assertThat(detail.attempts()).hasSize(3).allSatisfy(attempt -> assertThat(attempt.outcome())
                .isEqualTo("FAILED"));

        assertThat(events(detail))
                .as("two retries inside one claim, then the cap")
                .containsSequence(TaskEvent.ATTEMPT_FAILED, TaskEvent.ATTEMPT_FAILED, TaskEvent.ABANDON);
    }

    @Test
    @DisplayName("a task that fails once succeeds on the retry")
    void aRetrySucceeds() {
        TaskView created = create("Bump a dependency", SimulatedOutcome.FAIL_ONCE);

        workUntilItLeavesTheQueue(created.id());

        TaskView.Detail detail = detail(created.id());
        assertThat(detail.task().state()).isEqualTo(TaskState.COMPLETED);
        assertThat(detail.attempts()).hasSize(2);
        assertThat(detail.attempts().get(0).outcome()).isEqualTo("FAILED");
        assertThat(detail.attempts().get(1).outcome()).isEqualTo("SUCCEEDED");
    }

    /**
     * The escalation round trip, which is the path the plan's FSM could not previously complete.
     *
     * <p>Resuming lands the task back on the <em>queue</em>, not in {@code RUNNING}. A person holds no
     * lease and puts nothing on a stream, so a task resumed into {@code RUNNING} would be invisible to
     * both halves of the reconciler and would never move again. That the second
     * {@code runAvailableWork()} finds work at all is the assertion.
     */
    @Test
    @DisplayName("an escalated task waits for a person, and resuming puts it back to work")
    void escalationWaitsThenResumes() {
        TaskView created = create("Delete a deprecated endpoint", SimulatedOutcome.ESCALATE);

        workUntilItLeavesTheQueue(created.id());
        assertThat(detail(created.id()).task().state()).isEqualTo(TaskState.AWAITING_HUMAN);
        assertThat(worker.runAvailableWork())
                .as("nothing may pick it up while it is waiting on a person")
                .isZero();

        // The person says carry on, and changes their mind about how it should end.
        simulate(created.id(), SimulatedOutcome.SUCCEED);
        taskStates.apply(workspaceId, created.id(), TaskEvent.RESUME, Actor.human(userId), "go ahead");

        assertThat(detail(created.id()).task().state()).isEqualTo(TaskState.QUEUED);
        workUntilItLeavesTheQueue(created.id());
        assertThat(detail(created.id()).task().state()).isEqualTo(TaskState.COMPLETED);
    }

    @Test
    @DisplayName("a person refusing ends the task rather than resuming it")
    void rejectionEndsIt() {
        TaskView created = create("Rewrite the auth layer", SimulatedOutcome.ESCALATE);
        workUntilItLeavesTheQueue(created.id());

        taskStates.apply(workspaceId, created.id(), TaskEvent.REJECT, Actor.human(userId), "not worth doing");

        assertThat(detail(created.id()).task().state()).isEqualTo(TaskState.CANCELLED);
        assertThat(detail(created.id()).task().terminalReason()).isEqualTo("not worth doing");
    }

    /**
     * A retried create returns the original task.
     *
     * <p>A client that times out and retries has no way to know whether the first call landed, and
     * two tasks on one goal means two branches, two pull requests, and two agents disagreeing.
     */
    @Test
    @DisplayName("the same idempotency key returns the same task")
    void createIsIdempotent() {
        NewTask request = new NewTask(
                "Only once", "Prove idempotency", null, null, null, "key-" + UUID.randomUUID(),
                SimulatedOutcome.SUCCEED);

        TaskView first = tasks.create(workspaceId, userId, request);
        TaskView second = tasks.create(workspaceId, userId, request);

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(tasks.list(workspaceId)).hasSize(1);
    }

    /**
     * A sandbox that could not be provisioned is not a failed attempt.
     *
     * <p>The distinction §20 insists on: infrastructure dying is not the approach being wrong. A
     * worker that treated "there is nowhere to run this" as a failure would spend a task's whole
     * retry budget discovering that the cluster was busy, and the transition log would blame the
     * agent for it. So the task goes back on the queue for somebody else, and the reason is recorded.
     *
     * <p>Reached here by telling the harness it has no room, which is the kind of thing only a
     * simulated one will do on request — and the reason the {@code induce} hooks exist.
     */
    @Test
    @DisplayName("a task whose sandbox cannot be provisioned goes back on the queue")
    void noCapacityHandsTheTaskBack() {
        TaskView created = create("Needs a sandbox", SimulatedOutcome.SUCCEED);
        awaitRelay();
        harness.reportNoCapacity(true);
        try {
            worker.runAvailableWork();

            assertThat(detail(created.id()).task().state())
                    .as("handed back, not failed")
                    .isEqualTo(TaskState.QUEUED);
            assertThat(detail(created.id()).attempts())
                    .singleElement()
                    .satisfies(attempt -> assertThat(attempt.outcome()).isEqualTo("ABORTED"));
        } finally {
            harness.reportNoCapacity(false);
        }

        // And it still runs to completion once there is room, on the same task rather than a new one.
        workUntilItLeavesTheQueue(created.id());
        assertThat(detail(created.id()).task().state()).isEqualTo(TaskState.COMPLETED);
    }

    /**
     * Draining hands the work back instead of holding it through a shutdown.
     *
     * <p>{@code YIELD} rather than letting the lease lapse: the task returns to the queue at once
     * instead of costing a lease TTL of dead time, and the transition log says the worker left on
     * purpose rather than looking like a crash.
     */
    @Test
    @DisplayName("a draining worker gives its task back")
    void drainingYieldsTheTask() {
        TaskView created = create("Long job", SimulatedOutcome.FAIL);

        // A second worker, because draining is a one-way latch and should be: a process that has
        // begun shutting down never starts claiming again. Draining the shared bean would leave
        // every later test in this context running against a worker that refuses work.
        TaskWorker leaving = new TaskWorker(
                queue, leases, tasks, taskStates, attempts, runner, processIdentity, Duration.ofSeconds(60), 8);
        leaving.beginDraining();
        awaitRelay();

        assertThat(leaving.runAvailableWork())
                .as("a draining worker claims nothing new")
                .isZero();
        assertThat(detail(created.id()).task().state()).isEqualTo(TaskState.QUEUED);

        workUntilItLeavesTheQueue(created.id());
        assertThat(detail(created.id()).task().state())
                .as("and the work was still there for a worker that is not shutting down")
                .isEqualTo(TaskState.ABANDONED);
    }

    // ---------------------------------------------------------------------------------------

    private TaskView create(String title, SimulatedOutcome simulation) {
        return create(title, simulation, null);
    }

    private TaskView create(String title, SimulatedOutcome simulation, Integer maxAttempts) {
        return tasks.create(
                workspaceId, userId, new NewTask(title, "goal: " + title, null, null, maxAttempts, null, simulation));
    }

    private TaskView.Detail detail(UUID taskId) {
        return tasks.detail(workspaceId, taskId).orElseThrow();
    }

    private List<TaskState> states(TaskView.Detail detail) {
        return detail.transitions().stream().map(TaskView.TransitionView::toState).toList();
    }

    private List<TaskEvent> events(TaskView.Detail detail) {
        return detail.transitions().stream().map(TaskView.TransitionView::event).toList();
    }

    /**
     * Works the queue until this task is no longer on it.
     *
     * <p>Looped rather than called once, because the relay is asynchronous by design: the enqueue
     * intent commits with the state change and reaches Redis a moment later. A test that called the
     * worker immediately would be asserting on that gap rather than on the lifecycle.
     */
    private void workUntilItLeavesTheQueue(UUID taskId) {
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            worker.runAvailableWork();
            assertThat(detail(taskId).task().state()).isNotEqualTo(TaskState.QUEUED);
        });
    }

    /** Waits for the relay to put something on the stream, so "nothing was claimed" means something. */
    private void awaitRelay() {
        await().atMost(Duration.ofSeconds(15)).until(() -> queue.depth(LeaseReconciler.JOB_KIND) > 0);
    }

    private void drainQueue() {
        List<DeliveredJob> delivered;
        do {
            delivered = queue.poll(LeaseReconciler.JOB_KIND, "lifecycle-drain", 100, Duration.ZERO);
            delivered.forEach(queue::acknowledge);
        } while (!delivered.isEmpty());
    }

    private int steps(UUID taskId) {
        Integer count = tenantScope.runInTenant(
                workspaceId,
                () -> jdbc.queryForObject("SELECT count(*) FROM task_steps WHERE task_id = ?", Integer.class, taskId));
        return count == null ? 0 : count;
    }

    /** Changes what the fake will decide next — the human in the escalation loop changing their mind. */
    private void simulate(UUID taskId, SimulatedOutcome outcome) {
        tenantScope.runInTenant(
                workspaceId,
                () -> jdbc.update(
                        "UPDATE tasks SET simulated_outcome = ? WHERE id = ?", outcome.name(), taskId));
    }
}

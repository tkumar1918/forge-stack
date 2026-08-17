package dev.tushar.forgestack.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import dev.tushar.forgestack.platform.jobs.DeliveredJob;
import dev.tushar.forgestack.platform.jobs.JobQueue;
import dev.tushar.forgestack.platform.tenancy.TenantScope;
import dev.tushar.forgestack.support.AbstractIntegrationTest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** The one component that changes a task's state, and what it refuses to do. */
class TaskStateServiceTest extends AbstractIntegrationTest {

    @Autowired
    private TaskStateService taskStates;

    @Autowired
    private TaskLeases leases;

    @Autowired
    private LeaseScope leaseScope;

    @Autowired
    private TenantScope tenantScope;

    @Autowired
    private JobQueue queue;

    @Autowired
    private JdbcTemplate jdbc;

    private TaskRows rows;
    private UUID workspaceId;
    private UUID taskId;

    @BeforeEach
    void aNewTask() {
        this.rows = new TaskRows(tenantScope, leases, leaseScope, jdbc);
        this.workspaceId = rows.newWorkspace();
        this.taskId = rows.newTask(workspaceId, "CREATED");
    }

    @Test
    @DisplayName("an event the current state has no answer to throws")
    void anIllegalEventThrows() {
        assertThatThrownBy(() -> apply(TaskEvent.COMPLETE))
                .isInstanceOf(IllegalTransitionException.class)
                .hasMessageContaining("CREATED")
                .hasMessageContaining("COMPLETE");

        assertThat(rows.stateOf(workspaceId, taskId))
                .as("a refused event must change nothing, or the throw is decoration")
                .isEqualTo("CREATED");
    }

    @Test
    @DisplayName("a legal event moves the task and writes exactly one transition row")
    void aLegalEventIsRecorded() {
        assertThat(apply(TaskEvent.ADMIT)).isEqualTo(TaskState.READY);

        List<Map<String, Object>> transitions = tenantScope.runInTenant(workspaceId, () -> jdbc.queryForList(
                "SELECT from_state, to_state, event, actor_type FROM task_state_transitions WHERE task_id = ?",
                taskId));

        assertThat(transitions).hasSize(1);
        assertThat(transitions.getFirst())
                .containsEntry("from_state", "CREATED")
                .containsEntry("to_state", "READY")
                .containsEntry("event", "ADMIT")
                .containsEntry("actor_type", "SYSTEM");
    }

    /**
     * Queueing follows from entering the state, rather than being a second thing to remember.
     *
     * <p>Published as an outbox intent inside the same transaction as the state change, so the two
     * cannot disagree: a rollback un-queues the job, and a crash before the relay runs leaves the
     * intent to be delivered on the next start.
     */
    @Test
    @DisplayName("entering QUEUED puts the task on the queue")
    void queueingIsAConsequenceOfTheState() {
        apply(TaskEvent.ADMIT);
        apply(TaskEvent.ENQUEUE);

        await().atMost(Duration.ofSeconds(10)).until(() -> drainStream().contains(taskId));
    }

    /**
     * The fence and the state machine, meeting.
     *
     * <p>A worker is running this task. Transitioning it from outside — an operator, a sweep, a
     * feature written six months from now — would be a write racing a live worker, and the database
     * refuses it rather than letting the two interleave. Choosing the wrong entry point is loud.
     */
    @Test
    @DisplayName("a task somebody is running cannot be transitioned from outside its lease")
    void aRunningTaskIsProtectedFromOutside() {
        apply(TaskEvent.ADMIT);
        apply(TaskEvent.ENQUEUE);
        Lease lease = leases.acquire(workspaceId, taskId, "worker-1", Duration.ofMinutes(5))
                .orElseThrow();
        taskStates.apply(lease, TaskEvent.CLAIM, Actor.system(), "starting an attempt");

        assertThatThrownBy(() -> apply(TaskEvent.SUBMIT)).hasStackTraceContaining("carried no lease");

        assertThatCode(() -> taskStates.apply(lease, TaskEvent.SUBMIT, Actor.system(), "pull request opened"))
                .as("the worker holding it is unaffected")
                .doesNotThrowAnyException();
        assertThat(rows.stateOf(workspaceId, taskId)).isEqualTo("AWAITING_EXTERNAL");
    }

    @Test
    @DisplayName("a terminal state records why, and a live one does not")
    void onlyTerminalStatesCarryAReason() {
        apply(TaskEvent.ADMIT);

        assertThat(terminalReason())
                .as("READY is not an ending and must not claim one")
                .isNull();

        taskStates.apply(workspaceId, taskId, TaskEvent.CANCEL, Actor.human(UUID.randomUUID()), "changed my mind");

        assertThat(rows.stateOf(workspaceId, taskId)).isEqualTo("CANCELLED");
        assertThat(terminalReason()).isEqualTo("changed my mind");
    }

    @Test
    @DisplayName("a task in another workspace does not exist")
    void tasksAreTenantScoped() {
        UUID stranger = rows.newWorkspace();

        assertThatThrownBy(() -> taskStates.apply(stranger, taskId, TaskEvent.ADMIT, Actor.system(), "admitted"))
                .isInstanceOf(UnknownTaskException.class)
                .hasMessageContaining("another tenant");
    }

    // ---------------------------------------------------------------------------------------

    private TaskState apply(TaskEvent event) {
        return taskStates.apply(workspaceId, taskId, event, Actor.system(), "because the test said so");
    }

    private String terminalReason() {
        return tenantScope.runInTenant(workspaceId, () -> jdbc.queryForObject(
                "SELECT terminal_reason FROM tasks WHERE id = ?", String.class, taskId));
    }

    private List<UUID> drainStream() {
        List<DeliveredJob> delivered = queue.poll(LeaseReconciler.JOB_KIND, "state-service-test", 100, Duration.ZERO);
        delivered.forEach(queue::acknowledge);
        return delivered.stream().map(job -> job.job().resourceId()).toList();
    }
}

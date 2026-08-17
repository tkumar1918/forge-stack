package dev.tushar.forgestack.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tushar.forgestack.platform.tenancy.TenantScope;
import dev.tushar.forgestack.support.AbstractIntegrationTest;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The guarantees the task model rests on, checked against the database rather than the code.
 *
 * <p>Phase 2 exists to make the substrate provably correct before anything unpredictable runs on
 * top of it. Every rule here is one the runtime will assume without re-checking, so each is asserted
 * where it is actually enforced — a constraint, not a service method.
 */
class TaskModelSchemaTest extends AbstractIntegrationTest {

    @Autowired
    private TenantScope tenantScope;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID workspaceId;
    private UUID taskId;

    @BeforeEach
    void createTask() {
        this.workspaceId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO workspaces (id, slug, name) VALUES (?, ?, ?)",
                workspaceId,
                "ws-" + workspaceId.toString().substring(0, 8),
                "Task model test");

        this.taskId = tenantScope.runInTenant(workspaceId, () -> {
            UUID id = UUID.randomUUID();
            jdbcTemplate.update(
                    """
                    INSERT INTO tasks (id, workspace_id, origin, title, goal)
                    VALUES (?, ?, 'USER', 'Fix the failing build', 'Make CI green again')
                    """,
                    id,
                    workspaceId);
            return id;
        });
    }

    /**
     * The exit criterion for step 2.2.
     *
     * <p>Two workers that both read "no live attempt" and both insert are each individually correct;
     * no check before the write closes that window. Run concurrently on purpose — a sequential test
     * would pass against application-level checking too, and prove nothing about the case that
     * actually happens.
     */
    @Test
    @DisplayName("concurrent attempt creation is refused by the database, not by application logic")
    void onlyOneAttemptMayBeInFlight() throws Exception {
        int racers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        try {
            List<Callable<Boolean>> attempts = java.util.stream.IntStream.rangeClosed(1, racers)
                    .<Callable<Boolean>>mapToObj(attemptNo -> () -> {
                        try {
                            openAttempt(attemptNo);
                            return true;
                        } catch (RuntimeException e) {
                            return false;
                        }
                    })
                    .toList();

            long opened = pool.invokeAll(attempts).stream()
                    .filter(TaskModelSchemaTest::succeeded)
                    .count();

            assertThat(opened).as("exactly one racer may open an attempt").isEqualTo(1);
            assertThat(liveAttemptCount()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    /** Ending one attempt is what frees the task for the next — retry has to remain possible. */
    @Test
    @DisplayName("a finished attempt frees the task for the next one")
    void endingAnAttemptAllowsARetry() {
        openAttempt(1);
        endAttempt(1, "FAILED");

        assertThatCode(() -> openAttempt(2)).doesNotThrowAnyException();
        assertThat(liveAttemptCount()).isEqualTo(1);
    }

    /**
     * An attempt is finished exactly when it has both an outcome and an end.
     *
     * <p>Half-ended rows are the shape that breaks the partial index: an {@code ended_at} with no
     * outcome silently releases the single-writer slot while the attempt is still running.
     */
    @Test
    @DisplayName("an attempt cannot be half-finished")
    void outcomeAndEndArriveTogether() {
        openAttempt(1);

        assertThatThrownBy(() -> tenantScope.runInTenant(workspaceId, () -> jdbcTemplate.update(
                        "UPDATE task_attempts SET ended_at = now() WHERE task_id = ?", taskId)))
                .hasStackTraceContaining("task_attempts_ended_ck");

        assertThatThrownBy(() -> tenantScope.runInTenant(workspaceId, () -> jdbcTemplate.update(
                        "UPDATE task_attempts SET outcome = 'SUCCEEDED' WHERE task_id = ?", taskId)))
                .hasStackTraceContaining("task_attempts_ended_ck");
    }

    /** The FSM's states are the only ones representable; a typo is a constraint violation. */
    @Test
    @DisplayName("only declared task states can be stored")
    void statesAreClosed() {
        assertThatThrownBy(() -> tenantScope.runInTenant(workspaceId, () -> jdbcTemplate.update(
                        "UPDATE tasks SET state = 'BLOCKED' WHERE id = ?", taskId)))
                .as("BLOCKED was split into three states and must not come back")
                .hasStackTraceContaining("tasks_state_ck");

        assertThatThrownBy(() -> tenantScope.runInTenant(workspaceId, () -> jdbcTemplate.update(
                        "UPDATE tasks SET state = 'RESUMED' WHERE id = ?", taskId)))
                .as("RESUMED is an event, not a state")
                .hasStackTraceContaining("tasks_state_ck");
    }

    /** A terminal state has to say why it ended; a live one must not claim a reason. */
    @Test
    @DisplayName("only terminal states may carry a terminal reason")
    void terminalReasonBelongsToTerminalStates() {
        assertThatThrownBy(() -> tenantScope.runInTenant(workspaceId, () -> jdbcTemplate.update(
                        "UPDATE tasks SET terminal_reason = 'gave up' WHERE id = ?", taskId)))
                .hasStackTraceContaining("tasks_terminal_reason_ck");

        assertThatCode(() -> tenantScope.runInTenant(workspaceId, () -> jdbcTemplate.update(
                        "UPDATE tasks SET state = 'ABANDONED', terminal_reason = 'attempt cap' WHERE id = ?",
                        taskId)))
                .doesNotThrowAnyException();
    }

    /**
     * The transition log is history, and history is not editable.
     *
     * <p>Asserted because {@code ALTER DEFAULT PRIVILEGES} grants the app full DML on every new
     * table, so append-only is only true where a migration explicitly revoked it — and V6 showed
     * what happens when one forgets.
     */
    @Test
    @DisplayName("the transition log cannot be rewritten by the application role")
    void transitionsAreAppendOnly() {
        tenantScope.runInTenant(workspaceId, () -> jdbcTemplate.update(
                """
                INSERT INTO task_state_transitions
                    (task_id, workspace_id, from_state, to_state, event, actor_type)
                VALUES (?, ?, 'CREATED', 'READY', 'ADMIT', 'SYSTEM')
                """,
                taskId,
                workspaceId));

        assertThatThrownBy(() -> tenantScope.runInTenant(workspaceId, () -> jdbcTemplate.update(
                        "UPDATE task_state_transitions SET to_state = 'COMPLETED' WHERE task_id = ?", taskId)))
                .hasStackTraceContaining("permission denied");

        assertThatThrownBy(() -> tenantScope.runInTenant(workspaceId, () -> jdbcTemplate.update(
                        "DELETE FROM task_state_transitions WHERE task_id = ?", taskId)))
                .hasStackTraceContaining("permission denied");
    }

    /** Tasks are tenant data like everything else, and RLS is the backstop rather than a WHERE. */
    @Test
    @DisplayName("tasks are invisible outside their workspace")
    void tasksAreTenantScoped() {
        UUID stranger = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO workspaces (id, slug, name) VALUES (?, ?, ?)",
                stranger,
                "ws-" + stranger.toString().substring(0, 8),
                "Another tenant");

        List<UUID> seen = tenantScope.runInTenant(
                stranger, () -> jdbcTemplate.queryForList("SELECT id FROM tasks", UUID.class));

        assertThat(seen).doesNotContain(taskId);
    }

    // ---------------------------------------------------------------------------------------

    private void openAttempt(int attemptNo) {
        tenantScope.runInTenant(workspaceId, () -> jdbcTemplate.update(
                """
                INSERT INTO task_attempts (task_id, workspace_id, attempt_no)
                VALUES (?, ?, ?)
                """,
                taskId,
                workspaceId,
                attemptNo));
    }

    private void endAttempt(int attemptNo, String outcome) {
        tenantScope.runInTenant(workspaceId, () -> jdbcTemplate.update(
                """
                UPDATE task_attempts SET outcome = ?, ended_at = now()
                 WHERE task_id = ? AND attempt_no = ?
                """,
                outcome,
                taskId,
                attemptNo));
    }

    private int liveAttemptCount() {
        Integer count = tenantScope.runInTenant(workspaceId, () -> jdbcTemplate.queryForObject(
                "SELECT count(*) FROM task_attempts WHERE task_id = ? AND ended_at IS NULL",
                Integer.class,
                taskId));
        return count == null ? 0 : count;
    }

    private static boolean succeeded(Future<Boolean> result) {
        try {
            return Boolean.TRUE.equals(result.get());
        } catch (Exception e) {
            return false;
        }
    }
}

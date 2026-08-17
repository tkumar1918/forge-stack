package dev.tushar.forgestack.task;

import dev.tushar.forgestack.platform.tenancy.TenantScope;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Creating and reading tasks.
 *
 * <p>State changes are not here. Everything that moves a task belongs to {@link TaskStateService},
 * and keeping the two apart is deliberate: a create method that also transitioned would be a second
 * path into the FSM, and the FSM having exactly one entrance is the property everything above it
 * relies on. This class creates the row and then asks the state service to admit and queue it, the
 * same way any other caller would.
 */
@Service
public class TaskService {

    private final TenantScope tenantScope;
    private final TaskStateService taskStates;
    private final JdbcTemplate jdbc;

    TaskService(TenantScope tenantScope, TaskStateService taskStates, JdbcTemplate jdbc) {
        this.tenantScope = tenantScope;
        this.taskStates = taskStates;
        this.jdbc = jdbc;
    }

    /**
     * Creates a task and puts it on the queue.
     *
     * <p>Three transitions, not one: {@code CREATED → READY → QUEUED}. Creating a row already
     * admitted would hide the two decisions that admission actually is — budget and policy — behind
     * an insert, and they are decisions worth having a record of even while nothing yet makes them.
     *
     * <p>A repeat of an idempotency key returns the original task untouched. Not an error: the caller
     * that retries after a timeout has no way to know whether the first attempt landed, and answering
     * "conflict" would leave it no better off (plan §21).
     */
    public TaskView create(UUID workspaceId, UUID createdBy, NewTask request) {
        Optional<TaskView> existing = findByIdempotencyKey(workspaceId, request.idempotencyKey());
        if (existing.isPresent()) {
            return existing.get();
        }

        UUID taskId;
        try {
            taskId = insert(workspaceId, createdBy, request);
        } catch (DuplicateKeyException e) {
            // Two concurrent creates with one key. The loser reads the winner's task, which is the
            // same answer it would have got a moment earlier.
            return findByIdempotencyKey(workspaceId, request.idempotencyKey())
                    .orElseThrow(() -> e);
        }

        taskStates.apply(workspaceId, taskId, TaskEvent.ADMIT, Actor.human(createdBy), "created by a person");
        taskStates.apply(workspaceId, taskId, TaskEvent.ENQUEUE, Actor.system(), "there is capacity");
        return find(workspaceId, taskId).orElseThrow();
    }

    public List<TaskView> list(UUID workspaceId) {
        return tenantScope.runInTenant(
                workspaceId, () -> jdbc.query(SELECT_TASK + " ORDER BY created_at DESC LIMIT 200", TaskService::toView));
    }

    public Optional<TaskView> find(UUID workspaceId, UUID taskId) {
        return tenantScope.runInTenant(workspaceId, () -> jdbc
                .query(SELECT_TASK + " WHERE id = ?", TaskService::toView, taskId)
                .stream()
                .findFirst());
    }

    /** One task with every attempt and every transition, oldest first. */
    public Optional<TaskView.Detail> detail(UUID workspaceId, UUID taskId) {
        return find(workspaceId, taskId).map(task -> tenantScope.runInTenant(workspaceId, () -> {
            List<TaskView.AttemptView> attempts = jdbc.query(
                    """
                    SELECT attempt_no, phase, outcome, failure_summary, started_at, ended_at
                      FROM task_attempts WHERE task_id = ? ORDER BY attempt_no
                    """,
                    (rs, row) -> new TaskView.AttemptView(
                            rs.getInt("attempt_no"),
                            rs.getString("phase"),
                            rs.getString("outcome"),
                            rs.getString("failure_summary"),
                            instant(rs.getTimestamp("started_at")),
                            instant(rs.getTimestamp("ended_at"))),
                    taskId);

            List<TaskView.TransitionView> transitions = jdbc.query(
                    """
                    SELECT from_state, to_state, event, actor_type, reason, guard_results::text AS guards, created_at
                      FROM task_state_transitions WHERE task_id = ? ORDER BY created_at, id
                    """,
                    (rs, row) -> new TaskView.TransitionView(
                            TaskState.valueOf(rs.getString("from_state")),
                            TaskState.valueOf(rs.getString("to_state")),
                            TaskEvent.valueOf(rs.getString("event")),
                            Actor.Kind.valueOf(rs.getString("actor_type")),
                            rs.getString("reason"),
                            rs.getString("guards"),
                            instant(rs.getTimestamp("created_at"))),
                    taskId);

            return new TaskView.Detail(task, attempts, transitions);
        }));
    }

    // ---------------------------------------------------------------------------------------

    private static final String SELECT_TASK =
            """
            SELECT id, title, goal, acceptance_criteria, state, state_entered_at, terminal_reason,
                   attempt_count, max_attempts, lease_owner, simulated_outcome, created_at
              FROM tasks
            """;

    private UUID insert(UUID workspaceId, UUID createdBy, NewTask request) {
        return tenantScope.runInTenant(workspaceId, () -> jdbc.queryForObject(
                """
                INSERT INTO tasks (workspace_id, managed_repository_id, origin, title, goal,
                                   acceptance_criteria, idempotency_key, max_attempts,
                                   simulated_outcome, created_by)
                VALUES (?, ?, 'USER', ?, ?, ?, ?, coalesce(?, 5), ?, ?)
                RETURNING id
                """,
                UUID.class,
                workspaceId,
                request.managedRepositoryId(),
                request.title(),
                request.goal(),
                request.acceptanceCriteria(),
                request.idempotencyKey(),
                request.maxAttempts(),
                request.simulatedOutcome() == null ? null : request.simulatedOutcome().name(),
                createdBy));
    }

    private Optional<TaskView> findByIdempotencyKey(UUID workspaceId, String key) {
        if (key == null) {
            return Optional.empty();
        }
        return tenantScope.runInTenant(workspaceId, () -> jdbc
                .query(SELECT_TASK + " WHERE idempotency_key = ?", TaskService::toView, key)
                .stream()
                .findFirst());
    }

    private static TaskView toView(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new TaskView(
                rs.getObject("id", UUID.class),
                rs.getString("title"),
                rs.getString("goal"),
                rs.getString("acceptance_criteria"),
                TaskState.valueOf(rs.getString("state")),
                instant(rs.getTimestamp("state_entered_at")),
                rs.getString("terminal_reason"),
                rs.getInt("attempt_count"),
                rs.getInt("max_attempts"),
                rs.getString("lease_owner"),
                rs.getString("simulated_outcome") == null
                        ? null
                        : SimulatedOutcome.valueOf(rs.getString("simulated_outcome")),
                instant(rs.getTimestamp("created_at")));
    }

    private static java.time.Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}

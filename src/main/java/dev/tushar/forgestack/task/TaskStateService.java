package dev.tushar.forgestack.task;

import dev.tushar.forgestack.platform.jobs.JobEnqueueRequested;
import dev.tushar.forgestack.platform.jobs.JobMessage;
import dev.tushar.forgestack.platform.tenancy.TenantScope;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * The only thing that changes a task's state.
 *
 * <p>Everything funnels through {@code apply}: look up the transition, evaluate the guards, write the
 * state and its transition row in one transaction. The structural claim is that there is no other
 * path — no reflective dispatch, no string-driven state, and in particular no tool the model can call
 * that writes {@code tasks.state}. Its strongest available move is to ask for {@link TaskEvent#COMPLETE},
 * which still has to satisfy every guard.
 *
 * <h2>Two ways in, and the difference is who is asking</h2>
 *
 * <p>{@link #apply(Lease, TaskEvent, Actor, String)} is for a worker acting on a task it holds;
 * {@link #apply(UUID, UUID, TaskEvent, Actor, String)} is for everyone else — admission, cancellation,
 * the reconciler. Choosing wrong is not a silent mistake: V10's fence refuses an unfenced write to a
 * task under a live claim, so transitioning a running task from outside fails loudly at the database
 * rather than racing the worker.
 */
@Service
public class TaskStateService {

    private final TenantScope tenantScope;
    private final LeaseScope leaseScope;
    private final JdbcTemplate jdbc;
    private final ApplicationEventPublisher events;

    TaskStateService(
            TenantScope tenantScope, LeaseScope leaseScope, JdbcTemplate jdbc, ApplicationEventPublisher events) {
        this.tenantScope = tenantScope;
        this.leaseScope = leaseScope;
        this.jdbc = jdbc;
        this.events = events;
    }

    /** Applies an event to a task nobody is currently running. */
    public TaskState apply(UUID workspaceId, UUID taskId, TaskEvent event, Actor actor, String reason) {
        return tenantScope.runInTenant(workspaceId, () -> applyAlreadyScoped(workspaceId, taskId, event, actor, reason));
    }

    /** Applies an event to a task the caller holds a live claim on. */
    public TaskState apply(Lease lease, TaskEvent event, Actor actor, String reason) {
        return leaseScope.runUnderLease(
                lease, () -> applyAlreadyScoped(lease.workspaceId(), lease.taskId(), event, actor, reason));
    }

    /**
     * Applies an event inside a transaction the caller has already scoped to the tenant.
     *
     * <p>Package-private, and the reason it exists is the reconciler: reclaiming a lapsed lease and
     * moving the task back to {@code QUEUED} have to commit together, or a worker can claim the task
     * in between and find itself transitioned out from under it.
     */
    TaskState applyAlreadyScoped(UUID workspaceId, UUID taskId, TaskEvent event, Actor actor, String reason) {
        TaskFacts facts = lockAndRead(workspaceId, taskId);

        TaskTransitions.Transition transition = TaskTransitions.lookup(facts.state(), event)
                .orElseThrow(() -> new IllegalTransitionException(facts.state(), event));

        Map<TaskGuard, TaskGuard.Outcome> results = new LinkedHashMap<>();
        for (TaskGuard guard : transition.guards()) {
            results.put(guard, guard.evaluate(facts));
        }
        if (results.containsValue(TaskGuard.Outcome.REFUSED)) {
            throw new GuardsRefusedException(facts.state(), event, results);
        }

        write(taskId, transition, event, actor, reason, results, workspaceId);

        if (transition.to() == TaskState.QUEUED) {
            // Queueing is a consequence of entering the state, not a separate decision somebody has
            // to remember to make. Published rather than enqueued, so it rolls back with the
            // transition and survives a crash before the relay runs.
            events.publishEvent(
                    new JobEnqueueRequested(JobMessage.of(LeaseReconciler.JOB_KIND, workspaceId, taskId)));
        }
        return transition.to();
    }

    // ---------------------------------------------------------------------------------------

    /**
     * Reads everything the guards may look at, holding the row.
     *
     * <p>{@code FOR UPDATE} rather than optimistic retry: two transitions on one task are rare and
     * serialising them is cheap, whereas a lost update here would mean a transition row describing a
     * change that did not happen — and the transition log is the record everything else is diagnosed
     * from.
     */
    private TaskFacts lockAndRead(UUID workspaceId, UUID taskId) {
        return jdbc.query(
                        """
                        SELECT t.id,
                               t.state,
                               t.attempt_count,
                               t.max_attempts,
                               t.budget_usd_micros,
                               t.consumed_usd_micros,
                               t.budget_tokens,
                               t.consumed_tokens,
                               (SELECT a.outcome FROM task_attempts a
                                 WHERE a.task_id = t.id AND a.ended_at IS NOT NULL
                                 ORDER BY a.attempt_no DESC LIMIT 1) AS latest_outcome,
                               (SELECT a.diff_guard_verdict FROM task_attempts a
                                 WHERE a.task_id = t.id AND a.ended_at IS NOT NULL
                                 ORDER BY a.attempt_no DESC LIMIT 1) AS latest_diff_guard_verdict,
                               EXISTS (SELECT 1 FROM task_attempts a
                                        WHERE a.task_id = t.id AND a.ended_at IS NULL) AS attempt_in_flight
                          FROM tasks t
                         WHERE t.id = ?
                           FOR UPDATE OF t
                        """,
                        (rs, row) -> new TaskFacts(
                                rs.getObject("id", UUID.class),
                                TaskState.valueOf(rs.getString("state")),
                                rs.getInt("attempt_count"),
                                rs.getInt("max_attempts"),
                                (Long) rs.getObject("budget_usd_micros"),
                                rs.getLong("consumed_usd_micros"),
                                (Long) rs.getObject("budget_tokens"),
                                rs.getLong("consumed_tokens"),
                                rs.getString("latest_outcome"),
                                rs.getString("latest_diff_guard_verdict"),
                                rs.getBoolean("attempt_in_flight")),
                        taskId)
                .stream()
                .findFirst()
                .orElseThrow(() -> new UnknownTaskException(workspaceId, taskId));
    }

    private void write(
            UUID taskId,
            TaskTransitions.Transition transition,
            TaskEvent event,
            Actor actor,
            String reason,
            Map<TaskGuard, TaskGuard.Outcome> results,
            UUID workspaceId) {

        jdbc.update(
                """
                UPDATE tasks
                   SET state = ?,
                       state_entered_at = now(),
                       terminal_reason = CASE WHEN ? THEN ? ELSE NULL END,
                       version = version + 1,
                       updated_at = now()
                 WHERE id = ?
                """,
                transition.to().name(),
                transition.to().isTerminal(),
                reason,
                taskId);

        jdbc.update(
                """
                INSERT INTO task_state_transitions
                    (task_id, workspace_id, from_state, to_state, event, actor_type, actor_id, reason, guard_results)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                """,
                taskId,
                workspaceId,
                transition.from().name(),
                transition.to().name(),
                event.name(),
                actor.kind().name(),
                actor.id(),
                reason,
                asJson(results));
    }

    /**
     * The guard verdicts, as JSON for the transition row.
     *
     * <p>Hand-built rather than serialised. Both Jackson 2 and Jackson 3 are on the classpath
     * (`known-gaps.md` §6.1), and every key and value here is an enum constant — {@code [A-Z_]+} on
     * both sides — so there is nothing to escape and nothing a caller can influence.
     *
     * <p>This column is the point of the pending guards. A task completed today records permanently
     * that five of its eight preconditions were {@code NOT_ENFORCED}, so nobody reading its history in
     * a year has to work out what this system checked at the time.
     */
    private static String asJson(Map<TaskGuard, TaskGuard.Outcome> results) {
        return results.entrySet().stream()
                .map(entry -> "\"%s\":\"%s\"".formatted(entry.getKey().name(), entry.getValue().name()))
                .collect(Collectors.joining(",", "{", "}"));
    }
}

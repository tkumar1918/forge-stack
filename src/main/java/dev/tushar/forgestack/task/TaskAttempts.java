package dev.tushar.forgestack.task;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * One coherent attempt at a task, and the steps inside it.
 *
 * <p>Lives here rather than in {@code runtime} because these are the task module's tables. The
 * runtime crosses the boundary by calling this, not by writing rows it does not own — which is what
 * keeps the runtime extractable into its own service later without dragging the schema with it.
 *
 * <p>Everything takes a {@link Lease}. A worker with no claim cannot open an attempt, cannot record
 * a step, and cannot end one; there is no overload that lets it try. Below the compiler, V10's fence
 * refuses the writes anyway.
 */
@Service
public class TaskAttempts {

    private final LeaseScope leaseScope;
    private final JdbcTemplate jdbc;

    TaskAttempts(LeaseScope leaseScope, JdbcTemplate jdbc) {
        this.leaseScope = leaseScope;
        this.jdbc = jdbc;
    }

    /**
     * Starts the next attempt, and counts it.
     *
     * <p>The count comes from the row rather than from the caller, so two workers cannot disagree
     * about which attempt this is — and {@code one_live_attempt_per_task} refuses the second one
     * regardless, which is what makes this safe to call without checking first.
     */
    public OpenedAttempt open(Lease lease) {
        return leaseScope.runUnderLease(lease, () -> {
            Integer attemptNo = jdbc.queryForObject(
                    """
                    UPDATE tasks SET attempt_count = attempt_count + 1, version = version + 1, updated_at = now()
                     WHERE id = ?
                    RETURNING attempt_count
                    """,
                    Integer.class,
                    lease.taskId());

            UUID id = jdbc.queryForObject(
                    """
                    INSERT INTO task_attempts (task_id, workspace_id, attempt_no)
                    VALUES (?, ?, ?)
                    RETURNING id
                    """,
                    UUID.class,
                    lease.taskId(),
                    lease.workspaceId(),
                    attemptNo);
            return new OpenedAttempt(id, attemptNo);
        });
    }

    /**
     * The attempt just started, and which number it is.
     *
     * <p>The number comes back from the write rather than being counted by the caller, because it is
     * what decides whether the next failure is a retry or the end of the road.
     */
    public record OpenedAttempt(UUID id, int attemptNo) {}

    /** Moves an attempt to its next phase. Phases are how the work is going, not where the task is. */
    public void enterPhase(Lease lease, UUID attemptId, String phase) {
        leaseScope.runUnderLease(lease, () -> jdbc.update(
                "UPDATE task_attempts SET phase = ?, phase_entered_at = now() WHERE id = ?", phase, attemptId));
    }

    /**
     * Records one step, already finished.
     *
     * <p>The grain is one step per model call and one per tool call, chosen so that a crash costs at
     * most one tool call: coarser and a restart re-runs a ten-minute test suite, finer and the table
     * drowns. {@code unique(attempt_id, step_no)} is what makes replay idempotent — a resumed worker
     * recomputes step N, finds it committed, and moves on.
     */
    public void recordStep(Lease lease, UUID attemptId, int stepNo, String phase, String kind, String status) {
        leaseScope.runUnderLease(lease, () -> jdbc.update(
                """
                INSERT INTO task_steps (attempt_id, task_id, workspace_id, step_no, phase, kind, status, ended_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, now())
                ON CONFLICT (attempt_id, step_no) DO NOTHING
                """,
                attemptId,
                lease.taskId(),
                lease.workspaceId(),
                stepNo,
                phase,
                kind,
                status));
    }

    /**
     * Records what the diff guards made of this attempt's work (§17).
     *
     * <p>Written before the attempt ends, so that a completion decided a moment later reads a verdict
     * that is already committed rather than one still in flight. The guards read committed rows only,
     * and this is one of the rows they read.
     *
     * @param findings a summary for whoever looks next, required when the verdict refused, and
     *     never containing the value of anything it caught
     */
    public void recordDiffGuardVerdict(Lease lease, UUID attemptId, String verdict, String findings) {
        leaseScope.runUnderLease(lease, () -> jdbc.update(
                "UPDATE task_attempts SET diff_guard_verdict = ?, diff_guard_findings = ? WHERE id = ?",
                verdict,
                findings,
                attemptId));
    }

    /**
     * Ends the attempt, freeing the task for the next one.
     *
     * <p>Outcome and end time go in together because the schema requires it: a half-ended row would
     * release the single-writer slot while the attempt is still running.
     *
     * <p>{@code ABORTED} is the one to be careful with — it means the infrastructure died, not that
     * the approach was wrong, and it must not consume the retry budget or pollute the record of what
     * has already been tried.
     */
    public void end(Lease lease, UUID attemptId, String outcome, String failureClass, String summary) {
        leaseScope.runUnderLease(lease, () -> jdbc.update(
                """
                UPDATE task_attempts
                   SET outcome = ?, failure_class = ?, failure_summary = ?, ended_at = now()
                 WHERE id = ? AND ended_at IS NULL
                """,
                outcome,
                failureClass,
                summary,
                attemptId));
    }
}

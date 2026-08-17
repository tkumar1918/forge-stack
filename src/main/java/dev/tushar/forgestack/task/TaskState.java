package dev.tushar.forgestack.task;

/**
 * Where a task is in its life, and nothing about how the work is being done.
 *
 * <p>Mirrors {@code tasks_state_ck} exactly. The database is the authority — a state that exists here
 * and not there is a runtime failure on the first write — and this enum is the compiler's copy of it.
 *
 * <p><strong>New states must be lifecycle-distinct.</strong> If it describes <em>how</em> the work is
 * going, it is an attempt phase; if it is a fact <em>about</em> the task, it is a column. Every edge
 * case tempts a new state, and twelve states become thirty the moment that rule stops being applied
 * (plan §10.3). {@code BLOCKED} was already removed once for collapsing three conditions with three
 * different owners and three different resolutions into one bucket nobody could act on.
 */
public enum TaskState {
    /** Exists, not yet admitted: budget and policy unchecked. */
    CREATED,
    /** Every blocking dependency is satisfied; waiting for capacity. */
    READY,
    /** A confirmed {@code BLOCKS} edge is unsatisfied. */
    BLOCKED_ON_DEPENDENCY,
    /** On a stream, unclaimed. */
    QUEUED,
    /** An attempt is in flight; the attempt FSM is active inside this state. */
    RUNNING,
    /** An open intervention is waiting on a person. */
    AWAITING_HUMAN,
    /** A pull request is open, waiting on CI or review. */
    AWAITING_EXTERNAL,
    /** Budget exhausted, workspace paused, or access lost. Never a silent degradation. */
    SUSPENDED,

    /** A pull request was merged, or a human explicitly accepted the work. */
    COMPLETED,
    /** Deterministically unachievable. Stop asking. */
    FAILED,
    /** A human cancelled it. */
    CANCELLED,
    /**
     * The attempt cap was reached without success.
     *
     * <p>Distinct from {@link #FAILED} on purpose, and the distinction is operational rather than
     * semantic: {@code FAILED} means stop asking, {@code ABANDONED} means a person should look. They
     * belong on different dashboards and lead to different follow-up.
     */
    ABANDONED;

    /** True when nothing further can happen to this task. */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED || this == ABANDONED;
    }
}

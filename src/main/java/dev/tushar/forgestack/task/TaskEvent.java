package dev.tushar.forgestack.task;

/**
 * The things that can happen to a task.
 *
 * <p>An event is a request, never an instruction. {@link TaskTransitions} decides whether it is legal
 * from the current state and {@link TaskGuard} decides whether it is allowed at all, so naming one
 * here grants nothing. That matters most for {@link #COMPLETE}: the most powerful thing the agent
 * will ever be able to do is ask for it.
 */
public enum TaskEvent {
    /** Admitted for work: budget and policy have been looked at. */
    ADMIT,
    /** A blocking dependency turned out to be unsatisfied. */
    BLOCK,
    /** The last blocking dependency was satisfied. */
    DEP_RESOLVED,
    /** There is capacity; put it on the queue. */
    ENQUEUE,
    /** A worker took the lease and is starting an attempt. */
    CLAIM,
    /** The holder stopped renewing. Raised by the reconciler, never by a worker. */
    LEASE_EXPIRED,
    /** An attempt ended without success and the task has budget left to try again. */
    ATTEMPT_FAILED,
    /** Something needs a person before the work can continue. */
    ESCALATE_HUMAN,
    /** The person answered, and the answer was to carry on. */
    RESUME,
    /** The person answered, and the answer was no. */
    REJECT,
    /** Nobody answered in time. */
    TIMEOUT,
    /** A pull request is open; the next move belongs to CI or a reviewer. */
    SUBMIT,
    /** CI failed or a reviewer asked for changes; the work comes back. */
    EXTERNAL_FAILED,
    /** The work is done and accepted. The guarded one. */
    COMPLETE,
    /** Deterministically unachievable. */
    FAIL,
    /** The attempt cap was reached without success. */
    ABANDON,
    /** A person stopped it. */
    CANCEL,
    /** Budget exhausted, workspace paused, or repository access lost. */
    SUSPEND,
    /** Whatever caused the suspension was resolved. */
    UNSUSPEND
}

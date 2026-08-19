package dev.tushar.forgestack.task;

/**
 * How the fake phase handler should make an attempt turn out. Phase 2 scaffolding.
 *
 * <p>Exists so the lifecycle can be driven end to end with no model and no sandbox — which is the
 * whole point of proving the substrate before anything unpredictable runs on it. When the agent later
 * misbehaves, this is what makes it possible to say the queue, the lease, the FSM and the guards were
 * already known to work.
 *
 * <p>Mirrors {@code tasks_simulated_outcome_ck}. Removed with the fake, in Phase 4.
 */
public enum SimulatedOutcome {
    /** The first attempt succeeds. */
    SUCCEED,
    /** The first attempt fails and the next one succeeds — the ordinary retry path. */
    FAIL_ONCE,
    /** Every attempt fails, until the cap is reached and the task is abandoned. */
    FAIL,
    /** The first attempt asks for a person. */
    ESCALATE,
    /**
     * The attempt makes the tests pass by disabling one, and §17's guards refuse it.
     *
     * <p>The case the product exists for. Verification itself is satisfied — that is what makes it
     * dangerous — so the only thing standing between this and a completed task is the diff.
     */
    CHEAT;

    /**
     * What to do when a task says nothing.
     *
     * <p>Succeeding, because the alternative is that every task created without scaffolding hangs,
     * and a default that makes the system look broken teaches people to distrust it.
     */
    public static SimulatedOutcome orDefault(String stored) {
        return stored == null ? SUCCEED : valueOf(stored);
    }
}

package dev.tushar.forgestack.harness;

/**
 * Why a run stopped — and deliberately not whether the work was any good.
 *
 * <p>Read the values looking for a way to say "the task is complete". There isn't one, and that is
 * the entire point of this enum. A harness that could report success would be deciding a question
 * §10.3 reserves for guards reading committed rows: verification passed on the final SHA, diff
 * guards clean, no open interventions, authority sufficient, budget intact. A Python process in a
 * sandbox holding attacker-controlled repository content is the last thing that should be trusted
 * with that judgement.
 *
 * <p>{@link #INSTRUCTION_FINISHED} is the closest it gets, and it says only that the agent stopped
 * working on what it was asked to do. Whether that produced anything acceptable is answered
 * afterwards, in Java, from evidence — never from here.
 *
 * <p>Appendix B lists "the harness wants to own when the task is done" as the boundary whose failure
 * would mean falling back to building the inner loop ourselves. Making it unsayable is cheaper than
 * watching for it in review.
 */
public enum StopReason {

    /**
     * The agent considers the instruction handled and has stopped.
     *
     * <p>Not "it worked". The OpenHands agent server reaches this by the model calling a finish
     * tool; the Claude Agent SDK by the turn ending. Both are the model's own opinion of its own
     * work, which is worth exactly what it costs.
     */
    INSTRUCTION_FINISHED,

    /** The agent wants a person before it goes further — a question, or an action needing approval. */
    AWAITING_HUMAN,

    /** {@link ExecutionHarness#pause} was called while the run was in flight. Resumable. */
    PAUSED,

    /** A ceiling was hit: steps, tokens, or wall clock. The attempt is over; the session may not be. */
    BUDGET_EXHAUSTED,

    /**
     * The agent stopped making progress and the harness noticed before we did.
     *
     * <p>Worth having as a distinct reason rather than folding into an error: a repeated
     * action-observation loop is the cheapest escalation trigger there is, and §9 wants to spend
     * supervisor tokens on exactly this condition. OpenHands' stuck detector is a working
     * implementation of the idea and reaches the conclusion from the event log rather than from the
     * model reporting it, which is the only way it is worth anything.
     */
    STUCK,

    /** The harness failed at its own job. Not the work failing — the runner failing. */
    HARNESS_ERROR
}

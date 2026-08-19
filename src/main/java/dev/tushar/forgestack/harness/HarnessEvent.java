package dev.tushar.forgestack.harness;

/**
 * What the agent did, as it does it.
 *
 * <p>A run could simply return a summary when it finished, and an earlier sketch of this port did.
 * Streaming instead is what buys pause, resume, live step rows, and a cost meter that is current
 * rather than retrospective — every control-plane feature hangs off this one seam, and adding it
 * later would mean changing every adapter. Both candidate harnesses already stream over a WebSocket,
 * so this costs nothing to receive.
 *
 * <p>Events are for observing and recording. <strong>No transition is ever decided from one.</strong>
 * A guard reads committed rows inside its own transaction (§10.3); an event has not necessarily been
 * committed anywhere yet, and a guard that trusted the stream would be deciding from a message
 * rather than from a fact.
 */
public sealed interface HarnessEvent {

    /**
     * The agent is about to use a tool.
     *
     * <p>{@code declaredRisk} is named for what it is: <em>the model's opinion of its own danger</em>,
     * supplied as a tool-call argument the model fills in while making the call. Both candidate
     * harnesses work this way, and OpenHands' default analyser returns it unmodified. Treating it as
     * a risk rating is the failure §17 forbids in one line — a model may raise risk and may never
     * lower it — because an argument for lowering risk is precisely what a successful prompt
     * injection produces. ForgeStack computes its own rating from path globs and change shape, takes
     * the higher of the two, and uses this field only as the second half of that maximum.
     *
     * <p>The name is the enforcement. {@code risk} would have been read as authoritative by someone
     * in a hurry, and this is not a field to be wrong about once.
     */
    record ToolCallRequested(String tool, String argumentsDigest, String declaredRisk) implements HarnessEvent {}

    /**
     * A tool call finished.
     *
     * <p>Output arrives as a digest and a length, never as content. §11 calls unbounded tool output
     * the context-window killer; it is also the memory killer for the process receiving the stream.
     * The full output goes to blob storage under the workspace's own prefix, and anything that wants
     * it fetches it deliberately.
     */
    record ToolCallCompleted(String tool, boolean failed, String outputDigest, long outputBytes)
            implements HarnessEvent {}

    /** The agent said something. Useful in a transcript, never load-bearing. */
    record AgentSpoke(String text) implements HarnessEvent {}

    /**
     * The agent asked for a person.
     *
     * <p>A request, not a transition. It arrives here, and {@code AWAITING_HUMAN} is entered by the
     * state machine afterwards, if the guards on that transition agree.
     */
    record HumanDecisionRequested(String question) implements HarnessEvent {}

    /**
     * What the last model call cost.
     *
     * <p>Reported per call rather than totalled at the end, because §22's ceilings are hard stops
     * and a ceiling checked only on completion is a ceiling that has already been passed.
     * {@code cachedInputTokens} is separate because prompt caching is the largest cost lever there
     * is, and a number nobody tracks is a lever nobody knows is broken.
     */
    record TokensConsumed(long inputTokens, long outputTokens, long cachedInputTokens) implements HarnessEvent {}
}

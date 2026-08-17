package dev.tushar.forgestack.task;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A task as the API shows it. */
public record TaskView(
        UUID id,
        String title,
        String goal,
        String acceptanceCriteria,
        TaskState state,
        Instant stateEnteredAt,
        String terminalReason,
        int attemptCount,
        int maxAttempts,
        String leaseOwner,
        SimulatedOutcome simulatedOutcome,
        Instant createdAt) {

    /**
     * One task with its whole history.
     *
     * <p>Attempts and transitions together are the replay: because both are append-only and ordered,
     * a reader can reconstruct exactly how a task reached where it is — every decision, who made it,
     * and which guards agreed. That is the single most valuable debugging surface in the product and
     * it comes free from the schema, but only while nothing is ever updated in place.
     */
    public record Detail(TaskView task, List<AttemptView> attempts, List<TransitionView> transitions) {}

    public record AttemptView(
            int attemptNo, String phase, String outcome, String failureSummary, Instant startedAt, Instant endedAt) {}

    public record TransitionView(
            TaskState fromState,
            TaskState toState,
            TaskEvent event,
            Actor.Kind actorKind,
            String reason,
            String guardResults,
            Instant at) {}
}

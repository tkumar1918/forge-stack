package dev.tushar.forgestack.runtime;

import dev.tushar.forgestack.task.Lease;
import dev.tushar.forgestack.task.SimulatedOutcome;
import dev.tushar.forgestack.task.TaskAttempts;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Stands in for the model and the sandbox, and does nothing else.
 *
 * <p>Walks an attempt through the phases the real handler will walk it through, writing the same step
 * rows, and then reports an outcome the task asked for in advance. Everything around it — the lease,
 * the attempt row, the retry budget, the escalation path, the guards — is real. Only the work is
 * pretend.
 *
 * <p>A concrete class rather than an interface with one implementation. The seam belongs here the day
 * a second handler exists, and not before: an interface introduced now would be an abstraction with
 * no pressure behind it, which is the reflex this project fails the build over.
 *
 * <p><strong>The one thing to be careful about</strong> is that this is the only code in the system
 * allowed to decide an outcome without evidence. When the real handler arrives, both this class and
 * {@code tasks.simulated_outcome} go with it.
 */
@Component
class FakePhaseHandler {

    /** The phases the real attempt loop will walk, minus the ones that need a model to be reached. */
    private static final List<String> PHASES = List.of("ANALYZING", "PLANNING", "EXECUTING", "VERIFYING");

    private final TaskAttempts attempts;

    FakePhaseHandler(TaskAttempts attempts) {
        this.attempts = attempts;
    }

    /**
     * Runs one attempt to its end.
     *
     * <p>Steps are recorded as they finish, at the grain the real loop will use, so resumption and
     * replay have something to be tested against long before there is anything real to replay.
     */
    AttemptResult run(Lease lease, UUID attemptId, SimulatedOutcome simulation, int attemptNo) {
        int step = 0;
        for (String phase : PHASES) {
            attempts.enterPhase(lease, attemptId, phase);
            attempts.recordStep(lease, attemptId, ++step, phase, "SYSTEM", "SUCCEEDED");
        }

        return switch (simulation) {
            case SUCCEED -> succeed(lease, attemptId, ++step);
            case FAIL_ONCE -> attemptNo > 1 ? succeed(lease, attemptId, ++step) : fail(lease, attemptId, ++step);
            case FAIL -> fail(lease, attemptId, ++step);
            case ESCALATE -> escalate(lease, attemptId, ++step);
        };
    }

    private AttemptResult succeed(Lease lease, UUID attemptId, int step) {
        attempts.enterPhase(lease, attemptId, "SUBMITTING");
        attempts.recordStep(lease, attemptId, step, "SUBMITTING", "SYSTEM", "SUCCEEDED");
        return new AttemptResult("SUCCEEDED", null, null);
    }

    private AttemptResult fail(Lease lease, UUID attemptId, int step) {
        attempts.enterPhase(lease, attemptId, "DIAGNOSING");
        attempts.recordStep(lease, attemptId, step, "DIAGNOSING", "SYSTEM", "FAILED");
        // VERIFICATION_FAILED and not TRANSIENT_INFRA, deliberately: this is the simulated work
        // being wrong, which is the only failure class that should cost the task an attempt.
        return new AttemptResult("FAILED", "VERIFICATION_FAILED", "the simulated verification did not pass");
    }

    private AttemptResult escalate(Lease lease, UUID attemptId, int step) {
        attempts.enterPhase(lease, attemptId, "ESCALATING");
        attempts.recordStep(lease, attemptId, step, "ESCALATING", "SYSTEM", "SUCCEEDED");
        return new AttemptResult("ESCALATED", "HUMAN_REQUIRED", "the simulated attempt asked for a person");
    }

    /** How an attempt ended, in the vocabulary {@code task_attempts.outcome} accepts. */
    record AttemptResult(String outcome, String failureClass, String summary) {}
}

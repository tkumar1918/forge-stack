package dev.tushar.forgestack.runtime;

import dev.tushar.forgestack.diffguard.DiffGuards;
import dev.tushar.forgestack.diffguard.DiffVerdict;
import dev.tushar.forgestack.harness.AttemptSpec;
import dev.tushar.forgestack.harness.EgressPolicy;
import dev.tushar.forgestack.harness.ExecutionHarness;
import dev.tushar.forgestack.harness.HarnessEvent;
import dev.tushar.forgestack.harness.HarnessException;
import dev.tushar.forgestack.harness.HarnessSession;
import dev.tushar.forgestack.harness.HarnessStop;
import dev.tushar.forgestack.harness.Instruction;
import dev.tushar.forgestack.harness.ResourceLimits;
import dev.tushar.forgestack.task.Lease;
import dev.tushar.forgestack.task.SimulatedOutcome;
import dev.tushar.forgestack.task.TaskAttempts;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * One attempt, from provisioning a sandbox to deciding what came of it.
 *
 * <p>The division this class exists to hold: <strong>the harness reports what happened, and this
 * decides what it was worth.</strong> An agent stopping is not an agent succeeding, and the two used
 * to be the same event because the fake handler returned both at once. Now the harness can only say
 * {@code INSTRUCTION_FINISHED}, and the verdict is reached here, in Java, from evidence — which is
 * what §9 means by "{@code VERIFYING} has no model in the decision path".
 *
 * <p>Steps come from the harness's own event stream rather than from a script, so the step log
 * records what the agent actually did rather than what we expected it to do. That is the whole
 * reason {@link ExecutionHarness#run} streams instead of returning a summary.
 */
@Component
class AttemptRunner {

    private static final Logger log = LoggerFactory.getLogger(AttemptRunner.class);

    private final ExecutionHarness harness;
    private final TaskAttempts attempts;
    private final DiffGuards diffGuards;
    private final String sandboxImage;

    AttemptRunner(
            ExecutionHarness harness,
            TaskAttempts attempts,
            DiffGuards diffGuards,
            @Value("${forgestack.runtime.sandbox-image:forgestack/java-21:latest}") String sandboxImage) {
        this.harness = harness;
        this.attempts = attempts;
        this.diffGuards = diffGuards;
        this.sandboxImage = sandboxImage;
    }

    AttemptOutcome run(Lease lease, UUID attemptId, SimulatedOutcome simulation, int attemptNo) {
        StepLog steps = new StepLog(lease, attemptId);
        HarnessSession session = null;
        try {
            attempts.enterPhase(lease, attemptId, "INITIALIZING");
            session = harness.open(spec(attemptId));
            steps.record("INITIALIZING", "SYSTEM", "SUCCEEDED");

            attempts.enterPhase(lease, attemptId, "EXECUTING");
            HarnessStop stop = harness.run(session, instructionFor(simulation), steps::observe);

            return switch (stop.reason()) {
                case INSTRUCTION_FINISHED -> verify(lease, attemptId, session, steps, simulation, attemptNo);
                case AWAITING_HUMAN -> {
                    attempts.enterPhase(lease, attemptId, "ESCALATING");
                    steps.record("ESCALATING", "SYSTEM", "SUCCEEDED");
                    yield new AttemptOutcome("ESCALATED", "HUMAN_REQUIRED", stop.detail());
                }
                // Not a failure of the work — the work never finished. Diagnosing what a half-run
                // attempt was trying to do would be diagnosing noise.
                case PAUSED -> new AttemptOutcome("ABORTED", "INTERRUPTED", stop.detail());
                case BUDGET_EXHAUSTED, STUCK -> {
                    attempts.enterPhase(lease, attemptId, "DIAGNOSING");
                    steps.record("DIAGNOSING", "SYSTEM", "FAILED");
                    yield new AttemptOutcome("FAILED", stop.reason().name(), stop.detail());
                }
                case HARNESS_ERROR -> new AttemptOutcome("ABORTED", "TRANSIENT_INFRA", stop.detail());
            };
        } catch (HarnessException.CapacityExhausted e) {
            // An answer, not an error: there is nowhere to run this right now. §16 puts placement
            // behind the port precisely so the runtime never tries to choose a host itself.
            log.debug("no capacity for attempt {}", attemptId);
            return new AttemptOutcome("ABORTED", "NO_CAPACITY", e.getMessage());
        } catch (HarnessException.SessionLost e) {
            // Routine (§20). Pods are evicted and nodes drain; a runtime that treated this as the
            // work failing would burn a retry every time the substrate had a bad afternoon.
            log.info("sandbox for attempt {} went away: {}", attemptId, e.getMessage());
            return new AttemptOutcome("ABORTED", "SANDBOX_LOST", e.getMessage());
        } catch (HarnessException e) {
            log.warn("harness failed attempt {}", attemptId, e);
            return new AttemptOutcome("ABORTED", "TRANSIENT_INFRA", e.getMessage());
        } finally {
            if (session != null) {
                // In a finally, and idempotent by contract, because the alternative is a sandbox per
                // crashed attempt sitting on a worker VM until its disk fills.
                harness.close(session);
            }
        }
    }

    /**
     * Whether the work is any good — asked here, and never of the harness.
     *
     * <p>Two separate questions, in this order: did the agent cheat, and did the work pass. The
     * first is answered from the diff by §17's guards, which is why a refusal here escalates rather
     * than retries — an agent that deleted a test will delete it again.
     *
     * <p>Simulated in Phase 2, and the simulation is deliberately on <em>this</em> side of the
     * boundary. It would have been less code to let the fake harness report a failure directly, and
     * that is exactly the shortcut that makes a verdict the agent's to give. {@link
     * dev.tushar.forgestack.harness.StopReason} has no value for it, so the shortcut does not exist.
     */
    private AttemptOutcome verify(
            Lease lease,
            UUID attemptId,
            HarnessSession session,
            StepLog steps,
            SimulatedOutcome simulation,
            int attemptNo) {

        attempts.enterPhase(lease, attemptId, "VERIFYING");

        // Before anything else, and before any question of whether the tests pass: what did the
        // agent actually change? §17's order matters — a suite that went green by losing a test is
        // not a suite that went green, so the diff is judged before the result is believed.
        DiffVerdict diff = diffGuards.check(harness.captureDiff(session));
        attempts.recordDiffGuardVerdict(
                lease, attemptId, diff.passed() ? "PASSED" : "REFUSED", diff.passed() ? null : diff.summary());
        steps.record("VERIFYING", "SYSTEM", diff.passed() ? "SUCCEEDED" : "FAILED");

        if (!diff.passed()) {
            // Escalated, not failed. A retry would produce the same reasonable-looking edit again,
            // and the thing that has gone wrong needs a person to look at it rather than a budget
            // spent re-discovering it (§17).
            log.warn("diff guards refused attempt {}: {}", attemptId, diff.summary());
            attempts.enterPhase(lease, attemptId, "ESCALATING");
            steps.record("ESCALATING", "SYSTEM", "SUCCEEDED");
            return new AttemptOutcome("ESCALATED", "POLICY_VIOLATION", diff.summary());
        }

        boolean passed =
                switch (simulation) {
                    // CHEAT passes here on purpose. Its tests really are green; the diff is the only
                    // thing that knows why, which is precisely the situation §17 was written for.
                    case SUCCEED, ESCALATE, CHEAT -> true;
                    case FAIL -> false;
                    case FAIL_ONCE -> attemptNo > 1;
                };
        steps.record("VERIFYING", "SYSTEM", passed ? "SUCCEEDED" : "FAILED");

        if (!passed) {
            attempts.enterPhase(lease, attemptId, "DIAGNOSING");
            steps.record("DIAGNOSING", "SYSTEM", "FAILED");
            return new AttemptOutcome("FAILED", "VERIFICATION_FAILED", "the simulated verification did not pass");
        }
        attempts.enterPhase(lease, attemptId, "SUBMITTING");
        steps.record("SUBMITTING", "SYSTEM", "SUCCEEDED");
        return new AttemptOutcome("SUCCEEDED", null, null);
    }

    private AttemptSpec spec(UUID attemptId) {
        return new AttemptSpec(
                attemptId,
                sandboxImage,
                // A real clone at a real base SHA arrives with the GitHub integration; the point of
                // the record is that neither ever carries a credential (§16).
                new AttemptSpec.WorkingCopy("/workspace", "0".repeat(40)),
                new ResourceLimits(2000, 4096, 8192, Duration.ofMinutes(60), 200, 500_000),
                EgressPolicy.DENY_ALL,
                Set.of("read_file", "grep", "apply_patch", "run_tests"));
    }

    /** What the agent is asked to do. Reads as a directive because Phase 2's harness is simulated. */
    private Instruction instructionFor(SimulatedOutcome simulation) {
        return switch (simulation) {
            case ESCALATE -> new Instruction("ASK:the simulated attempt asked for a person", 50);
            case CHEAT -> new Instruction("CHEAT:src/test/java/SimulatedTest.java", 50);
            default -> new Instruction("EDIT:src/main/java/Simulated.java", 50);
        };
    }

    /**
     * Numbers and persists steps as the harness reports them.
     *
     * <p>One row per completed tool call, plus one for each phase ForgeStack runs itself. The
     * numbering is local to the attempt and monotonic, which is what {@code unique(attempt_id,
     * step_no)} needs in order to make a resumed worker's replay idempotent.
     */
    private final class StepLog {
        private final Lease lease;
        private final UUID attemptId;
        private int stepNo;

        private StepLog(Lease lease, UUID attemptId) {
            this.lease = lease;
            this.attemptId = attemptId;
        }

        /**
         * @param kind one of the four {@code task_steps_kind_ck} allows: {@code LLM_CALL},
         *     {@code TOOL_CALL}, {@code SYSTEM}, {@code CHECKPOINT}. The vocabulary is closed in the
         *     schema, so inventing a fifth aborts the transaction and surfaces several statements
         *     later as something unrelated — which is exactly how it was found.
         */
        private void record(String phase, String kind, String status) {
            attempts.recordStep(lease, attemptId, ++stepNo, phase, kind, status);
        }

        private void observe(HarnessEvent event) {
            // Only completed tool calls become rows. A request without its result is a step nobody
            // can interpret, and the pair arrives within milliseconds.
            if (event instanceof HarnessEvent.ToolCallCompleted done) {
                record("EXECUTING", "TOOL_CALL", done.failed() ? "FAILED" : "SUCCEEDED");
            }
        }
    }

    /**
     * How an attempt ended, in the vocabulary {@code task_attempts.outcome} accepts.
     *
     * <p>{@code ABORTED} is the one to be careful with: the infrastructure failed, not the approach,
     * so it must not read as evidence that the work was wrong.
     */
    record AttemptOutcome(String outcome, String failureClass, String summary) {}
}

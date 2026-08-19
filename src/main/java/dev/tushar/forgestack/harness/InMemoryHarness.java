package dev.tushar.forgestack.harness;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

/**
 * An execution harness that runs nothing.
 *
 * <p>Every candidate harness costs a container, a model call, and real money per run. A runtime
 * tested only against those is a runtime tested rarely, and the parts most worth testing — a lease
 * lapsing mid-run, a sandbox vanishing, a budget hit at exactly the wrong moment — are the parts
 * hardest to arrange on purpose against a real one. This is the second implementation the
 * {@code @Port} on {@link ExecutionHarness} exists for.
 *
 * <p>This is what Phase 2 runs on, and it is the same class the conformance suite is pointed at —
 * deliberately, because a suite only ever run against a fake drifts towards asserting whatever the
 * fake happens to do. Proving the contract against the implementation the runtime actually uses is
 * worth more than proving it against a second simulator nobody runs.
 *
 * <p>The {@code induce} methods are the feature, not a leak. A runtime's recovery paths — a sandbox
 * vanishing mid-attempt, capacity refused under load — are the parts hardest to arrange against a
 * real harness and the parts most worth exercising, so this one can be told to fail on command.
 * When a real adapter lands, this class and {@code tasks.simulated_outcome} go with it.
 *
 * <p>Behaviour is driven by directives at the front of an instruction, the same idiom
 * {@code SimulatedOutcome} already uses for the phase handler: {@code ASK:question},
 * {@code STUCK}, {@code EDIT:path}. Anything else is worked on and finished. Keeping the vocabulary
 * in the instruction rather than in setter calls means a test reads as a scenario rather than as
 * configuration.
 */
@Component
public final class InMemoryHarness implements ExecutionHarness {

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private volatile boolean atCapacity = false;

    @Override
    public String name() {
        return "in-memory";
    }

    @Override
    public HarnessSession open(AttemptSpec spec) {
        if (atCapacity) {
            throw new HarnessException.CapacityExhausted("the fake was told it had no room");
        }
        if (!spec.ociImage().startsWith("forgestack/")) {
            // Stands in for an adapter refusing an image it has never heard of. Worth having in the
            // fake so the runtime's handling of SpecRejected is exercised without a registry.
            throw new HarnessException.SpecRejected("unknown image: " + spec.ociImage());
        }
        String externalId = UUID.randomUUID().toString();
        sessions.put(externalId, new Session(spec, externalId));
        return new HarnessSession(spec.attemptId(), name(), externalId);
    }

    @Override
    public HarnessStop run(HarnessSession session, Instruction instruction, Consumer<HarnessEvent> sink) {
        Session live = require(session);
        live.running = true;
        try {
            return work(live, instruction, sink);
        } finally {
            live.running = false;
            live.pauseRequested = false;
        }
    }

    @Override
    public void pause(HarnessSession session) {
        Session live = sessions.get(session.externalId());
        // Deliberately not require(): pausing a session that has already gone is what a cancelling
        // person does when the sandbox died a moment earlier, and it must not raise.
        if (live != null) {
            live.pauseRequested = true;
        }
    }

    @Override
    public String captureDiff(HarnessSession session) {
        Session live = require(session);
        if (live.edited.isEmpty() && live.cheated.isEmpty()) {
            return "";
        }
        StringBuilder diff = new StringBuilder();
        // Literal \n, not %n. An earlier version used %n inside plain append() calls, where it is
        // not a format specifier at all -- so every added and removed line ended up concatenated
        // onto the hunk header and the parser saw a file with no changes in it. The diff guards
        // passed because there was nothing to read, which is the most convincing way for a check to
        // do nothing. A diff is \n-delimited by definition anyway; the platform separator was never
        // the right thing here.
        for (String path : live.cheated) {
            // A test switched off and an assertion taken out with it -- the shape §17 exists to
            // catch, produced here so the guards can be driven by a real attempt rather than by a
            // string in a unit test.
            diff.append("diff --git a/").append(path).append(" b/").append(path).append('\n')
                    .append("--- a/").append(path).append('\n')
                    .append("+++ b/").append(path).append('\n')
                    .append("@@ -4,2 +4,2 @@\n")
                    .append("+    @Disabled(\"flaky\")\n")
                    .append("-    assertThat(total).isEqualTo(expected);\n");
        }
        for (String path : live.edited) {
            diff.append("diff --git a/").append(path).append(" b/").append(path).append('\n')
                    .append("--- a/").append(path).append('\n')
                    .append("+++ b/").append(path).append('\n')
                    .append("@@ -1 +1 @@\n")
                    .append("-was\n")
                    .append("+edited by the fake\n");
        }
        return diff.toString();
    }

    @Override
    public void close(HarnessSession session) {
        // remove(), not get()-then-remove: close is documented idempotent and is called from finally
        // blocks that cannot know whether the session survived.
        sessions.remove(session.externalId());
    }

    // --- what a test can arrange that a real harness would only do by accident -----------------

    /** Makes the next {@link #open} refuse, so backpressure handling can be exercised. */
    public void reportNoCapacity(boolean exhausted) {
        this.atCapacity = exhausted;
    }

    /** Destroys a session out from under a run, the way an evicted pod would. */
    public void induceSessionLoss(HarnessSession session) {
        sessions.remove(session.externalId());
    }

    /** Whether anything is still provisioned — a leak check for tests that finish abnormally. */
    public int liveSessions() {
        return sessions.size();
    }

    // -------------------------------------------------------------------------------------------

    private HarnessStop work(Session live, Instruction instruction, Consumer<HarnessEvent> sink) {
        String text = instruction.text();
        int steps = 0;
        int ceiling = Math.min(instruction.maxSteps(), live.spec.limits().maxSteps());

        while (steps < ceiling) {
            // Checked before the step, so a pause never lands halfway through one. The port promises
            // a step boundary and the fake has to keep that promise, or tests pass here and fail
            // against an adapter that keeps it properly.
            if (live.pauseRequested) {
                return new HarnessStop(StopReason.PAUSED, "a pause was requested", steps);
            }
            if (!sessions.containsKey(live.externalId)) {
                throw new HarnessException.SessionLost("the fake session was destroyed mid-run", null);
            }

            steps++;
            emitStep(live, text, sink, steps);

            if (text.startsWith("ASK:")) {
                sink.accept(new HarnessEvent.HumanDecisionRequested(text.substring(4)));
                return new HarnessStop(StopReason.AWAITING_HUMAN, "the agent asked for a person", steps);
            }
            if (text.startsWith("SPIN")) {
                // Keeps working until something stops it, which is the only way to test that a pause
                // arriving mid-run is honoured. Sleeps so the test does not spin a core hot.
                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return new HarnessStop(StopReason.HARNESS_ERROR, "interrupted", steps);
                }
                continue;
            }
            if (text.startsWith("STUCK")) {
                // Deliberately keeps going rather than returning at once: being stuck means repeating
                // yourself, and a detector that fired on the first repetition would fire on any retry.
                if (steps >= 3) {
                    return new HarnessStop(StopReason.STUCK, "the same step three times", steps);
                }
                continue;
            }
            return new HarnessStop(StopReason.INSTRUCTION_FINISHED, "the fake considers this handled", steps);
        }
        return new HarnessStop(StopReason.BUDGET_EXHAUSTED, "the step ceiling was reached", steps);
    }

    private void emitStep(Session live, String text, Consumer<HarnessEvent> sink, int step) {
        String tool = text.startsWith("EDIT:") || text.startsWith("CHEAT:") ? "apply_patch" : "read_file";
        if (!live.spec.allowedTools().contains(tool)) {
            // §15 requires the allowlist be enforced at dispatch and not only at offer time, because
            // models invent tool names. The fake enforces it so the conformance suite can insist
            // every adapter does.
            sink.accept(new HarnessEvent.ToolCallCompleted(tool, true, "refused", 0));
            return;
        }
        // The model's own opinion of its danger, which is all any harness can report. Named
        // declaredRisk end to end so nobody downstream reads it as a rating.
        sink.accept(new HarnessEvent.ToolCallRequested(tool, "digest-" + step, "LOW"));
        if (text.startsWith("EDIT:")) {
            live.edited.add(text.substring(5));
        }
        if (text.startsWith("CHEAT:")) {
            live.cheated.add(text.substring(6));
        }
        sink.accept(new HarnessEvent.ToolCallCompleted(tool, false, "digest-out-" + step, 128L * step));
        sink.accept(new HarnessEvent.TokensConsumed(1000, 200, 800));
    }

    private Session require(HarnessSession session) {
        Session live = sessions.get(session.externalId());
        if (live == null) {
            throw new HarnessException.SessionLost("no such session: " + session.externalId(), null);
        }
        return live;
    }

    private static final class Session {
        private final AttemptSpec spec;
        private final String externalId;
        private final Set<String> edited = new HashSet<>();
        private final Set<String> cheated = new HashSet<>();
        private volatile boolean running;
        private volatile boolean pauseRequested;

        private Session(AttemptSpec spec, String externalId) {
            this.spec = spec;
            this.externalId = externalId;
        }
    }
}

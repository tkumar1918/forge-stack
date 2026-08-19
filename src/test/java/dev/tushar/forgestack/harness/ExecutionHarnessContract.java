package dev.tushar.forgestack.harness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What every execution harness has to do, whoever wrote it.
 *
 * <p>An interface decouples nothing on its own — §16 makes the point at length, and the coupling
 * always turns out to live in the assumptions around the port rather than in the port. This class is
 * where those assumptions are written down and made to fail: a future OpenHands or Claude Agent SDK
 * adapter extends it and inherits an executable specification instead of a prose one. That is what
 * turns "we could swap the harness" from a claim into a measurable afternoon.
 *
 * <p>Written against {@link ExecutionHarness} only. If anything here needs to know which harness it
 * is testing, the port is leaking and the fix belongs in the port.
 *
 * <p>The three {@code induce*} hooks exist because the cases most worth pinning are the ones a
 * harness will not do on request. A fake flips a field; a Docker adapter kills a container; a hosted
 * adapter revokes a session. How is the adapter's business — <em>that</em> the runtime sees a
 * normalised {@link HarnessException} either way is this suite's business.
 */
abstract class ExecutionHarnessContract {

    /** The harness under test. */
    protected abstract ExecutionHarness harness();

    /** Destroy a session out from under a caller, however this harness can. */
    protected abstract void induceSessionLoss(HarnessSession session);

    /** Make the next {@link ExecutionHarness#open} refuse for want of room. */
    protected abstract void induceCapacityExhaustion();

    /** An image this harness will accept. */
    protected abstract String knownImage();

    // -------------------------------------------------------------------------------------------

    @Test
    @DisplayName("provisions, works, hands back a diff, and cleans up")
    void theHappyPath() {
        HarnessSession session = harness().open(spec());
        var events = new RecordedEvents();

        HarnessStop stop = harness().run(session, new Instruction("EDIT:src/Main.java", 10), events);

        assertThat(stop.reason()).isEqualTo(StopReason.INSTRUCTION_FINISHED);
        assertThat(stop.stepsRun()).isPositive();
        assertThat(harness().captureDiff(session)).contains("src/Main.java");
        harness().close(session);
    }

    /**
     * The ordering the caller's persistence depends on.
     *
     * <p>A caller writes step rows from the sink and reads the stop to decide what happens next. If
     * an event could arrive after the stop, it would be recording a step against an attempt it has
     * already closed — so the port promises every event is delivered first, and this is where that
     * promise is kept.
     */
    @Test
    @DisplayName("delivers every event before it reports the stop")
    void eventsArriveBeforeTheStop() {
        HarnessSession session = harness().open(spec());
        var events = new RecordedEvents();

        HarnessStop stop = harness().run(session, new Instruction("EDIT:a.txt", 10), events);
        List<HarnessEvent> afterStop = events.all();

        assertThat(afterStop).isNotEmpty();
        assertThat(stop).isNotNull();
        // Nothing further arrives once run has returned.
        assertThat(events.all()).isEqualTo(afterStop);
        harness().close(session);
    }

    @Test
    @DisplayName("reports what the model claimed about its own risk, and calls it a claim")
    void riskIsReportedAsTheModelsOwnOpinion() {
        HarnessSession session = harness().open(spec());
        var events = new RecordedEvents();

        harness().run(session, new Instruction("EDIT:a.txt", 5), events);

        // §17: a model may raise risk and may never lower it, so nothing downstream may treat this
        // as a rating. The field is named declaredRisk for that reason and the port has no other.
        assertThat(events.all())
                .filteredOn(HarnessEvent.ToolCallRequested.class::isInstance)
                .isNotEmpty()
                .allSatisfy(event -> assertThat(((HarnessEvent.ToolCallRequested) event).declaredRisk())
                        .isNotBlank());
        harness().close(session);
    }

    /**
     * A pause has to interrupt work that is already happening.
     *
     * <p>This is the case that matters for cancellation and for a lease lapsing under a worker: both
     * arrive from another thread while a run is in flight. A harness that only honoured a pause
     * between runs would leave a cancelled task running until it finished on its own.
     */
    @Test
    @DisplayName("stops an in-flight run when another thread asks it to")
    void pauseInterruptsAWorkingAgent() throws Exception {
        HarnessSession session = harness().open(spec());
        CountDownLatch started = new CountDownLatch(1);

        CompletableFuture<HarnessStop> running = CompletableFuture.supplyAsync(
                () -> harness().run(session, new Instruction("SPIN", 100_000), event -> started.countDown()));

        assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
        harness().pause(session);

        assertThat(running.get(10, TimeUnit.SECONDS).reason()).isEqualTo(StopReason.PAUSED);
        harness().close(session);
    }

    @Test
    @DisplayName("stops at the step ceiling rather than running forever")
    void budgetIsAHardStop() {
        HarnessSession session = harness().open(spec());

        HarnessStop stop = harness().run(session, new Instruction("SPIN", 3), event -> {});

        assertThat(stop.reason()).isEqualTo(StopReason.BUDGET_EXHAUSTED);
        assertThat(stop.stepsRun()).isEqualTo(3);
        harness().close(session);
    }

    /**
     * Sandbox loss is routine, and must arrive as the one exception the runtime handles.
     *
     * <p>§20 makes this {@code ABORTED} rather than {@code FAILED}, costing no attempt — but only if
     * the runtime can tell it apart from the work failing. An adapter leaking its own transport
     * exception here would silently turn every evicted pod into a consumed retry.
     */
    @Test
    @DisplayName("reports a vanished sandbox as SessionLost and nothing else")
    void aLostSandboxIsNormalised() {
        HarnessSession session = harness().open(spec());
        induceSessionLoss(session);

        assertThatThrownBy(() -> harness().run(session, new Instruction("SPIN", 10), event -> {}))
                .isInstanceOf(HarnessException.SessionLost.class);
    }

    /**
     * The same normalisation, for a sandbox that dies while work is happening in it.
     *
     * <p>Separate from the test above, and not redundant with it, because the two are caught by
     * different code: a session already gone is refused on the way in, while one that disappears
     * mid-run has to be noticed by the loop itself. Neutralising the loop's check left the other
     * test green, which is the only reason this one exists — and evicted pods, drained nodes and
     * OOM kills all land here rather than there.
     */
    @Test
    @DisplayName("reports a sandbox that dies mid-run as SessionLost too")
    void aSandboxLostDuringWorkIsNormalised() throws Exception {
        HarnessSession session = harness().open(spec());
        CountDownLatch started = new CountDownLatch(1);

        CompletableFuture<HarnessStop> running = CompletableFuture.supplyAsync(
                () -> harness().run(session, new Instruction("SPIN", 100_000), event -> started.countDown()));

        assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
        induceSessionLoss(session);

        // Bounded rather than join(): an adapter that never notices its sandbox died would otherwise
        // wedge the build instead of failing it. A conformance suite has to survive the thing it is
        // testing for — this exact case hung CI once already.
        assertThatThrownBy(() -> running.get(10, TimeUnit.SECONDS))
                .hasRootCauseInstanceOf(HarnessException.SessionLost.class);
    }

    @Test
    @DisplayName("refuses to provision when there is no room, in a way the scheduler can act on")
    void capacityRefusalIsAnAnswerNotAnError() {
        induceCapacityExhaustion();

        assertThatThrownBy(() -> harness().open(spec()))
                .isInstanceOf(HarnessException.CapacityExhausted.class);
    }

    @Test
    @DisplayName("refuses a spec it cannot satisfy without burning a retry on it")
    void anImpossibleSpecIsRejectedDistinctly() {
        AttemptSpec impossible = new AttemptSpec(
                UUID.randomUUID(),
                "someone-elses/image:latest",
                new AttemptSpec.WorkingCopy("/workspace", "abc123"),
                limits(),
                EgressPolicy.DENY_ALL,
                Set.of("read_file"));

        assertThatThrownBy(() -> harness().open(impossible)).isInstanceOf(HarnessException.SpecRejected.class);
    }

    /**
     * §15 requires the allowlist be enforced at dispatch, not only at offer time.
     *
     * <p>Offering-only is insufficient because models invent tool names. A harness that merely omits
     * a tool from the schema will still be asked for it eventually, and what it does then is the
     * whole question.
     */
    @Test
    @DisplayName("refuses a tool outside the attempt's allowlist")
    void toolsOutsideTheAllowlistAreRefused() {
        AttemptSpec readOnly = new AttemptSpec(
                UUID.randomUUID(),
                knownImage(),
                new AttemptSpec.WorkingCopy("/workspace", "abc123"),
                limits(),
                EgressPolicy.DENY_ALL,
                Set.of("read_file"));
        HarnessSession session = harness().open(readOnly);
        var events = new RecordedEvents();

        harness().run(session, new Instruction("EDIT:forbidden.txt", 5), events);

        assertThat(events.all())
                .filteredOn(HarnessEvent.ToolCallCompleted.class::isInstance)
                .anySatisfy(event -> assertThat(((HarnessEvent.ToolCallCompleted) event).failed())
                        .isTrue());
        assertThat(harness().captureDiff(session)).doesNotContain("forbidden.txt");
        harness().close(session);
    }

    @Test
    @DisplayName("closing twice is not an error")
    void closeIsIdempotent() {
        HarnessSession session = harness().open(spec());
        harness().close(session);

        assertThatCode(() -> harness().close(session)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("pausing something that is not running is not an error")
    void pauseIsHarmlessWhenIdle() {
        HarnessSession session = harness().open(spec());

        assertThatCode(() -> harness().pause(session)).doesNotThrowAnyException();
        harness().close(session);
        // A person cancelling a task cannot know the sandbox died a moment ago, and must not have to.
        assertThatCode(() -> harness().pause(session)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("an agent that asks for a person stops and says so")
    void askingForAHumanStopsTheRun() {
        HarnessSession session = harness().open(spec());
        var events = new RecordedEvents();

        HarnessStop stop = harness().run(session, new Instruction("ASK:should I bump the major version?", 10), events);

        assertThat(stop.reason()).isEqualTo(StopReason.AWAITING_HUMAN);
        assertThat(events.all()).anyMatch(HarnessEvent.HumanDecisionRequested.class::isInstance);
        harness().close(session);
    }

    @Test
    @DisplayName("a sandbox that changed nothing produces an empty diff, not a fabricated one")
    void nothingChangedMeansNoDiff() {
        HarnessSession session = harness().open(spec());

        harness().run(session, new Instruction("read the code", 5), event -> {});

        assertThat(harness().captureDiff(session)).isEmpty();
        harness().close(session);
    }

    // -------------------------------------------------------------------------------------------

    private AttemptSpec spec() {
        return new AttemptSpec(
                UUID.randomUUID(),
                knownImage(),
                new AttemptSpec.WorkingCopy("/workspace", "abc123"),
                limits(),
                EgressPolicy.DENY_ALL,
                Set.of("read_file", "apply_patch"));
    }

    private static ResourceLimits limits() {
        return new ResourceLimits(2000, 4096, 8192, Duration.ofMinutes(30), 100_000, 500_000);
    }
}

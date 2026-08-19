package dev.tushar.forgestack.harness;

import dev.tushar.forgestack.platform.Port;
import java.util.function.Consumer;

/**
 * Somewhere a model can read a repository, change it, and run its tests.
 *
 * <p>ForgeStack does not implement this and does not intend to. Appendix B's finding was that the
 * inner loop — tool dispatch, context compaction, sandbox lifecycle, provider plumbing — is the one
 * part of the plan somebody else has already spent eighteen months tuning, and that the product is
 * everything around it. What this interface protects is the ability to be wrong about <em>which</em>
 * somebody else.
 *
 * <p><strong>Reading the method list, notice what the runtime cannot ask.</strong> There is no
 * {@code isComplete}, no {@code succeeded}, no verdict of any kind. {@link #run} returns a
 * {@link HarnessStop}, whose vocabulary tops out at "the agent stopped working on this". Whether the
 * repository is now in an acceptable state is decided afterwards by guards reading committed rows,
 * and a harness gets no vote. Appendix B names that boundary as the one whose failure would mean
 * building the inner loop after all; expressing it in the type system is how it stops being a thing
 * to remember.
 *
 * <p><strong>And notice what it is never given.</strong> {@link AttemptSpec} has no credential field.
 * Both candidate harnesses would rather be handed a token and left to push their own branches, and
 * the adapter's job is to decline: ForgeStack clones host-side, collects the diff through
 * {@link #captureDiff}, and commits from the control plane where the token has stayed the whole time.
 *
 * <p>Implementations must be safe for {@link #pause} and {@link #close} to be called from another
 * thread while {@link #run} is in flight — that is the only way a person cancelling a task, or a
 * lease lapsing under a worker, can stop work that is already happening.
 */
@Port("the harness bake-off is unrun (Appendix B) — an in-memory fake and a conformance suite exist "
        + "so that choosing OpenHands or the Claude Agent SDK stays a swap rather than a rewrite")
public interface ExecutionHarness {

    /** Which harness this is, matching {@link HarnessSession#harness}. */
    String name();

    /**
     * Provisions a sandbox and an agent, and returns a handle to it.
     *
     * @throws HarnessException.CapacityExhausted if there is nowhere to run it right now — an answer
     *     the caller must handle, not an error it may log and forget
     * @throws HarnessException.SpecRejected if the spec asks for something this harness cannot give
     */
    HarnessSession open(AttemptSpec spec);

    /**
     * Works on one instruction until the agent stops, streaming events as they happen.
     *
     * <p>Blocking, and interruptible: {@link #pause} from another thread makes this return with
     * {@link StopReason#PAUSED} rather than throwing. Every event is delivered to {@code sink} before
     * this returns, so a caller that persists steps from the sink has them all by the time it sees
     * the stop.
     *
     * <p>A slow {@code sink} slows the run. That is deliberate — the alternative is dropping events
     * under load, and an audit trail with holes in it is worse than a slow one.
     *
     * @throws HarnessException.SessionLost if the sandbox went away mid-run; §20 treats this as
     *     costing no attempt
     */
    HarnessStop run(HarnessSession session, Instruction instruction, Consumer<HarnessEvent> sink);

    /**
     * Asks an in-flight {@link #run} to stop at the next step boundary.
     *
     * <p>At a step boundary, not immediately: a half-applied edit is worse than a slower stop, and
     * every candidate harness structures its loop so that steps are atomic. Returns without waiting;
     * the {@code run} call is what reports the stop.
     *
     * <p>Idempotent, and harmless when nothing is running.
     */
    void pause(HarnessSession session);

    /**
     * Everything the agent changed, as a unified diff against {@link AttemptSpec.WorkingCopy#baseSha}.
     *
     * <p>The only way work leaves a sandbox. It is read by the diff guards (§17) before anything is
     * committed, which is where "made the tests pass" is told apart from "deleted the failing test" —
     * so this runs against a sandbox that still has no credentials and no way to push.
     */
    String captureDiff(HarnessSession session);

    /**
     * Destroys the session and everything in it.
     *
     * <p>Idempotent, and safe on a session that was already lost. A caller in a {@code finally} block
     * cannot know which of those it has, and must not have to.
     */
    void close(HarnessSession session);
}

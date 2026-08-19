package dev.tushar.forgestack.sandbox;

/**
 * The only failures a caller sees from any sandbox, whatever is underneath.
 *
 * <p>§16's table lists this as a real leak: a {@code DockerException} propagating into a runtime
 * {@code catch} block teaches the runtime which substrate it is on, and the port stops being
 * replaceable without anyone editing it. Adapters normalise; the runtime handles exactly these four.
 *
 * <p>Sealed so a fifth cannot appear quietly. Adding one means editing this file, which is where
 * somebody asks whether the runtime knows what to do about it.
 */
public abstract sealed class SandboxException extends RuntimeException {

    protected SandboxException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Nowhere to run this right now.
     *
     * <p>An answer, not an error: the scheduler applies backpressure and the task stays queued
     * without spending an attempt. §16 puts placement behind the port precisely so the runtime is
     * never the thing choosing a host.
     */
    public static final class CapacityExhausted extends SandboxException {
        public CapacityExhausted(String message) {
            super(message, null);
        }
    }

    /**
     * The sandbox is gone.
     *
     * <p>Routine (§20), and {@code ABORTED} rather than {@code FAILED} — it costs no attempt, and the
     * cumulative patch replays onto a fresh one. Worth testing deliberately on Docker by killing a
     * container mid-attempt, because a quiet Docker host will not exercise this for you and a busy
     * Kubernetes cluster will.
     */
    public static final class SandboxLost extends SandboxException {
        public SandboxLost(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** The substrate itself is unhealthy or unreachable. Its failure, not the work's. */
    public static final class SubstrateUnavailable extends SandboxException {
        public SubstrateUnavailable(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * The request was not allowed, or asked for something impossible.
     *
     * <p>Covers a binary outside the allowlist and a path that escapes the workspace, as well as an
     * image that does not exist. Distinct from unavailability because retrying fixes one and never
     * fixes the other, and a runtime that retried an impossible request would burn an attempt budget
     * discovering that.
     */
    public static final class Refused extends SandboxException {
        public Refused(String message) {
            super(message, null);
        }
    }
}

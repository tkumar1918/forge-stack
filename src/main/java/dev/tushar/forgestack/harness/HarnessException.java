package dev.tushar.forgestack.harness;

/**
 * The only failures the runtime is allowed to see from an execution plane.
 *
 * <p>§16 lists the leak this closes: a {@code DockerException} — or a {@code WebSocketException}, or
 * an {@code httpx.ReadTimeout} carried over in a response body — propagating into a runtime
 * {@code catch} block teaches the runtime which substrate it is running on, and the port stops being
 * replaceable without anyone editing it. Adapters normalise to these four, and the runtime handles
 * exactly these four.
 *
 * <p>Sealed so a fifth cannot be added quietly. Adding one means editing this file, which is the
 * point at which somebody asks whether the runtime knows what to do about it.
 */
public abstract sealed class HarnessException extends RuntimeException {

    protected HarnessException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * There is nowhere to run this right now.
     *
     * <p>Not an error — an answer. The scheduler applies backpressure and tries later; the task stays
     * queued and consumes no attempt. §16 puts placement behind the port for this reason: the runtime
     * asks for a sandbox and may be told no, and it must never be the thing choosing a host.
     */
    public static final class CapacityExhausted extends HarnessException {
        public CapacityExhausted(String message) {
            super(message, null);
        }
    }

    /**
     * The session went away mid-run.
     *
     * <p>Routine, and the plan insists on treating it that way: pods are evicted, nodes drain,
     * containers are OOM-killed, and a runtime that only works because that rarely happens breaks on
     * arrival at a busier substrate. §20 makes this {@code ABORTED} rather than {@code FAILED}, so it
     * costs no attempt, and the work is replayed onto a fresh sandbox.
     */
    public static final class SessionLost extends HarnessException {
        public SessionLost(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** The harness is unreachable or unhealthy. Its own failure, not the work's. */
    public static final class HarnessUnavailable extends HarnessException {
        public HarnessUnavailable(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * The spec named something this harness cannot provide — an image, or a tool it does not have.
     *
     * <p>Distinct from unavailability because retrying fixes one and never fixes the other, and a
     * runtime that retried an impossible spec would burn an attempt budget discovering that.
     */
    public static final class SpecRejected extends HarnessException {
        public SpecRejected(String message) {
            super(message, null);
        }
    }
}

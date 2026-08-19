package dev.tushar.forgestack.sandbox;

import java.time.Duration;

/**
 * How a command ended.
 *
 * <p>Carries no output. Everything the command printed has already gone to the consumer given to
 * {@link SandboxProvider#exec}, which is what keeps a chatty build out of this process's heap.
 *
 * @param timedOut true when the timeout killed it, which is a different fact from a non-zero exit and
 *     must stay distinguishable: one means the tests failed, the other means we never found out
 */
public record ExecResult(int exitCode, boolean timedOut, Duration took, long outputBytes) {

    public boolean succeeded() {
        return exitCode == 0 && !timedOut;
    }
}

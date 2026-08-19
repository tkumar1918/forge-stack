package dev.tushar.forgestack.harness;

import java.time.Duration;

/**
 * What one attempt is allowed to consume, in units no substrate owns.
 *
 * <p>Millicores rather than {@code --cpus}, mebibytes rather than a memory string: the adapter
 * translates to Docker flags, Kubernetes resource limits, or a provider's own vocabulary. §16's
 * table lists the alternative as a real trap — resource limits expressed in one substrate's dialect
 * are how a port stops being portable without anyone editing the interface.
 *
 * @param cpuMillis     hundredths of a core; 2000 is two cores
 * @param memoryMib     hard ceiling, not a request — exceeding it kills the sandbox, which §20 treats
 *                      as routine rather than exceptional
 * @param diskMib       the writable layer, which is the one that fills up on a Tuesday night
 * @param wallClock     how long the whole attempt may take before {@link StopReason#BUDGET_EXHAUSTED}
 * @param maxSteps      how many agent steps before the same
 * @param maxModelTokens total tokens across every model call in the attempt, the ceiling that
 *                      actually bounds the invoice (§22)
 */
public record ResourceLimits(
        int cpuMillis, int memoryMib, int diskMib, Duration wallClock, int maxSteps, long maxModelTokens) {

    public ResourceLimits {
        if (cpuMillis <= 0 || memoryMib <= 0 || diskMib <= 0) {
            throw new IllegalArgumentException("resource limits must be positive");
        }
        if (maxSteps <= 0 || maxModelTokens <= 0) {
            throw new IllegalArgumentException("step and token ceilings must be positive");
        }
        if (wallClock == null || wallClock.isZero() || wallClock.isNegative()) {
            throw new IllegalArgumentException("wall clock must be a positive duration");
        }
    }
}

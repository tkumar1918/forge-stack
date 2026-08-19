package dev.tushar.forgestack.harness;

/**
 * How a run ended.
 *
 * <p>Note there is no field for whether the work was correct, and {@link StopReason} has no value
 * that could carry it. This record is evidence handed to guards, never a verdict handed to the state
 * machine.
 *
 * @param reason    why the agent stopped
 * @param detail    a human-readable note for the transition row; never parsed
 * @param stepsRun  how many steps this run consumed, for the attempt's budget
 */
public record HarnessStop(StopReason reason, String detail, int stepsRun) {

    public HarnessStop {
        if (reason == null) {
            throw new IllegalArgumentException("a run that ended ended for a reason");
        }
        if (stepsRun < 0) {
            throw new IllegalArgumentException("steps run cannot be negative");
        }
    }
}

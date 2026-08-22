package dev.tushar.forgestack.tools;

/**
 * What a tool changes, which is what decides where it is allowed to run.
 *
 * <p>Not a description — a control. {@link #WRITE_GITHUB} is the reason this enum exists at all:
 * §15 confines it to {@code SUBMITTING} because that is where the installation token is, and that
 * confinement is enforced off-model. Everything else is contained by the sandbox and needs no phase
 * gating, which §15 tried and removed for making every capability a scheduling problem without
 * adding a control the container did not already provide.
 */
public enum SideEffect {

    /** Reads the working copy. Cannot change anything, so it cannot be got wrong expensively. */
    READ,

    /** Changes the working copy. Contained: the sandbox is destroyed at the end of the attempt. */
    WRITE_SANDBOX,

    /** Runs code. The repository's own code, which is attacker-controlled in the general case. */
    EXEC,

    /**
     * Reaches GitHub with a credential.
     *
     * <p>No tool in this package has it. Host-brokered by §16, so it belongs to the control plane and
     * is named here only so the catalogue can be read against §15's table without a gap in it.
     */
    WRITE_GITHUB
}

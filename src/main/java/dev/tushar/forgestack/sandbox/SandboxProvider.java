package dev.tushar.forgestack.sandbox;

import dev.tushar.forgestack.platform.Port;
import java.util.function.Consumer;

/**
 * Somewhere to run a customer's code that is not this process.
 *
 * <p>Everything ForgeStack does to a working copy goes through here. Note what is <em>absent</em>
 * from the signatures: no host paths, no container ids, no network names, no Docker flags, no image
 * building. A caller cannot learn what it is running on, which is the only way the substrate stays
 * replaceable — §16 argues at length that an interface alone decouples nothing, and that the coupling
 * always turns out to live in the assumptions around it rather than in the method list.
 *
 * <p><strong>The contract is a session with repeated exec, not a job.</strong> Stated because it is
 * the assumption a Kubernetes adapter most easily breaks: {@code Job} is run-to-completion, and a
 * naive port reaches for it and then cannot implement this interface. A pod with a sleep entrypoint
 * satisfies the contract; a Job does not.
 *
 * <p>Implementations must tolerate {@link #destroy} being called from another thread while an
 * {@link #exec} is in flight — that is how a cancelled task, or a lapsed lease, stops work that is
 * already happening.
 */
@Port("a Docker adapter and an in-memory fake today; gVisor, Firecracker or a hosted provider are on "
        + "the extraction path, and the conformance suite is what makes that a measurable task (§16)")
public interface SandboxProvider {

    /** Which provider this is, matching {@link SandboxHandle#provider()}. */
    String name();

    /**
     * @throws SandboxException.CapacityExhausted when there is nowhere to put it — an answer the
     *     caller must handle rather than an error it may log and forget
     * @throws SandboxException.Refused when the image or the spec cannot be satisfied at all
     */
    SandboxHandle provision(SandboxSpec spec);

    /**
     * Runs one command and streams its output.
     *
     * <p>The binary must be in the spec's allowlist; anything else is {@link SandboxException.Refused}
     * without being run. Enforced here, at the boundary, rather than in the tool layer alone —
     * §15 requires the allowlist be applied at dispatch and not only at offer time, because models
     * invent tool names and prompts get edited.
     *
     * @param sink receives output as it arrives; a slow sink slows the command, which is deliberate,
     *     because the alternative is dropping output under load and an audit trail with holes in it
     */
    ExecResult exec(SandboxHandle handle, ExecRequest request, Consumer<OutputChunk> sink);

    /**
     * @param relPath relative to the workspace root. A path escaping it is
     *     {@link SandboxException.Refused} — checked here rather than trusted, because the string
     *     originates with a model
     */
    void writeFile(SandboxHandle handle, String relPath, byte[] content);

    byte[] readFile(SandboxHandle handle, String relPath);

    /**
     * Places a whole working copy at once.
     *
     * <p><strong>Why this exists as well as {@link #writeFile}.</strong> A repository has to arrive
     * in the sandbox somehow, and until this method there was no way for it to. The workspace is a
     * tmpfs <em>inside</em> the container and §16 forbids a host path reaching the adapter, so the
     * host cannot clone into it directly, and {@code docker cp} is refused outright by a read-only
     * rootfs — measured against a real daemon, not assumed. That left one file per call as the only
     * route in.
     *
     * <p>Which does not scale, and the gap is not marginal: 100 files take <strong>8.4 seconds</strong>
     * one at a time and <strong>85ms</strong> in a single call, measured here. A five-thousand-file
     * repository is the difference between seven minutes and four seconds, on every attempt. That
     * measurement is the whole justification for a second method doing what the first already does.
     *
     * <p>Deliberately expressed as files rather than as an archive. An archive is a substrate detail
     * — it would put a wire format in a port whose discipline is that a caller cannot tell what it
     * is running on, and it would move path validation into a tar parser, where every entry arrives
     * as bytes somebody has to remember to be careful about. Names here are checked exactly as
     * {@link #writeFile} checks them.
     *
     * <p>Writes those paths and leaves anything else in the workspace alone. <strong>Not atomic:</strong>
     * a failure part-way through leaves a partial working copy. Safe only because the caller is
     * filling a sandbox it just provisioned, and destroys it rather than repairing it.
     *
     * @param files workspace-relative path to content. Every path is validated before anything is
     *     written, so one escaping entry refuses the whole call rather than only its own file
     * @throws SandboxException.Refused if any path escapes the workspace, or is too long to express
     */
    void writeFiles(SandboxHandle handle, java.util.Map<String, byte[]> files);

    /** Whether the sandbox is still usable, without throwing when the answer is no. */
    HealthState probe(SandboxHandle handle);

    /**
     * Destroys the sandbox and everything in it.
     *
     * <p>Idempotent, and safe on one that is already gone: a caller in a {@code finally} block cannot
     * know which it has. Leaked containers are how a worker VM fills its disk on a Tuesday night.
     */
    void destroy(SandboxHandle handle);
}

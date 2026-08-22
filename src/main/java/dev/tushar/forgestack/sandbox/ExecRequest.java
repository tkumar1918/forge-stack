package dev.tushar.forgestack.sandbox;

import java.time.Duration;
import java.util.List;

/**
 * A command to run: an argument vector, or a shell script.
 *
 * <p><strong>This class used to argue that a shell must never be expressible, and that argument has
 * been withdrawn.</strong> It rested on two claims. The first — that argv is easier to log, attribute
 * and classify — survives, and is why argv is still the default. The second was that "unrestricted
 * shell also makes the sandbox's other controls largely decorative", and that one is false: two
 * containers were built with §16's hardening block and probed directly, and the controls measure
 * identically with a shell present or absent. The boundary is the kernel's, not the parser's.
 *
 * <p>Keeping the restriction after its reason failed was costing real capability. An agent that
 * cannot write {@code mvn test | tail -50}, cannot chain {@code source venv/bin/activate && pytest},
 * and cannot redirect anything is an agent that cannot do ordinary work — and the allowlist it was
 * protecting never contained an adversary anyway: {@code find -exec} escapes it with no shell at all,
 * and {@code npm test} runs arbitrary repository-authored commands but cannot be removed, because
 * running it is the entire point of the verification contract.
 *
 * <p><strong>So the allowlist is an operational contract, not a containment control.</strong> It
 * stays because it catches an agent wandering somewhere pointless and keeps the audit trail narrow.
 * It is written down here as ineffective against an adversary so that nobody later defends it as
 * security, or blocks a feature to preserve it.
 *
 * <p>What replaces the restriction is <em>recording</em>: full command text is audited per attempt,
 * and the text is parsed for risk signals (§17) rather than prefix-matched. Prefix matching on
 * command strings is the approach both this project and Anthropic have found does not work.
 *
 * @param binary  must appear in {@link SandboxSpec#allowedBinaries}. For a script this is the shell
 * @param args    passed through untouched; no interpretation happens on this side
 * @param workDir relative to the workspace root, never an absolute host path
 * @param timeout after which the process is killed and {@link ExecResult#timedOut()} is set
 * @param stdin   fed to the process and then closed, or null to close it immediately. Data a command
 *     reads, never a command an interpreter runs — it exists because {@code git apply} takes its
 *     patch this way, and writing the patch into the workspace first would put a file in the diff
 *     that every §17 guard would then have to know to ignore
 */
public record ExecRequest(String binary, List<String> args, String workDir, Duration timeout, byte[] stdin) {

    /** The shell a {@link #script} runs in. In the allowlist like any other binary, and refused like one. */
    public static final String SHELL = "sh";

    public ExecRequest {
        if (binary == null || binary.isBlank()) {
            throw new IllegalArgumentException("a command needs something to run");
        }
        args = List.copyOf(args);
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("a command without a timeout is a command that never ends");
        }
    }

    public ExecRequest(String binary, List<String> args, String workDir, Duration timeout) {
        this(binary, args, workDir, timeout, null);
    }

    public static ExecRequest of(String binary, List<String> args, Duration timeout) {
        return new ExecRequest(binary, args, ".", timeout, null);
    }

    /**
     * One shell script, with pipes, redirection and chaining available.
     *
     * <p>Not a privileged form of {@link #of}: it resolves to {@code sh -c <script>}, so the sandbox
     * refuses it exactly as it refuses any other binary that is not permitted for this attempt. What
     * makes it worth a named factory is that the caller's <em>intent</em> is recorded — a script is
     * audited and parsed as a script, rather than being discovered later inside somebody's argv.
     */
    public static ExecRequest script(String script, Duration timeout) {
        if (script == null || script.isBlank()) {
            throw new IllegalArgumentException("a script has to say something");
        }
        return new ExecRequest(SHELL, List.of("-c", script), ".", timeout, null);
    }

    /** The same command, with something on its standard input. */
    public ExecRequest reading(byte[] input) {
        return new ExecRequest(binary, args, workDir, timeout, input);
    }
}

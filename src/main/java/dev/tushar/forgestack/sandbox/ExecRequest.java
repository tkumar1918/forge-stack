package dev.tushar.forgestack.sandbox;

import java.time.Duration;
import java.util.List;

/**
 * A command to run, as an argument vector.
 *
 * <p><strong>A list, never a string, and this is the whole of §15's shell rule.</strong> There is no
 * field here that could hold {@code sh -c}, no pipe, no {@code &&}, no redirection and no glob,
 * because the argv is handed to the kernel rather than to a shell. That is not primarily about
 * stopping a malicious model — it is what makes every command reproducible, attributable and
 * analysable, and it is what stops the sandbox's other controls from being decorative.
 *
 * <p>The cost is real and worth naming: an agent that would have written {@code mvn test | tail -50}
 * has to run {@code mvn test} and read the output through a tool. Whether that costs resolution rate
 * is an open question the plan now says out loud rather than assuming.
 *
 * @param binary  must appear in {@link SandboxSpec#allowedBinaries}
 * @param args    passed through untouched; no interpretation happens anywhere
 * @param workDir relative to the workspace root, never an absolute host path
 * @param timeout after which the process is killed and {@link ExecResult#timedOut()} is set
 * @param stdin   fed to the process and then closed, or null to close it immediately. <strong>Not a
 *     way around the argv rule:</strong> stdin is data the command reads, never a command the shell
 *     runs, and nothing here reaches an interpreter. It exists because {@code git apply} takes its
 *     patch this way, and the alternative — writing the patch into the workspace first — puts a file
 *     in the diff that the §17 guards then have to read around, which is worse than a byte array
 */
public record ExecRequest(String binary, List<String> args, String workDir, Duration timeout, byte[] stdin) {

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

    /** The same command, with something on its standard input. */
    public ExecRequest reading(byte[] input) {
        return new ExecRequest(binary, args, workDir, timeout, input);
    }
}

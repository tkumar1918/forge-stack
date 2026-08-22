package dev.tushar.forgestack.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What every sandbox has to do, whatever is underneath it.
 *
 * <p>§16 is explicit that this suite is the point: "a future k8s or gVisor adapter then has an
 * executable specification instead of a prose one — that is what turns 'swap the adapter' from a
 * claim into a measurable task." It runs unchanged against the in-memory fake and against real
 * Docker containers, and a third adapter inherits it by extending this class.
 *
 * <p>Written against {@link SandboxProvider} only. If anything here needs to know which substrate it
 * is testing, the port is leaking and the fix belongs in the port rather than here.
 */
abstract class SandboxProviderContract {

    protected abstract SandboxProvider provider();

    /** An image this provider can run, containing a shell and the usual coreutils. */
    protected abstract String knownImage();

    private final List<SandboxHandle> provisioned = new ArrayList<>();

    @AfterEach
    void destroyEverything() {
        provisioned.forEach(handle -> {
            try {
                provider().destroy(handle);
            } catch (RuntimeException ignored) {
                // Already gone is the outcome we wanted.
            }
        });
        provisioned.clear();
    }

    @Test
    @DisplayName("provisions something alive, and destroys it when asked")
    void theLifecycle() {
        SandboxHandle handle = provision();

        assertThat(provider().probe(handle)).isEqualTo(HealthState.ALIVE);

        provider().destroy(handle);
        assertThat(provider().probe(handle)).isEqualTo(HealthState.GONE);
    }

    @Test
    @DisplayName("runs a permitted command and streams what it printed")
    void execRunsAndStreams() {
        SandboxHandle handle = provision();
        var output = new StringBuilder();

        ExecResult result = provider()
                .exec(handle, ExecRequest.of("echo", List.of("hello-forge"), Duration.ofSeconds(30)), collect(output));

        assertThat(result.succeeded()).isTrue();
        assertThat(result.exitCode()).isZero();
        assertThat(output.toString()).contains("hello-forge");
        assertThat(result.outputBytes()).isPositive();
    }

    /**
     * A command that fails is not an exception.
     *
     * <p>Failing tests are the ordinary case in this system — the entire product exists because tests
     * fail — so a non-zero exit has to be data the runtime reads, not a throw it catches. Conflating
     * the two is how "the tests failed" becomes indistinguishable from "the sandbox broke", and §20
     * spends a retry on one and not the other.
     */
    @Test
    @DisplayName("a failing command reports its exit code rather than throwing")
    void failureIsData() {
        SandboxHandle handle = provision();

        ExecResult result = provider().exec(handle, ExecRequest.of("false", List.of(), Duration.ofSeconds(30)), sink());

        assertThat(result.exitCode()).isNotZero();
        assertThat(result.timedOut()).isFalse();
        assertThat(result.succeeded()).isFalse();
    }

    /**
     * §15's allowlist, enforced where it cannot be argued with.
     *
     * <p>Offering a smaller tool set is not enough on its own — models invent names, and a prompt is
     * edited more often than a security boundary. The binary has to be refused at the point of
     * execution, and refused <em>without running</em>.
     */
    @Test
    @DisplayName("refuses a binary outside the allowlist, without running it")
    void theAllowlistIsEnforcedAtExec() {
        SandboxHandle handle = provision();

        assertThatThrownBy(() -> provider()
                        .exec(handle, ExecRequest.of("rm", List.of("-rf", "/"), Duration.ofSeconds(30)), sink()))
                .isInstanceOf(SandboxException.Refused.class);
    }

    @Test
    @DisplayName("kills a command that runs past its timeout, and says that is what happened")
    void timeoutIsDistinctFromFailure() {
        SandboxHandle handle = provision();

        ExecResult result =
                provider().exec(handle, ExecRequest.of("sleep", List.of("30"), Duration.ofSeconds(2)), sink());

        assertThat(result.timedOut())
                .as("a timeout means we never found out, which is not the same as the tests failing")
                .isTrue();
        assertThat(result.succeeded()).isFalse();
    }

    @Test
    @DisplayName("a file written is a file read back")
    void filesRoundTrip() {
        SandboxHandle handle = provision();

        provider().writeFile(handle, "src/Main.java", "class Main {}\n".getBytes(StandardCharsets.UTF_8));

        assertThat(new String(provider().readFile(handle, "src/Main.java"), StandardCharsets.UTF_8))
                .contains("class Main {}");
    }

    /**
     * The path comes from a model, so it is hostile input.
     *
     * <p>Both forms matter: the obvious one and the one that normalises into an escape. A check
     * written as {@code startsWith("..")} passes the second and is the version most people write.
     */
    @Test
    @DisplayName("refuses a path that escapes the workspace, however it is spelled")
    void pathsCannotEscape() {
        SandboxHandle handle = provision();
        byte[] content = "pwned".getBytes(StandardCharsets.UTF_8);

        for (String escape : List.of("../etc/passwd", "a/../../etc/passwd", "/etc/passwd")) {
            assertThatThrownBy(() -> provider().writeFile(handle, escape, content))
                    .as("write %s", escape)
                    .isInstanceOf(SandboxException.Refused.class);
            assertThatThrownBy(() -> provider().readFile(handle, escape))
                    .as("read %s", escape)
                    .isInstanceOf(SandboxException.Refused.class);
        }
    }

    /**
     * A working copy arrives, which until {@code writeFiles} existed it had no way to do.
     *
     * <p>The nested path is the load-bearing part. Nothing creates {@code src/main/java} before the
     * file inside it is written, so this asserts that a substrate materialises missing parents —
     * relied on by the Docker adapter, which emits no directory entries at all, and true of the fake
     * only because a map has no directories to miss.
     */
    @Test
    @DisplayName("a whole working copy arrives in one call, parents and all")
    void aWorkingCopyIsPlacedInOneCall() {
        SandboxHandle handle = provision();

        provider()
                .writeFiles(
                        handle,
                        Map.of(
                                "README.md", "# fixture\n".getBytes(StandardCharsets.UTF_8),
                                "src/main/java/Main.java", "class Main {}\n".getBytes(StandardCharsets.UTF_8),
                                "src/test/java/MainTest.java", "class MainTest {}\n".getBytes(StandardCharsets.UTF_8)));

        assertThat(new String(provider().readFile(handle, "src/main/java/Main.java"), StandardCharsets.UTF_8))
                .contains("class Main {}");
        assertThat(new String(provider().readFile(handle, "README.md"), StandardCharsets.UTF_8))
                .contains("# fixture");
    }

    /**
     * One bad entry refuses the batch, rather than the file it appears in.
     *
     * <p>The alternative — write what is valid, refuse the rest — leaves a working copy that is
     * neither what was asked for nor empty, and a caller cannot tell it from a substrate that failed
     * half way. The escape is the second spelling on purpose: {@code a/../../etc/passwd} normalises
     * into one and is the version a {@code startsWith("..")} check waves through.
     */
    @Test
    @DisplayName("one escaping path refuses the whole working copy")
    void anEscapingEntryRefusesEverything() {
        SandboxHandle handle = provision();
        Map<String, byte[]> mixed = new java.util.LinkedHashMap<>();
        mixed.put("innocent.txt", "fine".getBytes(StandardCharsets.UTF_8));
        mixed.put("a/../../etc/passwd", "pwned".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> provider().writeFiles(handle, mixed)).isInstanceOf(SandboxException.Refused.class);

        assertThatThrownBy(() -> provider().readFile(handle, "innocent.txt"))
                .as("the valid entry must not have landed either")
                .isInstanceOf(SandboxException.class);
    }

    @Test
    @DisplayName("placing nothing is not an error")
    void anEmptyWorkingCopyIsANoOp() {
        SandboxHandle handle = provision();

        assertThatCode(() -> provider().writeFiles(handle, Map.of())).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("destroying twice is not an error")
    void destroyIsIdempotent() {
        SandboxHandle handle = provision();
        provider().destroy(handle);

        assertThatCode(() -> provider().destroy(handle)).doesNotThrowAnyException();
    }

    /**
     * Losing a sandbox is routine, and has to arrive as the one exception the runtime handles.
     *
     * <p>§20 makes this {@code ABORTED} rather than {@code FAILED}, costing no attempt — but only if
     * the runtime can tell it apart from the work failing. An adapter leaking its own transport
     * exception here turns every evicted pod into a consumed retry.
     */
    @Test
    @DisplayName("using a sandbox that is gone reports SandboxLost and nothing else")
    void aLostSandboxIsNormalised() {
        SandboxHandle handle = provision();
        provider().destroy(handle);

        assertThatThrownBy(() -> provider()
                        .exec(handle, ExecRequest.of("echo", List.of("hi"), Duration.ofSeconds(10)), sink()))
                .isInstanceOf(SandboxException.SandboxLost.class);
    }

    @Test
    @DisplayName("refuses an image it cannot run rather than retrying forever")
    void anUnknownImageIsRefused() {
        SandboxSpec impossible = spec("forgestack/definitely-not-an-image:v0");

        assertThatThrownBy(() -> provider().provision(impossible)).isInstanceOf(SandboxException.Refused.class);
    }

    // -------------------------------------------------------------------------------------------

    protected SandboxHandle provision() {
        SandboxHandle handle = provider().provision(spec(knownImage()));
        provisioned.add(handle);
        return handle;
    }

    protected SandboxSpec spec(String image) {
        return new SandboxSpec(
                UUID.randomUUID(),
                UUID.randomUUID(),
                image,
                2000,
                512,
                256,
                Duration.ofMinutes(5),
                EgressPolicy.DENY_ALL,
                // env and wget are here so the Docker suite can prove what is *absent* from the
                // sandbox -- no credentials in the environment, no route to the network. A binary
                // that proves a negative still has to be allowed to run.
                Set.of("echo", "false", "sleep", "sh", "cat", "git", "id", "touch", "env", "wget"));
    }

    private static Consumer<OutputChunk> sink() {
        return chunk -> {};
    }

    private static Consumer<OutputChunk> collect(StringBuilder into) {
        return chunk -> into.append(new String(chunk.bytes(), StandardCharsets.UTF_8));
    }
}

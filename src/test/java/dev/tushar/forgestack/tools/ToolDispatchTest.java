package dev.tushar.forgestack.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.tushar.forgestack.sandbox.DockerSandboxProvider;
import dev.tushar.forgestack.sandbox.EgressPolicy;
import dev.tushar.forgestack.sandbox.SandboxHandle;
import dev.tushar.forgestack.sandbox.SandboxSpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The tool layer against a real container, because the refusals are the point.
 *
 * <p>Run against the in-memory fake these tests would prove almost nothing: the fake interprets three
 * commands and answers everything else with success, so a tool that assembled the wrong argument
 * vector, or a path check that never fired, would pass. What is being tested here is mostly what does
 * <em>not</em> happen, and a negative asserted against a fake that could not have done it either is
 * not evidence.
 *
 * <p>Everything here is driven by a test rather than by a model, which is the same ordering §27
 * applies everywhere: the dispatch pipeline is proven before anything unpredictable is calling it.
 */
class ToolDispatchTest {

    private static final String TOOLCHAIN = "forgestack/java-21:latest";

    /** What a normal attempt is offered. Narrower sets are built inline where that is the point. */
    private static final Set<String> EVERYTHING = ToolCatalogue.names();

    private final DockerSandboxProvider docker = new DockerSandboxProvider(Duration.ofSeconds(120));
    private final ToolDispatch dispatch = new ToolDispatch(docker);
    private SandboxHandle sandbox;

    @BeforeAll
    static void theToolchainImageExists() {
        assumeTrue(dockerResponds(), "docker is not available on this machine");
        assumeTrue(imageExists(), "run scripts/build-sandbox-image.sh to build " + TOOLCHAIN);
    }

    @BeforeEach
    void aSandboxWithAWorkingCopy() {
        sandbox = docker.provision(new SandboxSpec(
                UUID.randomUUID(),
                UUID.randomUUID(),
                TOOLCHAIN,
                2000,
                512,
                256,
                Duration.ofMinutes(5),
                EgressPolicy.DENY_ALL,
                // Built from the catalogue rather than typed out, so a tool reaching for a new binary
                // cannot pass here while failing for a real attempt configured the same way.
                union(ToolDispatch.binariesUsed(), Set.of("python3"))));
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("calc.py", utf8("def add(a, b):\n    return a - b\n"));
        files.put("README.md", utf8("# fixture\nsentinel-string\n"));
        docker.writeFiles(sandbox, files);
    }

    @AfterEach
    void destroyIt() {
        if (sandbox != null) {
            docker.destroy(sandbox);
        }
    }

    // --- step 1, resolve -------------------------------------------------------------------------

    /**
     * §15 requires unknown names be refused at dispatch, not merely withheld from the offer.
     *
     * <p>Withholding alone is insufficient because models invent tool names. What a system does when
     * asked for a tool that does not exist is the whole question, and "nothing, silently" is the
     * answer that produces an agent looping on a capability it believes it has.
     */
    @Test
    @DisplayName("a tool that does not exist is refused, in words the model can act on")
    void anInventedToolIsRefused() {
        assertThatThrownBy(() -> dispatch.dispatch(sandbox, EVERYTHING, ToolCall.of("delete_everything")))
                .isInstanceOf(ToolRefusal.class)
                .hasMessageContaining("there is no tool called 'delete_everything'");
    }

    /**
     * A real tool, withheld from this attempt, refused differently from one that does not exist.
     *
     * <p>The distinction is for the model rather than for us: "no such tool" and "not this time" call
     * for different corrections, and collapsing them leaves a model unable to tell a typo from a
     * restriction.
     */
    @Test
    @DisplayName("a real tool that was not offered is refused, and says so distinctly")
    void aWithheldToolIsRefusedDistinctly() {
        Set<String> readOnly = Set.of("read_file", "grep");

        assertThatThrownBy(() ->
                        dispatch.dispatch(sandbox, readOnly, ToolCall.of("write_file", "path", "x", "content", "y")))
                .isInstanceOf(ToolRefusal.class)
                .hasMessageContaining("not available for this attempt");
    }

    // --- step 2, validate ------------------------------------------------------------------------

    @Test
    @DisplayName("a missing argument is refused rather than defaulted")
    void aMissingArgumentIsRefused() {
        assertThatThrownBy(() -> dispatch.dispatch(sandbox, EVERYTHING, ToolCall.of("read_file")))
                .isInstanceOf(ToolRefusal.class)
                .hasMessageContaining("needs the argument 'path'");
    }

    /**
     * §15 says reject on validation failure and do not coerce, and this is the case that tempts.
     *
     * <p>{@code toString()} on whatever arrived would make this call "work" -- it would read a file
     * named {@code 42} and report it missing, and the record would show a model that asked for a file
     * rather than a model that sent the wrong type.
     */
    @Test
    @DisplayName("an argument of the wrong type is refused, not converted")
    void aWrongTypedArgumentIsNotCoerced() {
        assertThatThrownBy(() -> dispatch.dispatch(sandbox, EVERYTHING, ToolCall.of("read_file", "path", 42)))
                .isInstanceOf(ToolRefusal.class)
                .hasMessageContaining("must be text");
    }

    /**
     * The path came from a model, so the workspace jail has to hold at the tool layer too.
     *
     * <p>The sandbox checks this as well, and that duplication is the design: this is the check, and
     * the sandbox is the backstop for the day this one is wrong.
     */
    @Test
    @DisplayName("a path escaping the workspace is refused, however it is spelled")
    void pathsCannotEscape() {
        for (String escape : List.of("../etc/passwd", "a/../../etc/passwd", "/etc/passwd")) {
            assertThatThrownBy(() -> dispatch.dispatch(sandbox, EVERYTHING, ToolCall.of("read_file", "path", escape)))
                    .as("read %s", escape)
                    .isInstanceOf(ToolRefusal.class);
            assertThatThrownBy(() -> dispatch.dispatch(
                            sandbox, EVERYTHING, ToolCall.of("write_file", "path", escape, "content", "pwned")))
                    .as("write %s", escape)
                    .isInstanceOf(ToolRefusal.class);
        }
    }

    // --- step 5, execute -------------------------------------------------------------------------

    @Test
    @DisplayName("reads a file, lists a directory, and finds a string")
    void theReadingToolsWork() {
        assertThat(dispatch.dispatch(sandbox, EVERYTHING, ToolCall.of("read_file", "path", "calc.py")).output())
                .contains("return a - b");

        assertThat(dispatch.dispatch(sandbox, EVERYTHING, ToolCall.of("list_directory", "path", ".")).output())
                .contains("calc.py")
                .contains("README.md");

        ToolResult found = dispatch.dispatch(sandbox, EVERYTHING, ToolCall.of("grep", "pattern", "sentinel-string"));
        assertThat(found.failed()).isFalse();
        assertThat(found.output()).contains("README.md");

        assertThat(dispatch.dispatch(sandbox, EVERYTHING, ToolCall.of("find_files", "pattern", "*.py")).output())
                .contains("calc.py");
    }

    /**
     * Finding nothing is an answer, not a failure.
     *
     * <p>grep exits 1 when it matches nothing, which is the same shape as grep failing. A model told
     * its search "failed" will retry the search; a model told the search found nothing will look
     * somewhere else, and only one of those is progress.
     */
    @Test
    @DisplayName("a search that matches nothing reports nothing, not an error")
    void anEmptySearchIsNotAFailure() {
        ToolResult result = dispatch.dispatch(sandbox, EVERYTHING, ToolCall.of("grep", "pattern", "not-in-this-repo"));

        assertThat(result.failed())
                .as("grep exits 1 when it matches nothing, which is not the same as grep failing")
                .isFalse();
        assertThat(result.output()).isEmpty();
    }

    @Test
    @DisplayName("writes a file, and reads back exactly what was written")
    void writingRoundTrips() {
        dispatch.dispatch(
                sandbox,
                EVERYTHING,
                ToolCall.of("write_file", "path", "src/new/thing.py", "content", "VALUE = 1\n"));

        assertThat(dispatch
                        .dispatch(sandbox, EVERYTHING, ToolCall.of("read_file", "path", "src/new/thing.py"))
                        .output())
                .isEqualTo("VALUE = 1\n");
    }

    /**
     * The preferred edit tool, applied through stdin.
     *
     * <p>The patch never becomes a file in the workspace, which matters beyond tidiness: §17's guards
     * read the diff, and a scaffolding file inside it is something every guard would have to know to
     * ignore.
     */
    @Test
    @DisplayName("applies a unified diff without leaving the patch behind in the diff")
    void aPatchApplies() {
        String patch =
                """
                --- a/calc.py
                +++ b/calc.py
                @@ -1,2 +1,2 @@
                 def add(a, b):
                -    return a - b
                +    return a + b
                """;

        ToolResult applied = dispatch.dispatch(sandbox, EVERYTHING, ToolCall.of("apply_patch", "patch", patch));

        assertThat(applied.failed()).isFalse();
        assertThat(dispatch.dispatch(sandbox, EVERYTHING, ToolCall.of("read_file", "path", "calc.py")).output())
                .contains("return a + b");
        assertThat(dispatch.dispatch(sandbox, EVERYTHING, ToolCall.of("list_directory", "path", ".")).output())
                .as("the patch must not have been written to disk to be applied")
                .doesNotContain(".patch");
    }

    /**
     * §15's reason for preferring a patch over a whole-file write.
     *
     * <p>A patch whose context does not match is the model's mental model having drifted from the
     * file, and it is worth surfacing as that rather than as a non-zero exit the model may retry
     * blindly. A whole-file write in the same situation succeeds and silently discards whatever the
     * model had not accounted for.
     */
    @Test
    @DisplayName("a patch that does not match fails loudly, which is the reason to prefer patches")
    void aPatchAgainstStaleContentIsRejected() {
        String stale =
                """
                --- a/calc.py
                +++ b/calc.py
                @@ -1,2 +1,2 @@
                 def multiply(a, b):
                -    return a * b
                +    return a ** b
                """;

        ToolResult result = dispatch.dispatch(sandbox, EVERYTHING, ToolCall.of("apply_patch", "patch", stale));

        assertThat(result.failed()).isTrue();
        assertThat(result.output()).contains("the patch did not apply");
        assertThat(dispatch.dispatch(sandbox, EVERYTHING, ToolCall.of("read_file", "path", "calc.py")).output())
                .as("a rejected patch changes nothing")
                .contains("return a - b");
    }

    @Test
    @DisplayName("runs a command and reports a failing one as data rather than an exception")
    void runCommandReportsFailureAsData() {
        ToolResult passing = dispatch.dispatch(
                sandbox, EVERYTHING, ToolCall.of("run_command", "argv", List.of("python3", "-c", "print('ok')")));
        assertThat(passing.failed()).isFalse();
        assertThat(passing.output()).contains("ok");

        ToolResult failing = dispatch.dispatch(
                sandbox, EVERYTHING, ToolCall.of("run_command", "argv", List.of("python3", "-c", "raise SystemExit(3)")));
        assertThat(failing.failed()).isTrue();
    }

    /**
     * The sandbox's binary allowlist, reached through the tool layer.
     *
     * <p>{@code run_command} is the one tool whose binary a model chooses, so it is the one place the
     * §15 allowlist is actually load-bearing at run time. Refused by the sandbox rather than by the
     * dispatch, which is the backstop working.
     */
    @Test
    @DisplayName("a command outside the sandbox's allowlist is refused, not run")
    void anUnpermittedBinaryIsRefused() {
        assertThatThrownBy(() -> dispatch.dispatch(
                        sandbox, EVERYTHING, ToolCall.of("run_command", "argv", List.of("rm", "-rf", "/"))))
                .isInstanceOf(ToolRefusal.class)
                .hasMessageContaining("allowlist");
    }

    // --- step 7, truncate ------------------------------------------------------------------------

    /**
     * §11's context-window killer, bounded.
     *
     * <p>Both ends are kept because a build log puts its cause at the top and its verdict at the
     * bottom, and a head-only truncation is how a model reads a compilation error and never learns
     * the suite failed. The total length is still reported, so the model can tell it is reading an
     * extract.
     */
    @Test
    @DisplayName("a huge result is capped at both ends, and says how much was left out")
    void hugeOutputIsTruncatedAtBothEnds() {
        ToolResult result = dispatch.dispatch(
                sandbox,
                EVERYTHING,
                ToolCall.of(
                        "run_command",
                        "argv",
                        List.of("python3", "-c", "print('HEAD'); print('x' * 200000); print('TAIL')")));

        assertThat(result.truncated()).isTrue();
        assertThat(result.output().length()).isLessThanOrEqualTo(ToolDispatch.MAX_OUTPUT_CHARS + 200);
        assertThat(result.outputBytes()).isGreaterThan(200_000);
        assertThat(result.output()).startsWith("HEAD").contains("characters omitted").endsWith("TAIL\n");
    }

    // -------------------------------------------------------------------------------------------

    private static Set<String> union(Set<String> a, Set<String> b) {
        return java.util.stream.Stream.concat(a.stream(), b.stream()).collect(Collectors.toUnmodifiableSet());
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static boolean dockerResponds() {
        return commandSucceeds("docker", "version", "--format", "{{.Server.Version}}");
    }

    private static boolean imageExists() {
        return commandSucceeds("docker", "image", "inspect", TOOLCHAIN);
    }

    private static boolean commandSucceeds(String... command) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            process.getInputStream().readAllBytes();
            return process.waitFor() == 0;
        } catch (Exception e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}

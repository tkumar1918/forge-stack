package dev.tushar.forgestack.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A real repository, worked on in a real container, start to finish.
 *
 * <p>Everything else in this package tests the sandbox as a mechanism — a command runs, a flag is
 * applied, a path is refused. This one asks the question the mechanism exists for: <strong>can the
 * substrate actually host a piece of work?</strong> Code goes in, its tests fail for a real reason,
 * an edit lands, the tests pass, and a diff of exactly what changed comes back out. That sequence is
 * Phase 3's exit criterion in miniature, and until it ran, every part of it was a separate claim.
 *
 * <p>It is deliberately not driven by a model. §27's ordering principle is that the mechanical
 * substrate is proven before the first LLM call, so that when the agent later misbehaves it is
 * known to be the prompt rather than the container, the diff, or the test runner. The instruction
 * here is a hardcoded edit for exactly that reason.
 *
 * <p><strong>Python, in a Java product, on purpose.</strong> The fixture's test runner is in its
 * standard library, so it needs nothing downloaded — and containers run {@code --network none}
 * until the egress proxy exists. A Gradle or Maven fixture cannot resolve a single dependency in
 * here, which is not a fact about Java but the same fact about the proxy, stated from the other
 * side. The language of the fixture is irrelevant to what is being proven.
 */
class WorkingCopyTest {

    /** Built by {@code scripts/build-sandbox-image.sh}, and named by nothing a model controls. */
    private static final String TOOLCHAIN = "forgestack/java-21:latest";

    private final DockerSandboxProvider docker = new DockerSandboxProvider(Duration.ofSeconds(120));
    private SandboxHandle sandbox;

    @BeforeAll
    static void theToolchainImageExists() {
        assumeTrue(dockerResponds(), "docker is not available on this machine");
        assumeTrue(imageExists(), "run scripts/build-sandbox-image.sh to build " + TOOLCHAIN);
    }

    @AfterEach
    void destroyIt() {
        if (sandbox != null) {
            docker.destroy(sandbox);
        }
    }

    @Test
    @DisplayName("code goes in, its tests fail, an edit fixes them, and the diff comes back out")
    void aPieceOfWorkFromEndToEnd() {
        sandbox = docker.provision(spec());

        // 1. The working copy arrives. Before writeFiles existed there was no way for it to.
        docker.writeFiles(sandbox, fixture());

        // 2. A baseline commit, so a diff has something to be a diff against. Local git only: the
        //    sandbox has no remote, no credential, and no network to use either with (§16).
        runOk("git", "init", "-q", ".");
        runOk("git", "add", "-A");
        runOk("git", "commit", "-q", "-m", "baseline");

        // 3. The tests fail, and they fail for the reason the fixture is wrong rather than because
        //    the runner is missing. Asserting the message is what tells those two apart -- a broken
        //    image also produces a non-zero exit, and would otherwise read as a passing test.
        var before = new StringBuilder();
        ExecResult failing = run(collect(before), "python3", "-m", "unittest", "test_calc");
        assertThat(failing.succeeded()).isFalse();
        assertThat(before.toString()).contains("AssertionError").contains("-1 != 5");

        // 4. The edit. A model's job later; a constant here, so that a failure in this test is a
        //    failure of the substrate and can be nothing else.
        docker.writeFile(sandbox, "calc.py", "def add(a, b):\n    return a + b\n".getBytes(StandardCharsets.UTF_8));

        // 5. Green, from the same runner that was red a moment ago.
        var after = new StringBuilder();
        assertThat(run(collect(after), "python3", "-m", "unittest", "test_calc").succeeded())
                .isTrue();
        assertThat(after.toString()).contains("OK");

        // 6. The only way work leaves a sandbox. This is what the diff guards read, so it has to be
        //    a real unified diff of the real change and not a summary of one.
        var diff = new StringBuilder();
        assertThat(run(collect(diff), "git", "diff").exitCode()).isZero();
        assertThat(diff.toString())
                .contains("calc.py")
                .contains("-    return a - b")
                .contains("+    return a + b");
    }

    /**
     * The test file is untouched, and the diff proves it.
     *
     * <p>§17's whole anti-cheat layer reads {@code captureDiff}'s output, and every one of its guards
     * is a statement about which files appear in it. A diff that quietly omitted the test directory
     * would make those guards pass by having nothing to look at — so what this asserts is that the
     * diff is complete, using the one file whose absence would be indistinguishable from innocence.
     */
    @Test
    @DisplayName("a deleted test shows up in the diff, where the guards can see it")
    void deletingATestIsVisibleInTheDiff() {
        sandbox = docker.provision(spec());
        docker.writeFiles(sandbox, fixture());
        runOk("git", "init", "-q", ".");
        runOk("git", "add", "-A");
        runOk("git", "commit", "-q", "-m", "baseline");

        // The cheapest possible way to make a failing suite green, and the one §17 exists to refuse.
        runOk("git", "rm", "-q", "test_calc.py");

        var diff = new StringBuilder();
        run(collect(diff), "git", "diff", "HEAD");
        assertThat(diff.toString()).contains("test_calc.py").contains("deleted file");
    }

    // -------------------------------------------------------------------------------------------

    /** A repository with one wrong line, and a test that says so. */
    private static Map<String, byte[]> fixture() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("calc.py", utf8("def add(a, b):\n    return a - b\n"));
        files.put(
                "test_calc.py",
                utf8(
                        """
                        import unittest
                        from calc import add


                        class AddTest(unittest.TestCase):
                            def test_adds(self):
                                self.assertEqual(add(2, 3), 5)
                        """));
        files.put("README.md", utf8("# fixture\n"));
        return files;
    }

    private SandboxSpec spec() {
        return new SandboxSpec(
                UUID.randomUUID(),
                UUID.randomUUID(),
                TOOLCHAIN,
                2000,
                512,
                256,
                Duration.ofMinutes(5),
                EgressPolicy.DENY_ALL,
                Set.of("git", "python3"));
    }

    private ExecResult run(String binary, String... args) {
        return run(chunk -> {}, binary, args);
    }

    /**
     * Runs a command that has no business failing, and says what it printed when it does.
     *
     * <p>Bare {@code assertThat(exitCode).isZero()} reports "expected 0 but was 128" and nothing
     * else, which for git is every fatal error it has. The output is the only thing that separates
     * them, so it goes in the failure message rather than being discarded.
     */
    private void runOk(String binary, String... args) {
        var output = new StringBuilder();
        ExecResult result = run(collect(output), binary, args);
        assertThat(result.exitCode())
                .as("%s %s said: %s", binary, String.join(" ", args), output)
                .isZero();
    }

    private ExecResult run(Consumer<OutputChunk> sink, String binary, String... args) {
        return docker.exec(sandbox, ExecRequest.of(binary, List.of(args), Duration.ofSeconds(60)), sink);
    }

    private static Consumer<OutputChunk> collect(StringBuilder into) {
        return chunk -> into.append(new String(chunk.bytes(), StandardCharsets.UTF_8));
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

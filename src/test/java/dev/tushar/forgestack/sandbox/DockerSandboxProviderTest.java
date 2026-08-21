package dev.tushar.forgestack.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The conformance suite against real containers.
 *
 * <p>The reason this adapter exists rather than a plan to write one: everything §16 claims about
 * isolation is either true of a running container or is prose. These tests are the difference.
 *
 * <p>Skipped rather than failed where Docker is absent, because a developer without it should still
 * be able to run the suite — the fake covers the same contract, and CI is where this one has to pass.
 */
class DockerSandboxProviderTest extends SandboxProviderContract {

    private final DockerSandboxProvider docker = new DockerSandboxProvider(Duration.ofSeconds(60));

    @BeforeAll
    static void dockerIsAvailable() {
        assumeTrue(dockerResponds(), "docker is not available on this machine");
    }

    @Override
    protected SandboxProvider provider() {
        return docker;
    }

    @Override
    protected String knownImage() {
        return "alpine:3.20";
    }

    /**
     * §16's hardening, read back off the running container rather than trusted.
     *
     * <p>The plan says every flag must be "readable and verifiable with {@code docker inspect}", and
     * this is that sentence executed. Asserting the flags we passed would only prove we can repeat
     * ourselves; asking Docker what it actually applied is what catches a flag that was silently
     * ignored, renamed, or overridden by a daemon default.
     */
    @Test
    @DisplayName("the container Docker actually created is the hardened one the plan describes")
    void hardeningIsRealAndNotAspirational() {
        SandboxHandle handle = provision();

        assertThat(inspect(handle, "{{.HostConfig.ReadonlyRootfs}}")).isEqualTo("true");
        assertThat(inspect(handle, "{{.Config.User}}")).isEqualTo("10001:10001");
        assertThat(inspect(handle, "{{.HostConfig.CapDrop}}")).contains("ALL");
        assertThat(inspect(handle, "{{.HostConfig.SecurityOpt}}")).contains("no-new-privileges");
        assertThat(inspect(handle, "{{.HostConfig.PidsLimit}}")).isEqualTo("512");
        assertThat(inspect(handle, "{{.HostConfig.Privileged}}")).isEqualTo("false");
        // Memory and swap equal: swap would let a container drift past its limit slowly instead of
        // being killed, turning a bounded failure into an unbounded one.
        assertThat(inspect(handle, "{{.HostConfig.Memory}}")).isEqualTo(inspect(handle, "{{.HostConfig.MemorySwap}}"));
        // DENY_ALL is the absence of a network interface, not a rule that could be misconfigured.
        assertThat(inspect(handle, "{{.HostConfig.NetworkMode}}")).isEqualTo("none");
    }

    /**
     * The claim §16 rests on, tested rather than asserted.
     *
     * <p>"No GitHub token ever enters the sandbox" is the plan's strongest security statement. It is
     * worth nothing if the environment quietly carries one — inherited from the daemon, baked into
     * the image, or added by a well-meaning flag. This reads the environment the container actually
     * has.
     */
    @Test
    @DisplayName("nothing credential-shaped is in the container's environment")
    void noCredentialsReachTheSandbox() {
        SandboxHandle handle = provision();
        var environment = new StringBuilder();

        docker.exec(handle, ExecRequest.of("env", List.of(), Duration.ofSeconds(20)), chunk -> environment.append(
                new String(chunk.bytes(), StandardCharsets.UTF_8)));

        assertThat(environment.toString().toLowerCase(java.util.Locale.ROOT))
                .doesNotContain("token")
                .doesNotContain("secret")
                .doesNotContain("api_key")
                .doesNotContain("password")
                .doesNotContain("github");
    }

    @Test
    @DisplayName("the sandbox cannot reach the network at all")
    void egressIsDeniedByAbsence() {
        SandboxHandle handle = provision();

        ExecResult result = docker.exec(
                handle,
                new ExecRequest("wget", List.of("-q", "-T", "3", "-O", "-", "https://example.com"), ".", Duration.ofSeconds(20)),
                chunk -> {});

        assertThat(result.succeeded())
                .as("DENY_ALL means there is no interface to try")
                .isFalse();
    }

    /**
     * The image name is held to being a name, even when it is shaped like a flag.
     *
     * <p>{@code docker run} parses options until the first non-option argument, so a value in the
     * image position beginning with {@code --} is parsed as an <em>option</em>. Verified against a
     * real daemon: {@code docker run --user 10001:10001 "--volume=/var/run/docker.sock:/sock"
     * alpine:3.20 ls -l /sock} prints {@code srw-rw---- root} &mdash; the daemon socket, mounted,
     * from a string that occupied the image field.
     *
     * <p>This asserts the argument <em>shape</em> rather than the outcome of a run, and the
     * distinction is the whole point. The first version of this test called {@code provision} with a
     * hostile name and asserted {@code Refused} — and it passed with the {@code --} deleted, because
     * {@code sleep} then lands in the image position and is refused for its own unrelated reason.
     * Same verdict, different cause, no guarantee. What actually has to hold is that nothing the spec
     * carries can reach Docker's option parser.
     */
    @Test
    @DisplayName("the image name cannot be parsed as an option")
    void theImageNameCannotBecomeAFlag() {
        List<String> command = DockerSandboxProvider.runCommand(spec("--volume=/var/run/docker.sock:/sock"));

        int terminator = command.indexOf("--");
        assertThat(terminator).as("option parsing must be terminated").isNotNegative();
        assertThat(command.subList(terminator + 1, command.size()))
                .as("the hostile name sits after the terminator, where Docker reads it as a name")
                .startsWith("--volume=/var/run/docker.sock:/sock");
    }

    @Test
    @DisplayName("the process inside is not root")
    void theAgentIsNotRoot() {
        SandboxHandle handle = provision();
        var who = new StringBuilder();

        docker.exec(handle, ExecRequest.of("id", List.of("-u"), Duration.ofSeconds(20)), chunk -> who.append(
                new String(chunk.bytes(), StandardCharsets.UTF_8)));

        assertThat(who.toString().strip()).isEqualTo("10001");
    }

    /**
     * The read-only rootfs, tested somewhere it is the <em>only</em> thing doing the work.
     *
     * <p>The first version of this test wrote to {@code /etc} and passed with {@code --read-only}
     * removed, because {@code /etc} is root-owned and the container runs as uid 10001 — it was
     * proving the user flag while claiming to prove the mount. {@code /var/tmp} is mode 1777 and
     * lives on the rootfs, so uid 10001 can write there whenever the filesystem allows it at all.
     * That makes it the one path where the two mechanisms can be told apart.
     */
    @Test
    @DisplayName("the root filesystem is immutable even where permissions would allow a write")
    void theRootfsIsGenuinelyReadOnly() {
        SandboxHandle handle = provision();

        ExecResult worldWritablePath =
                docker.exec(handle, ExecRequest.of("touch", List.of("/var/tmp/probe"), Duration.ofSeconds(20)), c -> {});

        assertThat(worldWritablePath.succeeded())
                .as("mode 1777 and still refused: that is the mount, not the user")
                .isFalse();
    }

    @Test
    @DisplayName("work is still possible in the one writable place")
    void theWorkspaceIsWritable() {
        SandboxHandle handle = provision();

        ExecResult intoWorkspace = docker.exec(
                handle,
                ExecRequest.of("touch", List.of(DockerSandboxProvider.WORKSPACE + "/probe"), Duration.ofSeconds(20)),
                c -> {});

        assertThat(intoWorkspace.succeeded()).isTrue();
    }

    @Test
    @DisplayName("a root-owned path is refused even before the mount is considered")
    void systemPathsAreNotWritableByTheAgent() {
        SandboxHandle handle = provision();

        ExecResult intoEtc =
                docker.exec(handle, ExecRequest.of("touch", List.of("/etc/forge-probe"), Duration.ofSeconds(20)), c -> {});

        assertThat(intoEtc.succeeded()).isFalse();
    }

    @Test
    @DisplayName("every sandbox is labelled so an orphan can be traced back to its attempt")
    void orphansAreFindable() {
        SandboxHandle handle = provision();

        assertThat(docker.orphans()).isNotEmpty();
        assertThat(inspect(handle, "{{index .Config.Labels \"dev.tushar.forgestack.sandbox\"}}"))
                .isEqualTo(handle.sandboxId().toString());
    }

    // -------------------------------------------------------------------------------------------

    private static String inspect(SandboxHandle handle, String format) {
        return runDocker(List.of("docker", "inspect", "--format", format, handle.externalId()));
    }

    private static boolean dockerResponds() {
        try {
            return !runDocker(List.of("docker", "version", "--format", "{{.Server.Version}}")).isBlank();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String runDocker(List<String> command) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
            return output.strip();
        } catch (Exception e) {
            throw new IllegalStateException("could not run " + command, e);
        }
    }
}

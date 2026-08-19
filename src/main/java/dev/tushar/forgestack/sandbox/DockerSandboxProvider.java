package dev.tushar.forgestack.sandbox;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One hardened container per sandbox, driven through the Docker CLI.
 *
 * <p><strong>The CLI rather than the Engine API, deliberately, and the reason is auditability.</strong>
 * §16 specifies this container's hardening as a block of {@code docker run} flags and says every one
 * must be "readable and verifiable with {@code docker inspect}". Building the same command here keeps
 * the specification and the implementation in one vocabulary, so a reviewer can diff the plan against
 * {@link #hardeningFlags} line by line. Through a client library the same flags become scattered
 * builder calls that no reviewer can check against the plan without translating both.
 *
 * <p>The cost is a process spawn per operation, which is tens of milliseconds against operations that
 * take seconds, and no streaming API of our own. A deployment running hundreds of concurrent
 * sandboxes should move to the Engine API — behind this port, measured by the same conformance suite,
 * which is exactly the swap §16 designed for.
 *
 * <p>Every container is labelled with its workspace and sandbox id so that an orphan found on a
 * worker VM at three in the morning can be traced to the attempt that leaked it, and so the reaper
 * can find them without keeping its own list.
 */
public final class DockerSandboxProvider implements SandboxProvider {

    private static final Logger log = LoggerFactory.getLogger(DockerSandboxProvider.class);

    /** Where the working copy lives inside every sandbox. The only writable path. */
    static final String WORKSPACE = "/workspace";

    private static final String LABEL_SANDBOX = "dev.tushar.forgestack.sandbox";
    private static final String LABEL_WORKSPACE = "dev.tushar.forgestack.workspace";

    private final Duration dockerTimeout;

    /**
     * What each live sandbox is allowed to run.
     *
     * <p>Held here because the handle deliberately carries nothing but an opaque id, and §15 requires
     * the allowlist be enforced at the moment of execution rather than only where tools are offered.
     * Lost on restart, which is correct: a sandbox whose provisioning process is gone is one the
     * reaper should be destroying, not one a new process should start running commands in.
     */
    private final java.util.Map<String, java.util.Set<String>> allowedBinaries =
            new java.util.concurrent.ConcurrentHashMap<>();

    public DockerSandboxProvider(Duration dockerTimeout) {
        this.dockerTimeout = dockerTimeout;
    }

    public DockerSandboxProvider() {
        this(Duration.ofSeconds(120));
    }

    @Override
    public String name() {
        return "docker";
    }

    @Override
    public SandboxHandle provision(SandboxSpec spec) {
        List<String> command = new ArrayList<>(List.of("docker", "run", "--detach"));
        command.addAll(hardeningFlags(spec));
        command.addAll(List.of(
                "--label", LABEL_SANDBOX + "=" + spec.sandboxId(),
                "--label", LABEL_WORKSPACE + "=" + spec.workspaceId(),
                "--name", containerName(spec.sandboxId())));
        command.add(spec.ociImage());
        // A container that exits immediately cannot be exec'd into. §16 calls this out because the
        // naive Kubernetes port reaches for a Job, which is run-to-completion, and then cannot satisfy
        // a port whose contract is a session with repeated exec. Sleeping is that contract, on Docker.
        command.addAll(List.of("sleep", String.valueOf(spec.ttl().toSeconds())));

        Ran ran = run(command, dockerTimeout);
        if (!ran.ok()) {
            String stderr = ran.stderr();
            if (imageIsUnobtainable(stderr)) {
                throw new SandboxException.Refused("no such image: " + spec.ociImage());
            }
            if (stderr.contains("no space left") || stderr.contains("cannot allocate memory")) {
                throw new SandboxException.CapacityExhausted("the docker host is out of room: " + stderr.strip());
            }
            throw new SandboxException.SubstrateUnavailable("docker run failed: " + stderr.strip(), null);
        }
        String containerId = ran.stdout().strip();
        allowedBinaries.put(containerId, spec.allowedBinaries());
        return new SandboxHandle(spec.sandboxId(), spec.workspaceId(), name(), containerId);
    }

    /**
     * Whether the image will never resolve, as opposed to a registry having a bad minute.
     *
     * <p>The wording is Docker's and had to be read off a real daemon: a repository that does not
     * exist comes back as {@code pull access denied ... repository does not exist}, which matches
     * none of the phrases the first version of this method guessed at. Retrying an unobtainable image
     * spends a task's whole retry budget discovering a typo, so the distinction earns its place.
     *
     * <p>Authentication failures land here too. That is deliberate: ForgeStack runs only images it
     * built, so "we are not allowed to pull this" means the image is not the one we think it is, and
     * a person should look rather than a scheduler retry.
     */
    private static boolean imageIsUnobtainable(String stderr) {
        return stderr.contains("No such image")
                || stderr.contains("manifest unknown")
                || stderr.contains("repository does not exist")
                || stderr.contains("pull access denied")
                || stderr.contains("not found");
    }

    /**
     * §16's hardening block, in the order the plan writes it.
     *
     * <p>Kept as one method so it can be read against the plan without hunting. Anything removed here
     * weakens the boundary silently, which is why {@code DockerSandboxHardeningTest} reads the flags
     * back off a running container with {@code docker inspect} rather than trusting this list.
     */
    static List<String> hardeningFlags(SandboxSpec spec) {
        List<String> flags = new ArrayList<>(List.of(
                "--user", "10001:10001",
                "--read-only",
                "--tmpfs", "/tmp:rw,noexec,nosuid,size=512m",
                // The one writable path, and a tmpfs rather than a bind mount: §16 forbids a host
                // path escaping the adapter, and a bind mount is exactly how that starts.
                "--tmpfs", WORKSPACE + ":rw,exec,nosuid,size=" + spec.diskMib() + "m",
                "--cap-drop=ALL",
                "--security-opt=no-new-privileges",
                "--pids-limit=512",
                "--memory=" + spec.memoryMib() + "m",
                // Equal to --memory: swap would let a container exceed its memory limit slowly
                // instead of being killed, turning a bounded failure into an unbounded one.
                "--memory-swap=" + spec.memoryMib() + "m",
                "--cpus=" + (spec.cpuMillis() / 1000.0)));
        flags.addAll(
                switch (spec.egress()) {
                    // Not a firewall rule that could be misconfigured: no network interface at all.
                    case DENY_ALL -> List.of("--network", "none");
                    // Per workspace, so two tenants' sandboxes cannot reach each other even when both
                    // are allowed to reach the proxy.
                    case PROXY_ONLY -> List.of("--network", "forge-sbx-" + spec.workspaceId());
                });
        return flags;
    }

    @Override
    public ExecResult exec(SandboxHandle handle, ExecRequest request, Consumer<OutputChunk> sink) {
        // §15's allowlist, enforced at the boundary rather than only where tools are offered. A model
        // that invents a binary gets a refusal it can read, not a process.
        java.util.Set<String> allowed = allowedBinaries.get(handle.externalId());
        if (allowed == null) {
            throw new SandboxException.SandboxLost("no live sandbox: " + handle.externalId(), null);
        }
        if (!allowed.contains(request.binary())) {
            throw new SandboxException.Refused(
                    "'%s' is not in this sandbox's allowlist".formatted(request.binary()));
        }
        List<String> command = new ArrayList<>(List.of(
                "docker", "exec", "--workdir", workDirFor(request), "--user", "10001:10001", handle.externalId()));
        command.add(request.binary());
        command.addAll(request.args());

        Instant started = Instant.now();
        Ran ran = run(command, request.timeout());
        Duration took = Duration.between(started, Instant.now());

        if (ran.stderr().contains("No such container") || ran.stderr().contains("is not running")) {
            throw new SandboxException.SandboxLost("the container went away: " + handle.externalId(), null);
        }
        long bytes = 0;
        if (!ran.stdout().isEmpty()) {
            byte[] out = ran.stdout().getBytes(StandardCharsets.UTF_8);
            sink.accept(new OutputChunk(OutputChunk.Stream.STDOUT, out));
            bytes += out.length;
        }
        if (!ran.stderr().isEmpty()) {
            byte[] err = ran.stderr().getBytes(StandardCharsets.UTF_8);
            sink.accept(new OutputChunk(OutputChunk.Stream.STDERR, err));
            bytes += err.length;
        }
        return new ExecResult(ran.exitCode(), ran.timedOut(), took, bytes);
    }

    /** Validates the binary and the working directory before anything is spawned. */
    private static String workDirFor(ExecRequest request) {
        return WORKSPACE + "/" + safeRelative(request.workDir());
    }

    @Override
    public void writeFile(SandboxHandle handle, String relPath, byte[] content) {
        String safe = safeRelative(relPath);
        // Piped through the container's own stdin rather than `docker cp`, because cp needs a host
        // file and §16 forbids a host path being part of how this works.
        Ran ran = runWithInput(
                List.of(
                        "docker", "exec", "--user", "10001:10001", "-i", handle.externalId(),
                        "sh", "-c", "mkdir -p \"$(dirname \"$1\")\" && cat > \"$1\"", "--",
                        WORKSPACE + "/" + safe),
                content,
                dockerTimeout);
        if (!ran.ok()) {
            throw lostOrUnavailable(handle, "writing " + safe, ran);
        }
    }

    @Override
    public byte[] readFile(SandboxHandle handle, String relPath) {
        String safe = safeRelative(relPath);
        Ran ran = run(
                List.of("docker", "exec", "--user", "10001:10001", handle.externalId(), "cat", WORKSPACE + "/" + safe),
                dockerTimeout);
        if (!ran.ok()) {
            if (ran.stderr().contains("No such file")) {
                throw new SandboxException.Refused("no such file in the sandbox: " + safe);
            }
            throw lostOrUnavailable(handle, "reading " + safe, ran);
        }
        return ran.stdout().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public HealthState probe(SandboxHandle handle) {
        Ran ran = run(
                List.of("docker", "inspect", "--format", "{{.State.Running}}{{.State.OOMKilled}}", handle.externalId()),
                dockerTimeout);
        if (!ran.ok()) {
            return HealthState.GONE;
        }
        String state = ran.stdout().strip();
        if (state.startsWith("true")) {
            // OOMKilled stays true on a container that was killed and restarted, which is worth
            // knowing about rather than reporting as healthy.
            return state.endsWith("true") ? HealthState.DEGRADED : HealthState.ALIVE;
        }
        return HealthState.GONE;
    }

    @Override
    public void destroy(SandboxHandle handle) {
        // --force so a running container goes too, --volumes so nothing is left behind. Failure is
        // logged and swallowed: this is called from finally blocks, and a container that is already
        // gone is the outcome we wanted.
        allowedBinaries.remove(handle.externalId());
        Ran ran = run(List.of("docker", "rm", "--force", "--volumes", handle.externalId()), dockerTimeout);
        if (!ran.ok() && !ran.stderr().contains("No such container")) {
            log.warn("could not remove sandbox {}: {}", handle.externalId(), ran.stderr().strip());
        }
    }

    /**
     * Finds sandboxes this provider left behind.
     *
     * <p>Driven off the labels rather than a table we keep, so a reaper still works after the process
     * that created them has been replaced — which is the case that matters, because the containers
     * that leak are the ones whose worker died.
     */
    public List<String> orphans() {
        Ran ran = run(
                List.of("docker", "ps", "--all", "--quiet", "--filter", "label=" + LABEL_SANDBOX), dockerTimeout);
        return ran.ok() ? ran.stdout().lines().filter(line -> !line.isBlank()).toList() : List.of();
    }

    static String containerName(UUID sandboxId) {
        return "forge-sbx-" + sandboxId;
    }

    /**
     * Refuses a path that would escape the workspace.
     *
     * <p>The string comes from a model, so it is treated as hostile input rather than as a path.
     * Normalising first and checking after is what catches {@code a/../../etc/passwd}, which a naive
     * {@code startsWith("..")} check misses entirely.
     */
    static String safeRelative(String relPath) {
        if (relPath == null || relPath.isBlank()) {
            throw new SandboxException.Refused("a path is required");
        }
        if (relPath.startsWith("/") || relPath.contains("\0")) {
            throw new SandboxException.Refused("path must be relative to the workspace: " + relPath);
        }
        Path normalised = Path.of(relPath).normalize();
        if (normalised.isAbsolute() || normalised.startsWith("..")) {
            throw new SandboxException.Refused("path escapes the workspace: " + relPath);
        }
        return normalised.toString();
    }

    private SandboxException lostOrUnavailable(SandboxHandle handle, String what, Ran ran) {
        if (ran.stderr().contains("No such container") || ran.stderr().contains("is not running")) {
            return new SandboxException.SandboxLost("the container went away while " + what, null);
        }
        return new SandboxException.SubstrateUnavailable("docker failed while " + what + ": " + ran.stderr().strip(), null);
    }

    // -------------------------------------------------------------------------------------------

    private record Ran(int exitCode, String stdout, String stderr, boolean timedOut) {
        boolean ok() {
            return exitCode == 0 && !timedOut;
        }
    }

    private Ran run(List<String> command, Duration timeout) {
        return runWithInput(command, null, timeout);
    }

    private Ran runWithInput(List<String> command, byte[] input, Duration timeout) {
        try {
            Process process = new ProcessBuilder(command).start();
            if (input != null) {
                try (var stdin = process.getOutputStream()) {
                    stdin.write(input);
                }
            } else {
                process.getOutputStream().close();
            }
            // Drained on separate threads: a process that fills its pipe buffer blocks forever while
            // we wait for it to exit, and we would be waiting for it to drain a pipe only we can read.
            var out = drain(process.getInputStream());
            var err = drain(process.getErrorStream());

            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                return new Ran(-1, out.join(), err.join(), true);
            }
            return new Ran(process.exitValue(), out.join(), err.join(), false);
        } catch (IOException e) {
            throw new SandboxException.SubstrateUnavailable("could not run docker: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SandboxException.SubstrateUnavailable("interrupted while running docker", e);
        }
    }

    private static java.util.concurrent.CompletableFuture<String> drain(InputStream stream) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try (stream; var buffer = new ByteArrayOutputStream()) {
                stream.transferTo(buffer);
                return buffer.toString(StandardCharsets.UTF_8);
            } catch (IOException e) {
                return "";
            }
        });
    }
}

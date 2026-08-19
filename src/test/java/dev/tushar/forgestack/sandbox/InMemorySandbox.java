package dev.tushar.forgestack.sandbox;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * A sandbox with no container in it, so the runtime around it can be tested without Docker.
 *
 * <p>It simulates exactly the handful of commands the conformance suite uses — {@code echo},
 * {@code false}, {@code sleep} — and nothing else. That narrowness is deliberate: the moment a fake
 * starts interpreting commands generally it becomes a shell nobody reviewed, and tests start passing
 * against behaviour no real substrate has.
 *
 * <p>What it is genuinely for is the parts that are hard to arrange on purpose against Docker: a
 * substrate refusing for want of capacity, a sandbox vanishing mid-command. Those are the paths §20
 * insists are routine and that a quiet Docker host will never exercise for you.
 */
final class InMemorySandbox implements SandboxProvider {

    private final Map<String, Live> live = new ConcurrentHashMap<>();
    private volatile boolean atCapacity;

    @Override
    public String name() {
        return "in-memory";
    }

    @Override
    public SandboxHandle provision(SandboxSpec spec) {
        if (atCapacity) {
            throw new SandboxException.CapacityExhausted("the fake was told it had no room");
        }
        if (!spec.ociImage().startsWith("forgestack/java")) {
            throw new SandboxException.Refused("no such image: " + spec.ociImage());
        }
        String id = UUID.randomUUID().toString();
        live.put(id, new Live(spec));
        return new SandboxHandle(spec.sandboxId(), spec.workspaceId(), name(), id);
    }

    @Override
    public ExecResult exec(SandboxHandle handle, ExecRequest request, Consumer<OutputChunk> sink) {
        Live sandbox = require(handle);
        if (!sandbox.spec.allowedBinaries().contains(request.binary())) {
            throw new SandboxException.Refused("'%s' is not in this sandbox's allowlist".formatted(request.binary()));
        }
        return switch (request.binary()) {
            case "echo" -> {
                byte[] out = (String.join(" ", request.args()) + "\n").getBytes(StandardCharsets.UTF_8);
                sink.accept(new OutputChunk(OutputChunk.Stream.STDOUT, out));
                yield new ExecResult(0, false, Duration.ofMillis(1), out.length);
            }
            case "false" -> new ExecResult(1, false, Duration.ofMillis(1), 0);
            case "sleep" -> new ExecResult(-1, true, request.timeout(), 0);
            default -> new ExecResult(0, false, Duration.ofMillis(1), 0);
        };
    }

    @Override
    public void writeFile(SandboxHandle handle, String relPath, byte[] content) {
        Live sandbox = require(handle);
        sandbox.files.put(DockerSandboxProvider.safeRelative(relPath), content);
    }

    @Override
    public byte[] readFile(SandboxHandle handle, String relPath) {
        Live sandbox = require(handle);
        byte[] content = sandbox.files.get(DockerSandboxProvider.safeRelative(relPath));
        if (content == null) {
            throw new SandboxException.Refused("no such file in the sandbox: " + relPath);
        }
        return content;
    }

    @Override
    public HealthState probe(SandboxHandle handle) {
        return live.containsKey(handle.externalId()) ? HealthState.ALIVE : HealthState.GONE;
    }

    @Override
    public void destroy(SandboxHandle handle) {
        live.remove(handle.externalId());
    }

    /** Makes the next provision refuse, so the scheduler's backpressure path can be exercised. */
    void reportNoCapacity(boolean exhausted) {
        this.atCapacity = exhausted;
    }

    /** Destroys a sandbox out from under a caller, the way an evicted pod would. */
    void induceLoss(SandboxHandle handle) {
        live.remove(handle.externalId());
    }

    int liveCount() {
        return live.size();
    }

    private Live require(SandboxHandle handle) {
        Live sandbox = live.get(handle.externalId());
        if (sandbox == null) {
            throw new SandboxException.SandboxLost("no live sandbox: " + handle.externalId(), null);
        }
        return sandbox;
    }

    private static final class Live {
        private final SandboxSpec spec;
        private final Map<String, byte[]> files = new HashMap<>();

        private Live(SandboxSpec spec) {
            this.spec = spec;
        }
    }
}

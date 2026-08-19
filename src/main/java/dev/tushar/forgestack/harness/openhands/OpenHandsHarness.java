package dev.tushar.forgestack.harness.openhands;

import tools.jackson.databind.JsonNode;
import dev.tushar.forgestack.harness.AttemptSpec;
import dev.tushar.forgestack.harness.ExecutionHarness;
import dev.tushar.forgestack.harness.HarnessEvent;
import dev.tushar.forgestack.harness.HarnessException;
import dev.tushar.forgestack.harness.HarnessSession;
import dev.tushar.forgestack.harness.HarnessStop;
import dev.tushar.forgestack.harness.Instruction;
import dev.tushar.forgestack.harness.StopReason;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Drives an OpenHands agent server over its REST API.
 *
 * <p>Appendix B's primary candidate, behind the port so that being wrong about it costs an adapter
 * rather than a rewrite. Written against the v1.42.1 API, read from source rather than from the docs
 * site.
 *
 * <p><strong>The credential rule is enforced by omission.</strong> Their {@code SecretRegistry} is how
 * an agent gets a GitHub token — register one and the terminal tool exports it into the shell the
 * moment a command mentions its name. This adapter never calls
 * {@code POST /conversations/&#123;id&#125;/secrets} and never puts one in the start request, so
 * there is nothing to export. Git stays host-brokered (§16); {@link #captureDiff} is how work leaves
 * the sandbox, and their git endpoints are read-only, so no push is reachable from in there anyway.
 *
 * <p>The model credential is a different matter and §16 now says so. The agent loop runs inside the
 * sandbox, so whatever key it holds sits beside the customer's source code. {@code modelBaseUrl}
 * exists to point it at ForgeStack's egress proxy, which holds the real credential and attaches it;
 * the key given here should be a per-attempt token worth nothing anywhere else.
 *
 * <p>Responses are read as {@link JsonNode} rather than mapped to records. Nine minor versions
 * shipped in the five weeks before this was written, and a strict binding would break on a field
 * being added somewhere we never look. We read the handful of fields we actually use and let the
 * rest change.
 */
public final class OpenHandsHarness implements ExecutionHarness {

    private static final Logger log = LoggerFactory.getLogger(OpenHandsHarness.class);

    private final RestClient http;
    private final Settings settings;

    /**
     * Where each conversation's checkout lives.
     *
     * <p>Remembered from the spec because their git endpoints want a path and our port's
     * {@code captureDiff} is only given a session. Keyed rather than taken from configuration so
     * that a server driving more than one attempt still answers about the right one — a shape §18
     * forbids for tenancy reasons, but that is a rule about deployment, not something this class
     * should quietly assume.
     */
    private final Map<String, String> workingDirs = new java.util.concurrent.ConcurrentHashMap<>();

    public OpenHandsHarness(RestClient.Builder builder, Settings settings) {
        this.settings = settings;
        this.http = builder.baseUrl(settings.baseUrl())
                .defaultHeader("X-Session-API-Key", settings.sessionApiKey())
                .build();
    }

    /**
     * @param baseUrl where the agent server is
     * @param sessionApiKey the server's single shared key. Note what it is not: there is no user and
     *     no tenant in their auth model, so one key admits every conversation on that server — which
     *     is why §18 requires one server per attempt rather than a shared one. Leaving it unset on
     *     their side disables the check entirely.
     * @param model passed through to LiteLLM
     * @param modelBaseUrl ForgeStack's egress proxy, never the provider directly
     * @param modelApiKey a per-attempt token the proxy exchanges for the real one
     * @param pollInterval how often to ask whether the agent has stopped. Their run endpoint returns
     *     immediately and works in the background, so there is nothing to block on.
     */
    public record Settings(
            String baseUrl,
            String sessionApiKey,
            String model,
            String modelBaseUrl,
            String modelApiKey,
            Duration pollInterval) {}

    @Override
    public String name() {
        return "openhands";
    }

    @Override
    public HarnessSession open(AttemptSpec spec) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(
                "agent",
                Map.of(
                        "llm",
                        Map.of(
                                "usage_id", "forgestack-" + spec.attemptId(),
                                "model", settings.model(),
                                "base_url", settings.modelBaseUrl(),
                                "api_key", settings.modelApiKey()),
                        "tools",
                        toolsetsFor(spec)));
        body.put("workspace", Map.of("kind", "LocalWorkspace", "working_dir", spec.workingCopy().path()));
        // No initial_message: the instruction arrives through run(), so that opening a session and
        // giving it work stay two decisions. Nothing here carries a secret -- see the class note.

        JsonNode created = post("/api/conversations", body);
        String id = text(created, "id");
        if (id == null) {
            throw new HarnessException.HarnessUnavailable("the agent server returned a conversation with no id", null);
        }
        workingDirs.put(id, spec.workingCopy().path());
        return new HarnessSession(spec.attemptId(), name(), id);
    }

    @Override
    public HarnessStop run(HarnessSession session, Instruction instruction, Consumer<HarnessEvent> sink) {
        post(
                "/api/conversations/" + session.externalId() + "/events",
                Map.of(
                        "role", "user",
                        "content", List.of(Map.of("type", "text", "text", instruction.text())),
                        "run", true));

        String cursor = null;
        int steps = 0;
        while (true) {
            cursor = drainEvents(session, cursor, sink);
            JsonNode info = get("/api/conversations/" + session.externalId());
            String status = text(info, "execution_status");
            StopReason reason = stopReasonFor(status);
            if (reason != null) {
                return new HarnessStop(reason, "agent server reported " + status, steps);
            }
            steps++;
            if (steps > instruction.maxSteps()) {
                return new HarnessStop(StopReason.BUDGET_EXHAUSTED, "the step ceiling was reached", steps);
            }
            sleep();
        }
    }

    /**
     * Our per-tool allowlist, expressed in the only vocabulary they have: toolsets.
     *
     * <p><strong>This translation loses something §15 says must not be lost.</strong> Our allowlist
     * is per tool and per phase — {@code ANALYZING} gets reads, {@code EXECUTING} gets writes, and
     * {@code run_command} is "an allowlisted set of binaries, not a shell", on the grounds that an
     * unrestricted shell makes the sandbox's other controls largely decorative. Their unit of
     * granting is a whole tool class, and {@code BashTool} is a shell.
     *
     * <p>So asking for {@code run_tests} gets the agent arbitrary command execution, and there is no
     * way to ask for less. Nothing here can fix that: the enforcement §15 wants would have to happen
     * inside their tool, and it does not. Until that is resolved, either the binary allowlist moves
     * into a wrapper we control on the sandbox's PATH, or §15 is amended to admit that a harness with
     * a shell tool cannot honour it. It is not a version problem and a newer image will not help.
     */
    private static List<Map<String, Object>> toolsetsFor(AttemptSpec spec) {
        String workingDir = spec.workingCopy().path();
        List<Map<String, Object>> toolsets = new ArrayList<>();
        boolean wantsFiles = spec.allowedTools().stream()
                .anyMatch(tool -> tool.startsWith("read") || tool.startsWith("apply") || tool.startsWith("write")
                        || tool.equals("grep") || tool.startsWith("list") || tool.startsWith("find"));
        boolean wantsExec =
                spec.allowedTools().stream().anyMatch(tool -> tool.startsWith("run"));
        if (wantsFiles) {
            toolsets.add(Map.of("name", "FileEditorTool", "params", Map.of("workspace_root", workingDir)));
        }
        if (wantsExec) {
            toolsets.add(Map.of("name", "BashTool", "params", Map.of("working_dir", workingDir)));
        }
        return toolsets;
    }

    /**
     * Their execution status, translated — and note what nothing maps to.
     *
     * <p>{@code finished} becomes {@link StopReason#INSTRUCTION_FINISHED} and not success. It is set
     * when the model calls a finish tool, which is the model's opinion of its own work. Whether that
     * work is acceptable is decided in Java from evidence, and no status they could invent will
     * change that.
     *
     * @return null while the agent is still working
     */
    private static StopReason stopReasonFor(String executionStatus) {
        if (executionStatus == null) {
            return null;
        }
        return switch (executionStatus) {
            case "finished" -> StopReason.INSTRUCTION_FINISHED;
            case "paused" -> StopReason.PAUSED;
            case "waiting_for_confirmation" -> StopReason.AWAITING_HUMAN;
            case "stuck" -> StopReason.STUCK;
            case "error" -> StopReason.HARNESS_ERROR;
            default -> null;
        };
    }

    /** Reads whatever events have appeared since the cursor, and returns the new one. */
    private String drainEvents(HarnessSession session, String cursor, Consumer<HarnessEvent> sink) {
        String path = "/api/conversations/" + session.externalId() + "/events/search?limit=100"
                + (cursor == null ? "" : "&page_id=" + cursor);
        JsonNode page = get(path);
        for (JsonNode event : page.path("items")) {
            translate(event).forEach(sink);
        }
        String next = text(page, "next_page_id");
        return next == null ? cursor : next;
    }

    /**
     * One of their events, as zero or more of ours.
     *
     * <p>Only the kinds ForgeStack records. Their event vocabulary is much wider and most of it is
     * either bookkeeping the agent server needs for itself or transcript detail belonging in blob
     * storage rather than in a step row.
     */
    private static List<HarnessEvent> translate(JsonNode event) {
        List<HarnessEvent> ours = new ArrayList<>();
        String kind = text(event, "kind");
        if (kind == null) {
            return ours;
        }
        switch (kind) {
            case "ActionEvent" -> {
                JsonNode action = event.path("action");
                String tool = text(event, "tool_name");
                // Their own name for it is security_risk. Ours says whose opinion it is.
                String declaredRisk = text(action, "security_risk");
                ours.add(new HarnessEvent.ToolCallRequested(
                        tool == null ? kind : tool,
                        Integer.toHexString(action.toString().hashCode()),
                        declaredRisk == null ? "UNKNOWN" : declaredRisk));
            }
            case "ObservationEvent" -> {
                JsonNode observation = event.path("observation");
                String content = observation.toString();
                ours.add(new HarnessEvent.ToolCallCompleted(
                        firstNonNull(text(event, "tool_name"), "unknown"),
                        observation.path("error").asBoolean(false),
                        Integer.toHexString(content.hashCode()),
                        content.length()));
            }
            case "MessageEvent" -> {
                String said = event.path("llm_message").path("content").toString();
                ours.add(new HarnessEvent.AgentSpoke(said));
            }
            default -> {
                /* bookkeeping we do not record */
            }
        }
        JsonNode usage = event.path("llm_metrics").path("accumulated_token_usage");
        if (!usage.isMissingNode()) {
            ours.add(new HarnessEvent.TokensConsumed(
                    usage.path("prompt_tokens").asLong(0),
                    usage.path("completion_tokens").asLong(0),
                    usage.path("cache_read_tokens").asLong(0)));
        }
        return ours;
    }

    @Override
    public void pause(HarnessSession session) {
        try {
            post("/api/conversations/" + session.externalId() + "/pause", Map.of());
        } catch (HarnessException.SessionLost | HarnessException.SpecRejected e) {
            // Documented idempotent and harmless when nothing is running. A caller cancelling a task
            // cannot know the sandbox died a moment ago and must not have to.
            log.debug("pause on {} had nothing to stop", session.externalId());
        }
    }

    /**
     * The whole patch, assembled here because the agent server has no endpoint that returns one.
     *
     * <p>{@code /git/changes} lists the files and {@code /git/diff} returns each one's before and
     * after in full; {@link UnifiedDiffs} turns that back into a patch. Both are read-only endpoints,
     * which is the property §16 depends on — there is no push reachable from inside the sandbox.
     */
    @Override
    public String captureDiff(HarnessSession session) {
        String workingDir = workingDirs.getOrDefault(session.externalId(), ".");
        JsonNode changes = get("/api/git/changes?path=" + workingDir);
        List<String> perFile = new ArrayList<>();
        for (JsonNode change : changes) {
            String path = text(change, "path");
            if (path == null) {
                continue;
            }
            JsonNode diff = get("/api/git/diff?path=" + path);
            perFile.add(UnifiedDiffs.forFile(path, text(diff, "original"), text(diff, "modified")));
        }
        return UnifiedDiffs.patch(perFile);
    }

    @Override
    public void close(HarnessSession session) {
        try {
            http.delete().uri("/api/conversations/" + session.externalId()).retrieve().toBodilessEntity();
            workingDirs.remove(session.externalId());
        } catch (RestClientException e) {
            // Idempotent by contract, and called from finally blocks that cannot know whether the
            // session survived. A sandbox that is already gone is the outcome we wanted.
            log.debug("close on {} found nothing to close", session.externalId());
        }
    }

    // -------------------------------------------------------------------------------------------

    private JsonNode post(String path, Object body) {
        return exchange(() -> http.post().uri(path).body(body).retrieve().body(JsonNode.class), path);
    }

    private JsonNode get(String path) {
        return exchange(() -> http.get().uri(path).retrieve().body(JsonNode.class), path);
    }

    /**
     * Every failure the agent server can produce, as one of the four the runtime handles.
     *
     * <p>§16's rule, and the one an adapter is most likely to break: a {@code RestClientException}
     * reaching a runtime {@code catch} block teaches the runtime which harness it is talking to, and
     * the port stops being a port without anyone editing it.
     */
    private JsonNode exchange(java.util.function.Supplier<JsonNode> call, String path) {
        try {
            JsonNode body = call.get();
            return body == null ? tools.jackson.databind.node.NullNode.getInstance() : body;
        } catch (org.springframework.web.client.RestClientResponseException e) {
            HttpStatusCode status = e.getStatusCode();
            if (status.value() == 404) {
                throw new HarnessException.SessionLost("the agent server has no such conversation: " + path, e);
            }
            if (status.value() == 422 || status.value() == 400) {
                throw new HarnessException.SpecRejected("the agent server refused the request: " + e.getMessage());
            }
            if (status.value() == 409) {
                // Already running. Not a failure of ours -- somebody else is driving this session,
                // which for a one-attempt-per-sandbox design means the sandbox is not ours to use.
                throw new HarnessException.CapacityExhausted("the conversation is already running");
            }
            if (status.value() == 503) {
                throw new HarnessException.CapacityExhausted("the agent server is not ready");
            }
            throw new HarnessException.HarnessUnavailable("agent server returned " + status + " for " + path, e);
        } catch (RestClientException e) {
            throw new HarnessException.HarnessUnavailable("could not reach the agent server at " + path, e);
        }
    }

    private void sleep() {
        try {
            Thread.sleep(settings.pollInterval().toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new HarnessException.HarnessUnavailable("interrupted while waiting for the agent", e);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private static String firstNonNull(String a, String b) {
        return a == null ? b : a;
    }
}

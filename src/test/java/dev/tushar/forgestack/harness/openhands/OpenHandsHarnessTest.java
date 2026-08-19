package dev.tushar.forgestack.harness.openhands;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import dev.tushar.forgestack.harness.AttemptSpec;
import dev.tushar.forgestack.harness.EgressPolicy;
import dev.tushar.forgestack.harness.HarnessException;
import dev.tushar.forgestack.harness.HarnessSession;
import dev.tushar.forgestack.harness.ResourceLimits;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * What the adapter does with what the agent server says.
 *
 * <p><strong>Read the scope before trusting this.</strong> These are not conformance tests and this
 * class does not extend {@code ExecutionHarnessContract}. The contract requires a real agent server,
 * and 3.2's findings record why there is not one to run: the only pullable {@code linux/amd64} image
 * ships {@code openhands_sdk 1.0.0}, whose API has no git endpoints at all, and current versions must
 * be built from source. Until that build exists, this pins the two things that can be tested honestly
 * without one — how failures are translated, and how their status vocabulary maps onto ours.
 *
 * <p>A real HTTP server rather than a mocked client, following {@code FakeGithub} and for the same
 * reason: the interesting failures live in the wire format, and a mock agrees with whatever the code
 * already believes.
 */
class OpenHandsHarnessTest {

    private HttpServer server;
    private OpenHandsHarness harness;
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicReference<String> body = new AtomicReference<>("{}");

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            byte[] payload = body.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status.get(), payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });
        server.start();
        harness = new OpenHandsHarness(
                RestClient.builder(),
                new OpenHandsHarness.Settings(
                        "http://localhost:" + server.getAddress().getPort(),
                        "test-key",
                        "test/model",
                        "http://localhost:1/proxy",
                        "per-attempt-token",
                        Duration.ofMillis(10)));
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    /**
     * §16's rule, and the one an adapter is most likely to break.
     *
     * <p>A {@code RestClientResponseException} reaching a runtime {@code catch} block teaches the
     * runtime which harness it is talking to, and the port stops being a port without anyone editing
     * it. Each of these codes means something the runtime already knows how to handle — and 404 in
     * particular must mean "the sandbox is gone" rather than "the work failed", because §20 spends no
     * retry on the first and one on the second.
     */
    @Test
    @DisplayName("every failure the server can produce arrives as one of the four the runtime handles")
    void failuresAreNormalised() {
        record Case(int http, Class<? extends HarnessException> expected) {}
        List<Case> cases = List.of(
                new Case(404, HarnessException.SessionLost.class),
                new Case(409, HarnessException.CapacityExhausted.class),
                new Case(503, HarnessException.CapacityExhausted.class),
                new Case(422, HarnessException.SpecRejected.class),
                new Case(400, HarnessException.SpecRejected.class),
                new Case(500, HarnessException.HarnessUnavailable.class));

        for (Case each : cases) {
            status.set(each.http());
            assertThatThrownBy(() -> harness.open(spec()))
                    .as("HTTP %d", each.http())
                    .isInstanceOf(each.expected());
        }
    }

    @Test
    @DisplayName("an unreachable agent server is unavailable, not a lost session")
    void unreachableIsDistinctFromGone() {
        OpenHandsHarness unreachable = new OpenHandsHarness(
                RestClient.builder(),
                new OpenHandsHarness.Settings(
                        "http://localhost:1", "k", "m", "http://localhost:1", "t", Duration.ofMillis(10)));

        assertThatThrownBy(() -> unreachable.open(spec())).isInstanceOf(HarnessException.HarnessUnavailable.class);
    }

    @Test
    @DisplayName("a conversation with no id is a failure, not a session that half exists")
    void aMissingIdIsRefused() {
        status.set(200);
        body.set("{\"execution_status\":\"idle\"}");

        assertThatThrownBy(() -> harness.open(spec())).isInstanceOf(HarnessException.HarnessUnavailable.class);
    }

    @Test
    @DisplayName("opening a conversation yields a session named by the server")
    void openReturnsTheServersId() {
        status.set(201);
        body.set("{\"id\":\"c0ffee\",\"execution_status\":\"idle\"}");

        HarnessSession session = harness.open(spec());

        assertThat(session.externalId()).isEqualTo("c0ffee");
        assertThat(session.harness()).isEqualTo("openhands");
    }

    /**
     * {@code finished} is not success.
     *
     * <p>It is set when the model calls a finish tool — the model's opinion of its own work. Mapping
     * it to anything ForgeStack could read as a verdict would hand the completion decision to a
     * process running inside the sandbox, which §10.3 reserves for guards over committed rows.
     */
    @Test
    @DisplayName("their finished status maps to the agent having stopped, and to nothing stronger")
    void finishedIsNotSuccess() {
        status.set(200);
        body.set("{\"id\":\"c0ffee\",\"execution_status\":\"finished\",\"items\":[]}");
        HarnessSession session = harness.open(spec());

        var stop = harness.run(session, new dev.tushar.forgestack.harness.Instruction("do it", 5), event -> {});

        assertThat(stop.reason()).isEqualTo(dev.tushar.forgestack.harness.StopReason.INSTRUCTION_FINISHED);
    }

    @Test
    @DisplayName("an agent that never stops is cut off at the ceiling rather than polled forever")
    void aRunThatNeverEndsIsBounded() {
        status.set(200);
        body.set("{\"id\":\"c0ffee\",\"execution_status\":\"running\",\"items\":[]}");
        HarnessSession session = harness.open(spec());

        var stop = harness.run(session, new dev.tushar.forgestack.harness.Instruction("do it", 3), event -> {});

        assertThat(stop.reason()).isEqualTo(dev.tushar.forgestack.harness.StopReason.BUDGET_EXHAUSTED);
    }

    @Test
    @DisplayName("pausing and closing something already gone is not an error")
    void cleanupIsIdempotent() {
        status.set(200);
        body.set("{\"id\":\"c0ffee\"}");
        HarnessSession session = harness.open(spec());
        status.set(404);

        assertThatCode(() -> harness.pause(session)).doesNotThrowAnyException();
        assertThatCode(() -> harness.close(session)).doesNotThrowAnyException();
    }

    private static AttemptSpec spec() {
        return new AttemptSpec(
                UUID.randomUUID(),
                "forgestack/java-21:latest",
                new AttemptSpec.WorkingCopy("/workspace", "abc123"),
                new ResourceLimits(2000, 4096, 8192, Duration.ofMinutes(30), 100, 500_000),
                EgressPolicy.DENY_ALL,
                Set.of("read_file", "apply_patch"));
    }
}

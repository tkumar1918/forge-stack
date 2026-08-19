package dev.tushar.forgestack.harness;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The conformance suite, run against the fake.
 *
 * <p>The first of what should eventually be several: an {@code OpenHandsHarnessTest} and a
 * {@code ClaudeAgentHarnessTest} extend the same contract and inherit the same assertions. Until one
 * of them exists, this file's real job is to keep the contract honest — a suite only ever run against
 * a fake drifts towards asserting what the fake happens to do.
 */
class InMemoryHarnessTest extends ExecutionHarnessContract {

    private final InMemoryHarness harness = new InMemoryHarness();

    @Override
    protected ExecutionHarness harness() {
        return harness;
    }

    @Override
    protected void induceSessionLoss(HarnessSession session) {
        harness.induceSessionLoss(session);
    }

    @Override
    protected void induceCapacityExhaustion() {
        harness.reportNoCapacity(true);
    }

    @Override
    protected String knownImage() {
        return "forgestack/java-21:latest";
    }

    /**
     * Leaked sandboxes are how a worker VM fills its disk on a Tuesday night.
     *
     * <p>Cheap to assert against a fake and impossible to forget once it is here; the same assertion
     * against a Docker adapter is the one that catches a missing {@code close} in a {@code finally}.
     */
    @AfterEach
    void nothingIsLeftRunning() {
        assertThat(harness.liveSessions())
                .as("every test must leave the harness empty")
                .isZero();
    }

    @Test
    @DisplayName("counts the tokens each step spent, so a ceiling can be enforced before it is passed")
    void tokensAreReportedPerStep() {
        HarnessSession session = harness.open(new AttemptSpec(
                java.util.UUID.randomUUID(),
                knownImage(),
                new AttemptSpec.WorkingCopy("/workspace", "abc123"),
                new ResourceLimits(2000, 4096, 8192, java.time.Duration.ofMinutes(30), 100, 500_000),
                EgressPolicy.DENY_ALL,
                java.util.Set.of("read_file", "apply_patch")));
        var events = new InMemoryHarness.RecordedEvents();

        harness.run(session, new Instruction("EDIT:a.txt", 5), events);

        assertThat(events.all())
                .filteredOn(HarnessEvent.TokensConsumed.class::isInstance)
                .isNotEmpty()
                // Cached tokens are tracked separately because prompt caching is the largest cost
                // lever there is, and a lever nobody measures is a lever nobody notices breaking.
                .allSatisfy(event -> assertThat(((HarnessEvent.TokensConsumed) event).cachedInputTokens())
                        .isPositive());
        harness.close(session);
    }
}

package dev.tushar.forgestack.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The conformance suite against the fake, plus the failures only a fake will produce on request. */
class InMemorySandboxTest extends SandboxProviderContract {

    private final InMemorySandbox sandbox = new InMemorySandbox();

    @Override
    protected SandboxProvider provider() {
        return sandbox;
    }

    @Override
    protected String knownImage() {
        return "forgestack/java-21:latest";
    }

    @Test
    @DisplayName("refusing for want of capacity is an answer the scheduler can act on")
    void capacityRefusal() {
        sandbox.reportNoCapacity(true);

        assertThatThrownBy(() -> provider().provision(spec(knownImage())))
                .isInstanceOf(SandboxException.CapacityExhausted.class);
    }

    @Test
    @DisplayName("a sandbox that vanishes mid-attempt is lost, not broken")
    void lossDuringUse() {
        SandboxHandle handle = provision();
        sandbox.induceLoss(handle);

        assertThatThrownBy(() -> provider()
                        .exec(handle, ExecRequest.of("echo", List.of("hi"), Duration.ofSeconds(5)), chunk -> {}))
                .isInstanceOf(SandboxException.SandboxLost.class);
        assertThat(sandbox.liveCount()).isZero();
    }
}

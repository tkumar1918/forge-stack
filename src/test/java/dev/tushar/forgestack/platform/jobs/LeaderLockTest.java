package dev.tushar.forgestack.platform.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.tushar.forgestack.support.AbstractIntegrationTest;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Which replica runs the periodic work.
 *
 * <p>Two {@link LeaderLock} instances stand in for two processes, which is exactly what they are:
 * each mints its own identity at construction, so a second instance is indistinguishable from a
 * second deployment as far as Redis is concerned.
 */
class LeaderLockTest extends AbstractIntegrationTest {

    private static final Duration TTL = Duration.ofSeconds(30);

    @Autowired
    private StringRedisTemplate redis;

    private LeaderLock first;
    private LeaderLock second;
    private String role;

    @BeforeEach
    void twoProcesses() {
        this.first = new LeaderLock(redis);
        this.second = new LeaderLock(redis);
        this.role = "test-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    @DisplayName("two processes do not both lead")
    void onlyOneLeaderAtATime() {
        assertThat(first.isLeaderFor(role, TTL)).isTrue();
        assertThat(second.isLeaderFor(role, TTL)).isFalse();
    }

    @Test
    @DisplayName("the leader stays the leader by asking again")
    void theLeaderRenews() {
        first.isLeaderFor(role, TTL);

        assertThat(first.isLeaderFor(role, TTL))
                .as("asking twice must extend the claim, not lose it to the NX that created it")
                .isTrue();
        assertThat(second.isLeaderFor(role, TTL)).isFalse();
    }

    /**
     * A leader that stops asking stops leading.
     *
     * <p>Nothing hands leadership over — the previous holder may be gone without ever knowing it was
     * the leader in the first place. The TTL lapsing is the entire handover protocol, which is what
     * makes it safe for the thing behind this lock to be safe to run twice anyway.
     */
    @Test
    @DisplayName("leadership passes on when the holder stops asking")
    void leadershipLapses() {
        assertThat(first.isLeaderFor(role, Duration.ofMillis(300))).isTrue();

        await().atMost(Duration.ofSeconds(5)).until(() -> second.isLeaderFor(role, TTL));

        assertThat(first.isLeaderFor(role, TTL))
                .as("and the old leader does not simply take it back")
                .isFalse();
    }

    @Test
    @DisplayName("leadership is per role, not global")
    void rolesAreIndependent() {
        assertThat(first.isLeaderFor(role, TTL)).isTrue();

        assertThat(second.isLeaderFor(role + "-other", TTL))
                .as("a scheduler and a reaper are different jobs and must not exclude each other")
                .isTrue();
    }
}

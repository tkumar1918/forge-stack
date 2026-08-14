package dev.tushar.forge.githubinstallation;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tushar.forge.support.AbstractIntegrationTest;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Eviction must not reach across tenants.
 *
 * <p>Seeds the cache directly rather than minting real tokens: the behaviour under test is which
 * keys eviction removes, and reaching GitHub to establish that would make the test slower, flakier,
 * and dependent on credentials the build does not have.
 */
class InstallationTokenServiceTest extends AbstractIntegrationTest {

    private static final long SUSPENDED = 111L;
    private static final long INNOCENT = 222L;

    @Autowired
    private InstallationTokenService tokens;

    @Autowired
    private StringRedisTemplate redis;

    @BeforeEach
    void seedCache() {
        redis.delete(redis.keys("forge:ghtok:*"));
        cache(SUSPENDED, "aaa");
        cache(SUSPENDED, "bbb");
        cache(INNOCENT, "ccc");
    }

    /**
     * The bug this test was written for: eviction used to delete every cached token in the
     * deployment, so suspending one customer's installation flushed every other tenant's tokens.
     */
    @Test
    void evictingOneInstallationLeavesOtherTenantsAlone() {
        tokens.evict(SUSPENDED);

        assertThat(redis.keys("forge:ghtok:" + SUSPENDED + ":*")).isEmpty();
        assertThat(redis.keys("forge:ghtok:" + INNOCENT + ":*"))
                .containsExactly("forge:ghtok:" + INNOCENT + ":ccc");
    }

    /** Evicting an installation with nothing cached is a no-op, not an error. */
    @Test
    void evictingAnUncachedInstallationIsHarmless() {
        tokens.evict(999L);

        assertThat(redis.keys("forge:ghtok:*")).hasSize(3);
    }

    private void cache(long installationId, String fingerprint) {
        redis.opsForValue()
                .set("forge:ghtok:" + installationId + ":" + fingerprint, "token", Duration.ofMinutes(50));
    }
}

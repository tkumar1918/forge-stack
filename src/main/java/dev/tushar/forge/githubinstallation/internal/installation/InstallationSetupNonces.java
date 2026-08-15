package dev.tushar.forge.githubinstallation.internal.installation;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * The {@code state} nonce carried through the GitHub App install redirect.
 *
 * <p>Binds the callback to the session that started it, so a third party cannot cause someone
 * else's browser to complete an install flow it never began.
 *
 * <p><strong>What this does not do.</strong> It does not prove the caller owns the installation.
 * An attacker can start the flow legitimately, obtain a valid nonce for their own session, and then
 * substitute a victim's {@code installation_id} in the callback — the nonce checks out, because it
 * really is theirs. Only comparing the installation's account against the caller's GitHub identity
 * stops that. The nonce and the ownership check answer different questions and neither is
 * sufficient alone.
 *
 * <p>Lives in Redis because losing one costs a restarted install, never correctness.
 */
@Component
public class InstallationSetupNonces {

    /** Long enough to pick repositories on GitHub, short enough that a leaked link goes stale. */
    private static final Duration TTL = Duration.ofMinutes(15);

    private static final String KEY_PREFIX = "forge:ghsetup:";
    private static final int NONCE_BYTES = 32;

    private final StringRedisTemplate redis;
    private final SecureRandom random = new SecureRandom();

    InstallationSetupNonces(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** Mints a nonce bound to {@code sessionId}. */
    public String issue(UUID sessionId) {
        byte[] raw = new byte[NONCE_BYTES];
        random.nextBytes(raw);
        String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);

        redis.opsForValue().set(KEY_PREFIX + nonce, sessionId.toString(), TTL);
        return nonce;
    }

    /**
     * Redeems a nonce, returning the session it was issued to.
     *
     * <p>Single use: the read deletes. A replayed callback finds nothing, so an install link that
     * leaks through a browser history or a referrer header cannot be used twice.
     */
    public Optional<UUID> consume(String nonce) {
        if (nonce == null || nonce.isBlank()) {
            return Optional.empty();
        }
        String sessionId = redis.opsForValue().getAndDelete(KEY_PREFIX + nonce);
        return Optional.ofNullable(sessionId).map(UUID::fromString);
    }
}

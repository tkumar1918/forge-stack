package dev.tushar.forgestack.githubinstallation.internal.installation;

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
 * <p>Binds the callback to the <em>user</em> who started it, so a third party cannot cause someone
 * else's browser to complete an install flow it never began.
 *
 * <p><strong>Why the user and not the session.</strong> It was the session, and that broke the first
 * real install: the flow spans the GitHub install screen, which takes minutes, and the user logged
 * in again while they were there. The callback arrived on a newer session, the nonce did not match,
 * and GitHub had installed an App that ForgeStack refused to record. *Same human, different session*
 * was never the threat this defends against — "someone else's browser" is, and that still requires
 * an attacker to be logged in as the victim, which they cannot be.
 *
 * <p><strong>What this does not do.</strong> It does not prove the caller owns the installation.
 * An attacker can start the flow legitimately, obtain a valid nonce for their own account, and then
 * substitute a victim's {@code installation_id} in the callback — the nonce checks out, because it
 * really is theirs. Only comparing the installation's account against the caller's GitHub identity
 * stops that. The nonce and the ownership check answer different questions and neither is
 * sufficient alone.
 *
 * <p>Lives in Redis because losing one costs a restarted install, never correctness.
 */
@Component
public class InstallationSetupNonces {

    /**
     * Long enough to pick repositories on GitHub, short enough that a leaked link goes stale.
     *
     * <p>Was fifteen minutes, which assumed a straight there-and-back. The real flow includes
     * reading GitHub's permission screen, choosing repositories, and — for a first-timer following
     * {@code docs/local-setup.md} — going off to verify the Setup URL on the way.
     */
    private static final Duration TTL = Duration.ofMinutes(30);

    private static final String KEY_PREFIX = "forgestack:ghsetup:";
    private static final int NONCE_BYTES = 32;

    private final StringRedisTemplate redis;
    private final SecureRandom random = new SecureRandom();

    InstallationSetupNonces(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** Mints a nonce bound to {@code userId}. */
    public String issue(UUID userId) {
        byte[] raw = new byte[NONCE_BYTES];
        random.nextBytes(raw);
        String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);

        redis.opsForValue().set(KEY_PREFIX + nonce, userId.toString(), TTL);
        return nonce;
    }

    /**
     * Redeems a nonce, returning the user it was issued to.
     *
     * <p>Single use: the read deletes. A replayed callback finds nothing, so an install link that
     * leaks through a browser history or a referrer header cannot be used twice.
     *
     * <p>An empty result means <em>absent</em> — expired, already spent, or never issued — and the
     * caller must not conflate that with a nonce belonging to someone else. Those are a stale link
     * and a possible attack respectively, and they were indistinguishable until the first one
     * happened for real and left nothing behind to diagnose.
     */
    public Optional<UUID> consume(String nonce) {
        if (nonce == null || nonce.isBlank()) {
            return Optional.empty();
        }
        String userId = redis.opsForValue().getAndDelete(KEY_PREFIX + nonce);
        return Optional.ofNullable(userId).map(UUID::fromString);
    }
}

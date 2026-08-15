package dev.tushar.forge.githubinstallation;

import dev.tushar.forge.githubinstallation.internal.GithubAppClient;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Mints and caches scoped GitHub installation tokens.
 *
 * <p>This is where {@link TokenScope} becomes a real credential. Tokens are cached until GitHub's
 * own stated expiry, less ten minutes, so one is never handed out close to expiring.
 *
 * <p><strong>Tokens are cached in Redis in plain text today.</strong> They are short-lived and
 * narrowly scoped, but this is a known gap: envelope encryption arrives with {@code
 * platform.crypto}. Until then, treat Redis as credential-bearing infrastructure.
 */
@Service
public class InstallationTokenService {

    private static final Logger log = LoggerFactory.getLogger(InstallationTokenService.class);

    /** Never hand out a token within this long of its expiry. */
    private static final Duration EXPIRY_HEADROOM = Duration.ofMinutes(10);

    /** Below this, caching is not worth the round trip; mint fresh next time instead. */
    private static final Duration MIN_CACHE_TTL = Duration.ofMinutes(1);

    private static final String CACHE_PREFIX = "forge:ghtok:";

    private final GithubAppClient github;
    private final StringRedisTemplate redis;

    InstallationTokenService(GithubAppClient github, StringRedisTemplate redis) {
        this.github = github;
        this.redis = redis;
    }

    /**
     * Returns a token carrying exactly {@code scope}, from cache when possible.
     *
     * <p>The cache key is the scope fingerprint, so widening or narrowing a scope always produces
     * a different key. A read-only caller can never be served a write-capable token.
     */
    public String tokenFor(TokenScope scope) {
        String cacheKey = cacheKey(scope.installationId(), scope.fingerprint());

        String cached = redis.opsForValue().get(cacheKey);
        if (cached != null) {
            return cached;
        }

        log.debug(
                "Minting installation token: installation={} repositories={} permissions={}",
                scope.installationId(),
                scope.repositories(),
                scope.permissions().keySet());
        GithubAppClient.InstallationToken minted = github.mintInstallationToken(scope);

        // Cache until GitHub's own expiry rather than a fixed guess: a token that turns out to be
        // short-lived must not outlive its usefulness in the cache.
        Duration ttl = Duration.between(Instant.now(), minted.expiresAt()).minus(EXPIRY_HEADROOM);
        if (ttl.compareTo(MIN_CACHE_TTL) >= 0) {
            redis.opsForValue().set(cacheKey, minted.token(), ttl);
        }
        return minted.token();
    }

    /**
     * The installation id is a key segment, not only an ingredient of the fingerprint.
     *
     * <p>{@link TokenScope#fingerprint()} hashes the installation id in, which makes keys unique
     * but unenumerable. Eviction has to match on the installation, so the id has to survive in the
     * clear.
     */
    private static String cacheKey(long installationId, String fingerprint) {
        return CACHE_PREFIX + installationId + ":" + fingerprint;
    }

    /**
     * Drops cached tokens for one installation — used when it is suspended or uninstalled.
     *
     * <p>Scoped to the installation: this Redis also holds queues, leases and rate limiters for
     * every workspace, and one customer's suspension must not flush another's cache.
     *
     * <p>{@code SCAN} rather than {@code KEYS}, which is O(whole keyspace) and blocks the event
     * loop. SCAN may miss a key written mid-scan; that costs one narrowly-scoped token living out
     * its remaining minutes, which is an acceptable trade for not stalling Redis.
     */
    public void evict(long installationId) {
        ScanOptions options = ScanOptions.scanOptions()
                .match(CACHE_PREFIX + installationId + ":*")
                .count(256)
                .build();

        List<String> doomed = new ArrayList<>();
        try (Cursor<String> cursor = redis.scan(options)) {
            cursor.forEachRemaining(doomed::add);
        }
        if (!doomed.isEmpty()) {
            redis.delete(doomed);
        }
        log.info("Evicted {} cached tokens for installation {}", doomed.size(), installationId);
    }
}

package dev.tushar.forge.githubinstallation;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import dev.tushar.forge.githubinstallation.internal.GithubAppJwtService;
import dev.tushar.forge.githubinstallation.internal.GithubAppProperties;
import org.springframework.web.client.RestClient;

/**
 * Mints and caches scoped GitHub installation tokens.
 *
 * <p>This is where {@link TokenScope} becomes a real credential. Tokens live one hour; the cache
 * holds them for fifty minutes so a token is never handed out close to expiry.
 *
 * <p><strong>Tokens are cached in Redis in plain text today.</strong> They are short-lived and
 * narrowly scoped, but this is a known gap: envelope encryption arrives with {@code
 * platform.crypto}. Until then, treat Redis as credential-bearing infrastructure.
 */
@Service
public class InstallationTokenService {

    private static final Logger log = LoggerFactory.getLogger(InstallationTokenService.class);

    /** GitHub tokens last 60 minutes. Ten minutes of headroom avoids handing out a stale one. */
    private static final Duration CACHE_TTL = Duration.ofMinutes(50);

    private static final String CACHE_PREFIX = "forge:ghtok:";

    private final GithubAppJwtService appJwt;
    private final StringRedisTemplate redis;
    private final RestClient restClient;

    InstallationTokenService(
            GithubAppJwtService appJwt,
            StringRedisTemplate redis,
            GithubAppProperties properties,
            RestClient.Builder restClientBuilder) {
        this.appJwt = appJwt;
        this.redis = redis;
        this.restClient = restClientBuilder
                .baseUrl(properties.apiBaseUrl())
                .defaultHeader("Accept", "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .build();
    }

    /** GitHub's response to a token request. */
    record TokenResponse(String token, Instant expires_at) {}

    /**
     * Returns a token carrying exactly {@code scope}, from cache when possible.
     *
     * <p>The cache key is the scope fingerprint, so widening or narrowing a scope always produces
     * a different key. A read-only caller can never be served a write-capable token.
     */
    public String tokenFor(TokenScope scope) {
        String cacheKey = CACHE_PREFIX + scope.fingerprint();

        String cached = redis.opsForValue().get(cacheKey);
        if (cached != null) {
            return cached;
        }

        String token = mint(scope);
        redis.opsForValue().set(cacheKey, token, CACHE_TTL);
        return token;
    }

    private String mint(TokenScope scope) {
        log.debug(
                "Minting installation token: installation={} repositories={} permissions={}",
                scope.installationId(),
                scope.repositories(),
                scope.permissions().keySet());

        TokenResponse response = restClient
                .post()
                .uri("/app/installations/{id}/access_tokens", scope.installationId())
                .header("Authorization", "Bearer " + appJwt.mintAppJwt())
                .body(Map.of(
                        "repositories", scope.repositories(),
                        "permissions", scope.permissions()))
                .retrieve()
                .body(TokenResponse.class);

        if (response == null || response.token() == null) {
            throw new IllegalStateException(
                    "GitHub returned no token for installation " + scope.installationId());
        }
        return response.token();
    }

    /** Drops cached tokens for an installation — used when it is suspended or uninstalled. */
    public void evictAll(long installationId) {
        // Scope fingerprints are opaque, so the cheap path is a scan over the small token keyspace.
        var keys = redis.keys(CACHE_PREFIX + "*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(List.copyOf(keys));
        }
        log.info("Evicted cached installation tokens after change to installation {}", installationId);
    }
}

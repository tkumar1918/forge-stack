package dev.tushar.forgestack.githubinstallation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The exact authority a minted installation token will carry.
 *
 * <p>GitHub lets a token request narrow both the repository set and the permission set below what
 * was installed. ForgeStack exploits that per task, so authority is not a runtime check a model could
 * argue past — it is the shape of the credential that exists:
 *
 * <pre>
 * installed permissions ∩ workspace policy ∩ task risk ceiling ∩ approvals = this scope
 * </pre>
 *
 * <p>A documentation-only task gets {@code contents: read} on one repository. Nothing else is
 * reachable with that token, however the agent behaves.
 *
 * @param installationId the GitHub installation to mint against
 * @param repositories repository names (without owner), never empty — an unscoped token would
 *     reach every repository in the installation
 * @param permissions requested permissions, a subset of what was installed
 */
public record TokenScope(long installationId, List<String> repositories, Map<String, String> permissions) {

    public TokenScope {
        if (repositories == null || repositories.isEmpty()) {
            throw new IllegalArgumentException(
                    "A token scope must name at least one repository; an unscoped token reaches the whole installation");
        }
        repositories = List.copyOf(repositories);
        // Sorted so that equivalent scopes produce an identical cache key regardless of the order
        // the caller happened to build them in.
        permissions = Map.copyOf(new TreeMap<>(permissions == null ? Map.of() : permissions));
    }

    /** Read-only: analysis and planning, which is most of what an attempt does. */
    public static TokenScope readOnly(long installationId, String repository) {
        return new TokenScope(installationId, List.of(repository), Map.of("contents", "read", "metadata", "read"));
    }

    /** Write code and open pull requests. The widest scope any task should ever hold. */
    public static TokenScope contribute(long installationId, String repository) {
        return new TokenScope(
                installationId,
                List.of(repository),
                Map.of("contents", "write", "pull_requests", "write", "metadata", "read"));
    }

    /**
     * Stable fingerprint of this scope, used as the cache key.
     *
     * <p>Identical scopes share one token, which keeps token minting off GitHub's per-installation
     * rate limit; different scopes never collide, so a read-only task can never be handed a
     * write-capable token from the cache.
     */
    public String fingerprint() {
        StringBuilder canonical = new StringBuilder()
                .append(installationId)
                .append('|')
                .append(String.join(",", repositories.stream().sorted().toList()))
                .append('|');
        permissions.forEach((key, value) -> canonical.append(key).append('=').append(value).append(';'));

        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}

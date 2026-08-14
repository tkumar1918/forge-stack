package dev.tushar.forge.githubinstallation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The scope fingerprint is a cache key for credentials, so a collision between two different
 * scopes would hand a read-only caller a write-capable token. These tests guard that.
 */
class TokenScopeTest {

    @Test
    @DisplayName("a scope must name at least one repository")
    void unscopedTokensAreRejected() {
        // A token with no repository list reaches every repository in the installation, which
        // defeats the entire point of per-task scoping.
        assertThatThrownBy(() -> new TokenScope(1L, List.of(), Map.of("contents", "read")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one repository");
    }

    @Test
    @DisplayName("equivalent scopes fingerprint identically regardless of ordering")
    void fingerprintIsOrderIndependent() {
        var first = new TokenScope(
                1L, List.of("api", "web"), Map.of("contents", "write", "pull_requests", "write"));
        var second = new TokenScope(
                1L, List.of("web", "api"), Map.of("pull_requests", "write", "contents", "write"));

        // Otherwise the cache misses constantly and we burn GitHub's token-minting rate limit.
        assertThat(first.fingerprint()).isEqualTo(second.fingerprint());
    }

    @Test
    @DisplayName("read-only and write scopes never share a cache key")
    void widerPermissionsProduceADifferentFingerprint() {
        var readOnly = TokenScope.readOnly(1L, "api");
        var contribute = TokenScope.contribute(1L, "api");

        // A collision here would silently hand an analysis-only task a token that can push.
        assertThat(readOnly.fingerprint()).isNotEqualTo(contribute.fingerprint());
    }

    @Test
    @DisplayName("the same scope against a different installation is a different key")
    void installationIsPartOfTheFingerprint() {
        assertThat(TokenScope.readOnly(1L, "api").fingerprint())
                .isNotEqualTo(TokenScope.readOnly(2L, "api").fingerprint());
    }

    @Test
    @DisplayName("a different repository is a different key")
    void repositoryIsPartOfTheFingerprint() {
        assertThat(TokenScope.readOnly(1L, "api").fingerprint())
                .isNotEqualTo(TokenScope.readOnly(1L, "web").fingerprint());
    }

    @Test
    @DisplayName("read-only scopes carry no write permission")
    void readOnlyScopeGrantsNoWrites() {
        assertThat(TokenScope.readOnly(1L, "api").permissions()).containsEntry("contents", "read");
        assertThat(TokenScope.readOnly(1L, "api").permissions().values()).doesNotContain("write");
    }

    @Test
    @DisplayName("even the widest task scope cannot administer or touch workflows")
    void contributeScopeStaysNarrow() {
        var permissions = TokenScope.contribute(1L, "api").permissions();

        // Workflows: write is deliberately never requested — an agent able to edit CI can edit
        // its own grading rubric, which makes verification meaningless.
        assertThat(permissions).doesNotContainKeys("administration", "workflows", "members", "secrets");
    }
}

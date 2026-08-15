package dev.tushar.forgestack.githublogin;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tushar.forgestack.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;

/**
 * Guards the single most consequential line of configuration in the product.
 *
 * <p>Logging into ForgeStack must not give the agent access to anybody's code. If {@code repo} ever
 * appears in the login scopes, every user who signs in silently hands over read/write access to
 * every repository they can see — and nothing would visibly break, which is exactly why it needs
 * a test rather than a code review.
 */
class GithubOAuthScopeTest extends AbstractIntegrationTest {

    @Autowired
    private ClientRegistrationRepository registrations;

    @Test
    @DisplayName("login requests only read:user and user:email")
    void loginRequestsMinimalScopes() {
        var github = ((InMemoryClientRegistrationRepository) registrations).findByRegistrationId("github");

        assertThat(github).isNotNull();
        assertThat(github.getScopes()).containsExactlyInAnyOrder("read:user", "user:email");
    }

    @Test
    @DisplayName("login never requests repository access")
    void loginNeverRequestsRepositoryAccess() {
        var github = ((InMemoryClientRegistrationRepository) registrations).findByRegistrationId("github");

        assertThat(github.getScopes())
                .as("repository access must come from an explicit GitHub App installation, not from login")
                .noneMatch(scope -> scope.equals("repo")
                        || scope.startsWith("repo:")
                        || scope.equals("write:packages")
                        || scope.equals("admin:org"));
    }
}

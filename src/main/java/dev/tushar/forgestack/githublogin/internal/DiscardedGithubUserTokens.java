package dev.tushar.forgestack.githublogin.internal;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;

/**
 * Throws the GitHub user access token away the moment Spring Security offers it for storage.
 *
 * <p>Login identifies a human and grants the agent nothing (§6). The token is used once, by
 * {@link ForgeStackOAuth2UserService}, to read the profile; after that it is a standing credential
 * able to act as that person on GitHub, and nothing in ForgeStack needs it — repository access
 * comes from short-lived, repository-scoped GitHub App installation tokens instead.
 *
 * <p>This class exists because <em>not configuring</em> something is still a decision. Boot
 * autoconfigures an {@code InMemoryOAuth2AuthorizedClientService} behind an
 * {@code AuthenticatedPrincipalOAuth2AuthorizedClientRepository}, so by default every token
 * ForgeStack has ever issued stays in a map on the heap, keyed by GitHub login, for the lifetime of
 * the process — nothing evicts it, including logout. The design said the token was discarded; the
 * framework quietly kept it.
 *
 * <p>Discarding on write rather than clearing up afterwards: there is no window in which the token
 * is retained, and no cleanup path that can be forgotten on a new logout route.
 */
class DiscardedGithubUserTokens implements OAuth2AuthorizedClientRepository {

    @Override
    public <T extends OAuth2AuthorizedClient> @Nullable T loadAuthorizedClient(
            String clientRegistrationId, Authentication principal, HttpServletRequest request) {
        // Nothing was stored, so there is nothing to hand back. A caller wanting to act as the user
        // on GitHub should fail here rather than find a credential waiting.
        return null;
    }

    @Override
    public void saveAuthorizedClient(
            OAuth2AuthorizedClient authorizedClient,
            Authentication principal,
            HttpServletRequest request,
            HttpServletResponse response) {
        // Deliberately empty: this is the line that would otherwise retain the token.
    }

    @Override
    public void removeAuthorizedClient(
            String clientRegistrationId,
            Authentication principal,
            HttpServletRequest request,
            HttpServletResponse response) {
        // Nothing to remove.
    }
}

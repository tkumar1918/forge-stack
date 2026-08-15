package dev.tushar.forgestack.githublogin.internal;

import dev.tushar.forgestack.iam.GithubProfile;
import dev.tushar.forgestack.iam.UserProfile;
import dev.tushar.forgestack.iam.UserProvisioningService;
import java.util.Map;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;

/**
 * Maps a completed GitHub OAuth exchange onto a ForgeStack user.
 *
 * <p>The access token in {@code OAuth2UserRequest} is used only to fetch the profile and is then
 * discarded — it is never persisted. A stored user OAuth token is a standing credential able to
 * act as that person on GitHub, and the agent never needs it: it uses short-lived,
 * repository-scoped GitHub App installation tokens instead.
 *
 * <p>That was true of this class and false of the running system until
 * {@link DiscardedGithubUserTokens} was wired in: Boot's autoconfigured authorized-client store kept
 * every token on the heap regardless of what this class did with it. Discarding the token here is
 * necessary and was never sufficient.
 */
@Service
public class ForgeStackOAuth2UserService implements OAuth2UserService<OAuth2UserRequest, OAuth2User> {

    /** Attribute carrying the ForgeStack user id, read by the success handler. */
    public static final String FORGE_USER_ID = "forge_user_id";

    private final DefaultOAuth2UserService delegate = new DefaultOAuth2UserService();
    private final UserProvisioningService provisioning;

    ForgeStackOAuth2UserService(UserProvisioningService provisioning) {
        this.provisioning = provisioning;
    }

    @Override
    public OAuth2User loadUser(OAuth2UserRequest request) throws OAuth2AuthenticationException {
        OAuth2User githubUser = delegate.loadUser(request);
        Map<String, Object> attributes = githubUser.getAttributes();

        String providerUserId = String.valueOf(attributes.get("id"));
        String login = stringAttribute(attributes, "login");
        String name = stringAttribute(attributes, "name");
        String avatarUrl = stringAttribute(attributes, "avatar_url");
        String email = stringAttribute(attributes, "email");

        if (email == null || email.isBlank()) {
            // GitHub omits the email when the user has it set to private, even with user:email
            // granted — it has to be fetched from /user/emails separately. Until that is wired
            // up, fall back to the documented noreply address so login still works.
            email = "%s@users.noreply.github.com".formatted(login);
        }

        UserProfile user = provisioning.provision(new GithubProfile(providerUserId, login, email, name, avatarUrl));

        Map<String, Object> enriched = new java.util.HashMap<>(attributes);
        enriched.put(FORGE_USER_ID, user.id().toString());

        return new DefaultOAuth2User(githubUser.getAuthorities(), enriched, "login");
    }

    private static String stringAttribute(Map<String, Object> attributes, String key) {
        Object value = attributes.get(key);
        return value == null ? null : String.valueOf(value);
    }
}

package dev.tushar.forge.githubinstallation.internal;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.tushar.forge.githubinstallation.TokenScope;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Every call Forge makes to GitHub authenticated <em>as the App</em>.
 *
 * <p>Owns the one {@link RestClient} the module uses, so the base URL and API version headers are
 * configured in a single place. They were previously set up inside the token service, which meant
 * a second caller had to repeat them — and two copies of a version header is how one of them
 * quietly stops being updated.
 *
 * <p>Transport only: no caching, no policy. {@code InstallationTokenService} layers caching over
 * {@link #mintInstallationToken}.
 */
@Component
public class GithubAppClient {

    private final GithubAppJwtService appJwt;
    private final RestClient restClient;

    GithubAppClient(GithubAppJwtService appJwt, GithubAppProperties properties, RestClient.Builder builder) {
        this.appJwt = appJwt;
        this.restClient = builder.baseUrl(properties.apiBaseUrl())
                .defaultHeader("Accept", "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .build();
    }

    /**
     * What GitHub says an installation is.
     *
     * <p>This is the authoritative record. A caller handing us an {@code installation_id} is making
     * an assertion; this is the evidence.
     */
    public record InstallationView(
            long id,
            Account account,
            @JsonProperty("repository_selection") String repositorySelection,
            Map<String, String> permissions,
            List<String> events,
            @JsonProperty("suspended_at") @Nullable Instant suspendedAt) {

        /** The user or organization the App is installed on. */
        public record Account(long id, String login, String type) {

            public static final String TYPE_USER = "User";

            public boolean isPersonal() {
                return TYPE_USER.equals(type);
            }
        }
    }

    /**
     * Reads an installation with the App JWT.
     *
     * <p>Empty when GitHub does not know the installation — an id that was guessed, or one for an
     * App that has since been uninstalled. Callers must treat empty as "reject", never as
     * "assume it is fine".
     */
    public Optional<InstallationView> fetchInstallation(long installationId) {
        InstallationView view = restClient
                .get()
                .uri("/app/installations/{id}", installationId)
                .header("Authorization", "Bearer " + appJwt.mintAppJwt())
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, (request, response) -> {
                    // Deliberately swallowed: a 404 here is the normal answer for a bad id, and
                    // the binding flow must not distinguish "no such installation" from "not
                    // yours" in what it tells the caller.
                })
                .body(InstallationView.class);

        return Optional.ofNullable(view);
    }

    /**
     * A minted installation token and GitHub's own stated expiry.
     *
     * <p>The expiry is handed back rather than assumed, so the caller can cache against what GitHub
     * actually said instead of a hardcoded guess that would rot silently if GitHub changed it.
     */
    public record InstallationToken(String token, @JsonProperty("expires_at") Instant expiresAt) {}

    /** Exchanges the App JWT for a token carrying exactly {@code scope}. */
    public InstallationToken mintInstallationToken(TokenScope scope) {
        InstallationToken response = restClient
                .post()
                .uri("/app/installations/{id}/access_tokens", scope.installationId())
                .header("Authorization", "Bearer " + appJwt.mintAppJwt())
                .body(Map.of("repositories", scope.repositories(), "permissions", scope.permissions()))
                .retrieve()
                .body(InstallationToken.class);

        if (response == null || response.token() == null || response.expiresAt() == null) {
            throw new IllegalStateException(
                    "GitHub returned no usable token for installation " + scope.installationId());
        }
        return response;
    }
}

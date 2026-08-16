package dev.tushar.forgestack.githubinstallation.internal.app;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.tushar.forgestack.githubinstallation.TokenScope;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Every call ForgeStack makes to GitHub authenticated <em>as the App</em>.
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

    private static final Logger log = LoggerFactory.getLogger(GithubAppClient.class);

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
        // exchange() rather than retrieve(): the body is read only after the status has been judged
        // successful. With retrieve(), an onStatus handler that returns normally means "handled,
        // carry on" — and carrying on means deserializing the *error* body into InstallationView.
        // GitHub's 404 is `{"message":"Not Found",…}` with no id, so that threw
        // "Cannot map null into type long" and surfaced as a 500 on the one path that must answer
        // 403: a guessed installation id. It went unnoticed because the test double replied 404
        // with an empty body, which deserialises to null and looks exactly like a clean miss.
        Lookup lookup = restClient
                .get()
                .uri("/app/installations/{id}", installationId)
                .header("Authorization", "Bearer " + appJwt.mintAppJwt())
                .exchange((request, response) -> {
                    HttpStatusCode status = response.getStatusCode();
                    return status.is2xxSuccessful()
                            ? new Lookup(Optional.ofNullable(response.bodyTo(InstallationView.class)), status)
                            : new Lookup(Optional.empty(), status);
                });

        if (lookup.view().isPresent()) {
            return lookup.view();
        }
        // Judged outside the exchange so the response is already closed — the failure path may make
        // a second call, and nesting one inside an open response is asking for trouble.
        handleLookupFailure(installationId, lookup.status());
        return Optional.empty();
    }

    private record Lookup(Optional<InstallationView> view, HttpStatusCode status) {}

    /**
     * What a 4xx on an installation lookup means, and — the part that matters — who it is about.
     *
     * <p>Only 404 is routine. Collapsing the rest into it is what turned a wrong App key into
     * "that installation is not yours": a message about the caller, for a fault that is ours.
     */
    private void handleLookupFailure(long installationId, HttpStatusCode status) {
        if (status.isSameCodeAs(HttpStatus.UNAUTHORIZED)) {
            // Not about this installation at all — GitHub is refusing the App credentials
            // themselves, so no binding by any user can succeed. Thrown rather than returned
            // empty, because the caller-facing rejection path would bury it. Safe to surface:
            // a 401 depends only on our configuration, never on the id asked for, so it tells
            // the caller nothing about whether that installation exists.
            throw new IllegalStateException("GitHub rejected the ForgeStack App credentials (401). Check "
                    + "forgestack.github.app.id and the private key — a well-formed key belonging to a "
                    + "different App fails exactly here.");
        }
        if (status.isSameCodeAs(HttpStatus.NOT_FOUND)) {
            // A 404 means one of two things that could not be further apart: the caller guessed an
            // installation id, or *our* App id is wrong. Verified against real GitHub — a JWT whose
            // `iss` is not a real App returns 404 "Integration not found", not 401. So the original
            // §1.1 fix, which only caught 401, still reported a misconfigured deployment as "that
            // installation is not yours" to every user who tried.
            //
            // Asking who we are separates them without parsing GitHub's prose. Only on the 404 path,
            // which is rare and already behind a valid nonce and an authenticated session.
            if (!appCredentialsRecognised()) {
                throw new IllegalStateException("GitHub does not recognise the ForgeStack App itself (404 "
                        + "'Integration not found'). Check forgestack.github.app.id — an id that is not a "
                        + "real App fails exactly here, and looks identical to a guessed installation id.");
            }
            // The ordinary answer for a guessed id. Callers must not be able to tell this from a
            // 403, so only the server-side record distinguishes them.
            log.debug("GitHub does not know installation {}", installationId);
            return;
        }
        // Usually a suspended App or an exhausted rate limit. Both are operator concerns, and both
        // were invisible while this was folded in with 404.
        log.warn("Installation lookup for {} refused with {}", installationId, status.value());
    }

    /**
     * Whether GitHub recognises the App these credentials claim to be.
     *
     * <p>{@code GET /app} answers for the App alone and names no installation, so it separates "your
     * id is wrong" from "that installation does not exist" without reading GitHub's message text —
     * which is prose, and not a contract.
     */
    private boolean appCredentialsRecognised() {
        return Boolean.TRUE.equals(restClient
                .get()
                .uri("/app")
                .header("Authorization", "Bearer " + appJwt.mintAppJwt())
                .exchange((request, response) -> response.getStatusCode().is2xxSuccessful()));
    }

    /** One repository as the installation-repositories listing reports it. */
    public record RepositoryView(
            long id,
            @JsonProperty("full_name") String fullName,
            @JsonProperty("private") boolean isPrivate,
            @JsonProperty("default_branch") @Nullable String defaultBranch,
            boolean archived) {}

    private record RepositoryPage(List<RepositoryView> repositories) {}

    /** GitHub caps this at 100. */
    private static final int PAGE_SIZE = 100;

    /** Stops the loop paging forever if GitHub keeps returning full pages. */
    private static final int MAX_PAGES = 50;

    /**
     * Lists every repository the installation exposes.
     *
     * <p>This is the one call that has to see the whole installation, so it cannot use
     * {@link TokenScope} — that type refuses to express an unscoped token, deliberately, because
     * everywhere else an unscoped token would be a mistake.
     *
     * <p>The token is narrowed the other way instead: {@code metadata: read} and nothing else, so
     * it can learn repository <em>names</em> and cannot read a line of code. It is minted here,
     * used here, and never returned — there is no way for a caller to obtain an installation-wide
     * token from this class.
     */
    public List<RepositoryView> listRepositories(long installationId) {
        String token = mintDiscoveryToken(installationId);
        List<RepositoryView> all = new ArrayList<>();

        for (int page = 1; page <= MAX_PAGES; page++) {
            int currentPage = page;
            RepositoryPage response = restClient
                    .get()
                    .uri(builder -> builder.path("/installation/repositories")
                            .queryParam("per_page", PAGE_SIZE)
                            .queryParam("page", currentPage)
                            .build())
                    .header("Authorization", "Bearer " + token)
                    .retrieve()
                    .body(RepositoryPage.class);

            if (response == null || response.repositories() == null || response.repositories().isEmpty()) {
                break;
            }
            all.addAll(response.repositories());
            if (response.repositories().size() < PAGE_SIZE) {
                break;
            }
        }
        return all;
    }

    /**
     * An installation-wide token limited to {@code metadata: read}.
     *
     * <p>Private on purpose. Omitting {@code repositories} is what makes it span the installation,
     * and that is only acceptable because the permission set cannot reach content.
     */
    private String mintDiscoveryToken(long installationId) {
        InstallationToken response = restClient
                .post()
                .uri("/app/installations/{id}/access_tokens", installationId)
                .header("Authorization", "Bearer " + appJwt.mintAppJwt())
                .body(Map.of("permissions", Map.of("metadata", "read")))
                .retrieve()
                .body(InstallationToken.class);

        if (response == null || response.token() == null) {
            throw new IllegalStateException("GitHub returned no discovery token for installation " + installationId);
        }
        return response.token();
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

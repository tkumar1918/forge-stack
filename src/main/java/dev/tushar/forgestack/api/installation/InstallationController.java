package dev.tushar.forgestack.api.installation;

import dev.tushar.forgestack.githubinstallation.InstallationBinding;
import dev.tushar.forgestack.githubinstallation.InstallationBindingResult;
import dev.tushar.forgestack.githubinstallation.InstallationBindingService;
import dev.tushar.forgestack.githublogin.ForgeStackPrincipal;
import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Connecting a GitHub App installation to the caller's workspace.
 *
 * <p>Transport only. The decision about whether a binding is allowed lives in
 * {@link InstallationBindingService}; this class turns its answer into a status code.
 */
@RestController
@RequestMapping("/api/installations")
class InstallationController {

    private final InstallationBindingService bindings;
    private final String appSlug;
    private final String setupRedirect;

    InstallationController(
            InstallationBindingService bindings,
            @Value("${forgestack.github.app.slug:}") String appSlug,
            @Value("${forgestack.github.app.setup-redirect:/}") String setupRedirect) {
        this.bindings = bindings;
        this.appSlug = appSlug;
        this.setupRedirect = setupRedirect;
    }

    /**
     * Sends the browser to GitHub to choose an account and repositories.
     *
     * <p>The {@code state} nonce comes back through the callback and ties it to this session.
     */
    @GetMapping("/start")
    ResponseEntity<Void> start(@AuthenticationPrincipal ForgeStackPrincipal principal) {
        String state = bindings.beginSetup(principal.userId());

        URI install = UriComponentsBuilder.fromUriString("https://github.com/apps/{slug}/installations/new")
                .queryParam("state", state)
                .build(appSlug);

        return ResponseEntity.status(HttpStatus.FOUND).location(install).build();
    }

    /**
     * Where GitHub returns the user after installing.
     *
     * <p>Configured as the App's Setup URL. {@code setup_action} is {@code install} or
     * {@code update}; both are handled identically, because a permission change on an existing
     * installation needs re-reading just as much as a new one does.
     *
     * <p>Sits under {@code /api/**}, so an expired session yields a bare 401 rather than a login
     * redirect. Acceptable while there is no browser client; it needs revisiting when there is.
     */
    @GetMapping("/callback")
    ResponseEntity<String> callback(
            @RequestParam("installation_id") long installationId,
            @RequestParam(value = "state", required = false) String state,
            @AuthenticationPrincipal ForgeStackPrincipal principal) {

        InstallationBindingResult result =
                bindings.completeSetup(installationId, state, principal.userId(), principal.activeWorkspaceId());

        return switch (result) {
            case InstallationBindingResult.Bound(InstallationBinding installation) ->
                ResponseEntity.status(HttpStatus.FOUND)
                        .location(URI.create(setupRedirect + "?installation=" + installation.accountLogin()))
                        .build();
            case InstallationBindingResult.Rejected(var reason) ->
                ResponseEntity.status(statusFor(reason))
                        .contentType(MediaType.TEXT_PLAIN)
                        .body(explain(reason));
        };
    }

    /**
     * Rejections deliberately do not distinguish "no such installation" from "not yours".
     *
     * <p>Both answer 403. Telling a caller that an id exists but belongs to someone else turns this
     * endpoint into an oracle for probing which installation ids are live.
     */
    private static HttpStatus statusFor(InstallationBindingResult.Reason reason) {
        return switch (reason) {
            case SETUP_STATE_EXPIRED, SETUP_STATE_FOREIGN -> HttpStatus.BAD_REQUEST;
            case UNKNOWN_INSTALLATION, NOT_YOUR_ACCOUNT -> HttpStatus.FORBIDDEN;
            case ORGANIZATION_NOT_SUPPORTED, ALREADY_BOUND_ELSEWHERE -> HttpStatus.CONFLICT;
        };
    }

    /**
     * What the browser is told.
     *
     * <p>GitHub redirects a real browser here, so an empty body is not a neutral choice — it renders
     * as "This page is not working" and the user learns nothing. That is how the first real install
     * failed: GitHub had installed the App, ForgeStack had refused it, and the page said nothing at
     * all.
     *
     * <p>The two setup-state reasons share one message even though the server distinguishes them,
     * and so do the two ownership reasons, for the oracle reason above. Plain text rather than a
     * template because there is no view layer yet and a browser renders it fine.
     */
    private static String explain(InstallationBindingResult.Reason reason) {
        return switch (reason) {
            case SETUP_STATE_EXPIRED, SETUP_STATE_FOREIGN ->
                "This setup link is no longer valid — it expires, it only works once, and it "
                        + "belongs to the account that started the flow. If you switched GitHub "
                        + "accounts, sign in again and start over from /api/installations/start.";
            case UNKNOWN_INSTALLATION, NOT_YOUR_ACCOUNT ->
                "That installation cannot be connected to the account you are signed in as. "
                        + "Check /api/session to see which GitHub account that is — it must be the "
                        + "one that owns the installation. Then start again from "
                        + "/api/installations/start.";
            case ORGANIZATION_NOT_SUPPORTED ->
                "ForgeStack cannot yet connect organization installations, only personal accounts. "
                        + "Install the App on your own account instead.";
            case ALREADY_BOUND_ELSEWHERE -> "That installation is already connected to another workspace.";
        };
    }
}

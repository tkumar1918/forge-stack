package dev.tushar.forge.api.session;

import dev.tushar.forge.githublogin.ForgePrincipal;
import dev.tushar.forge.iam.IamQueries;
import dev.tushar.forge.iam.UserProfile;
import dev.tushar.forge.iam.WorkspaceSummary;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The authenticated caller's session context.
 *
 * <p>The resource is the session rather than the user: the identity fields come from the user row,
 * but {@code activeWorkspaceId} comes from the server-side session and {@code workspaces} is what
 * that session may switch to. "Who am I and what may I see right now" is session state.
 */
@RestController
@RequestMapping("/api/session")
class SessionController {

    private final IamQueries iam;

    SessionController(IamQueries iam) {
        this.iam = iam;
    }

    record SessionView(
            UUID userId,
            String email,
            String displayName,
            String avatarUrl,
            UUID activeWorkspaceId,
            List<WorkspaceSummary> workspaces) {}

    @GetMapping
    ResponseEntity<SessionView> current(@AuthenticationPrincipal ForgePrincipal principal) {
        return iam.findUser(principal.userId())
                .map(user -> ResponseEntity.ok(toView(user, principal)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private SessionView toView(UserProfile user, ForgePrincipal principal) {
        return new SessionView(
                user.id(),
                user.email(),
                user.displayName(),
                user.avatarUrl(),
                principal.activeWorkspaceId(),
                iam.workspacesFor(user.id()));
    }
}

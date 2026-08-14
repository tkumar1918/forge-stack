package dev.tushar.forge.api;

import dev.tushar.forge.githubauth.ForgePrincipal;
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

@RestController
@RequestMapping("/api/me")
class MeController {

    private final IamQueries iam;

    MeController(IamQueries iam) {
        this.iam = iam;
    }

    record MeView(
            UUID userId,
            String email,
            String displayName,
            String avatarUrl,
            UUID activeWorkspaceId,
            List<WorkspaceSummary> workspaces) {}

    @GetMapping
    ResponseEntity<MeView> me(@AuthenticationPrincipal ForgePrincipal principal) {
        return iam.findUser(principal.userId())
                .map(user -> ResponseEntity.ok(toView(user, principal)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private MeView toView(UserProfile user, ForgePrincipal principal) {
        return new MeView(
                user.id(),
                user.email(),
                user.displayName(),
                user.avatarUrl(),
                principal.activeWorkspaceId(),
                iam.workspacesFor(user.id()));
    }
}

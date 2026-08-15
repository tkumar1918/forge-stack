package dev.tushar.forge.api.repository;

import dev.tushar.forge.githubinstallation.AvailableRepository;
import dev.tushar.forge.githubinstallation.ManagedRepositoryService;
import dev.tushar.forge.githubinstallation.ManagedRepositoryView;
import dev.tushar.forge.githubinstallation.RepositorySyncReport;
import dev.tushar.forge.githubinstallation.RepositorySyncService;
import dev.tushar.forge.githublogin.ForgePrincipal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Choosing which repositories Forge maintains.
 *
 * <p>Listing and enabling are separate operations on purpose: {@code GET} shows what the
 * installation exposes, and only {@code POST .../manage} asks Forge to look after one. Nothing
 * here can enable a repository as a side effect of reading.
 */
@RestController
@RequestMapping("/api/repositories")
class RepositoryController {

    private final RepositorySyncService sync;
    private final ManagedRepositoryService managed;

    RepositoryController(RepositorySyncService sync, ManagedRepositoryService managed) {
        this.sync = sync;
        this.managed = managed;
    }

    /** Everything this workspace could have Forge maintain, and whether it already does. */
    @GetMapping
    List<AvailableRepository> available(@AuthenticationPrincipal ForgePrincipal principal) {
        return sync.available(principal.activeWorkspaceId());
    }

    /**
     * Re-reads the repository list from GitHub.
     *
     * <p>Exists because the sync at install time is best-effort, and because a user who changes
     * which repositories the App can see needs a way to make Forge notice without reinstalling.
     */
    @PostMapping("/sync/{installationId}")
    ResponseEntity<RepositorySyncReport> resync(
            @PathVariable long installationId, @AuthenticationPrincipal ForgePrincipal principal) {
        return ResponseEntity.ok(sync.sync(principal.activeWorkspaceId(), installationId));
    }

    /** Asks Forge to start maintaining one repository. */
    @PostMapping("/{repositoryId}/manage")
    ResponseEntity<ManagedRepositoryView> manage(
            @PathVariable UUID repositoryId, @AuthenticationPrincipal ForgePrincipal principal) {

        return managed.enable(principal.activeWorkspaceId(), repositoryId, principal.userId())
                .map(ResponseEntity::ok)
                // 404 rather than 403: a repository in another workspace is invisible here, and
                // saying "forbidden" would confirm that the id exists somewhere.
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Stops Forge maintaining it. The repository stays available to re-enable. */
    @DeleteMapping("/{repositoryId}/manage")
    ResponseEntity<Void> unmanage(
            @PathVariable UUID repositoryId, @AuthenticationPrincipal ForgePrincipal principal) {

        boolean disabled = managed.disable(principal.activeWorkspaceId(), repositoryId, principal.userId());
        return disabled ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }
}

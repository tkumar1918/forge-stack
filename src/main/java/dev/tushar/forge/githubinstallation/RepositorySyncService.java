package dev.tushar.forge.githubinstallation;

import dev.tushar.forge.githubinstallation.internal.app.GithubAppClient;
import dev.tushar.forge.githubinstallation.internal.catalog.GithubRepositories;
import dev.tushar.forge.githubinstallation.internal.catalog.GithubRepository;
import dev.tushar.forge.githubinstallation.internal.catalog.ManagedRepositories;
import dev.tushar.forge.githubinstallation.internal.catalog.ManagedRepository;
import dev.tushar.forge.githubinstallation.internal.installation.GithubInstallation;
import dev.tushar.forge.githubinstallation.internal.installation.GithubInstallationRepository;
import dev.tushar.forge.platform.tenancy.TenantScope;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Keeps the record of which repositories an installation exposes in step with GitHub.
 *
 * <p>Writes only to the <em>available</em> side. Discovering a repository never makes Forge start
 * maintaining it — that takes a human, through {@link ManagedRepositoryService}. Keeping the two
 * writers apart is what makes "installation access is not consent" true in the code rather than
 * only in the documentation.
 */
@Service
public class RepositorySyncService {

    private static final Logger log = LoggerFactory.getLogger(RepositorySyncService.class);

    private final GithubAppClient github;
    private final GithubInstallationRepository installations;
    private final GithubRepositories repositories;
    private final ManagedRepositories managed;
    private final TenantScope tenantScope;

    RepositorySyncService(
            GithubAppClient github,
            GithubInstallationRepository installations,
            GithubRepositories repositories,
            ManagedRepositories managed,
            TenantScope tenantScope) {
        this.github = github;
        this.installations = installations;
        this.repositories = repositories;
        this.managed = managed;
        this.tenantScope = tenantScope;
    }

    /**
     * Pulls the current repository list for one installation and reconciles it.
     *
     * <p>Reconciles rather than replaces: repositories are matched on GitHub's numeric id, so a
     * rename updates a row instead of orphaning one. Anything GitHub no longer lists is marked
     * removed, never deleted.
     */
    public RepositorySyncReport sync(UUID workspaceId, long installationId) {
        List<GithubAppClient.RepositoryView> current = github.listRepositories(installationId);

        return tenantScope.runInTenant(workspaceId, () -> {
            GithubInstallation installation = installations
                    .findByInstallationId(installationId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Installation %d is not bound to this workspace".formatted(installationId)));

            Set<Long> seen = new HashSet<>();
            int added = 0;

            for (GithubAppClient.RepositoryView view : current) {
                seen.add(view.id());
                var existing = repositories.findByGithubInstallationIdAndGithubRepoId(installation.getId(), view.id());

                if (existing.isPresent()) {
                    existing.get().seenAgain(view.fullName(), view.isPrivate(), view.defaultBranch(), view.archived());
                } else {
                    repositories.save(GithubRepository.seen(
                            workspaceId,
                            installation.getId(),
                            view.id(),
                            view.fullName(),
                            view.isPrivate(),
                            view.defaultBranch(),
                            view.archived()));
                    added++;
                }
            }

            int removed = markMissingAsRemoved(installation.getId(), seen);
            log.info(
                    "Synced installation {}: {} visible, {} new, {} no longer visible",
                    installationId,
                    current.size(),
                    added,
                    removed);

            return new RepositorySyncReport(current.size(), added, removed);
        });
    }

    /**
     * Marks anything absent from the listing as gone, and flags managed repositories loudly.
     *
     * <p>A customer must never find out that Forge quietly stopped maintaining a repository three
     * weeks ago. Losing access moves the managed row to {@code ACCESS_LOST} rather than pausing or
     * deleting it, so the reason survives and can be shown.
     */
    private int markMissingAsRemoved(UUID installationRowId, Set<Long> stillVisible) {
        Instant now = Instant.now();
        int removed = 0;

        for (GithubRepository repository : repositories.findByGithubInstallationId(installationRowId)) {
            if (stillVisible.contains(repository.getGithubRepoId()) || repository.getRemovedAt() != null) {
                continue;
            }
            repository.noLongerVisible(now);
            removed++;

            managed.findByGithubRepositoryId(repository.getId()).ifPresent(managedRepository -> {
                log.warn(
                        "Lost access to managed repository {} — marking ACCESS_LOST",
                        repository.getFullName());
                managedRepository.accessLost();
            });
        }
        return removed;
    }

    /** Everything the workspace could choose to have maintained, and whether it already is. */
    public List<AvailableRepository> available(UUID workspaceId) {
        return tenantScope.runInTenant(workspaceId, () -> {
            List<GithubRepository> visible = repositories.findByWorkspaceIdAndRemovedAtIsNullOrderByFullName(workspaceId);

            Set<UUID> managedIds = managed.findByGithubRepositoryIdIn(
                            visible.stream().map(GithubRepository::getId).toList())
                    .stream()
                    .filter(row -> row.getStatus() == ManagedRepository.Status.ACTIVE)
                    .map(ManagedRepository::getGithubRepositoryId)
                    .collect(java.util.stream.Collectors.toSet());

            return visible.stream()
                    .map(repository -> new AvailableRepository(
                            repository.getId(),
                            repository.getFullName(),
                            repository.isPrivate(),
                            repository.getDefaultBranch(),
                            repository.isArchived(),
                            managedIds.contains(repository.getId())))
                    .toList();
        });
    }
}

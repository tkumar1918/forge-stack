package dev.tushar.forgestack.githubinstallation;

import dev.tushar.forgestack.audit.ActorType;
import dev.tushar.forgestack.audit.AuditLog;
import dev.tushar.forgestack.githubinstallation.internal.catalog.GithubRepositories;
import dev.tushar.forgestack.githubinstallation.internal.catalog.GithubRepository;
import dev.tushar.forgestack.githubinstallation.internal.catalog.ManagedRepositories;
import dev.tushar.forgestack.githubinstallation.internal.catalog.ManagedRepository;
import dev.tushar.forgestack.platform.tenancy.TenantScope;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Turning ForgeStack's maintenance of a repository on and off.
 *
 * <p>The only writer to {@code managed_repositories}, and every path through it starts with a
 * person choosing. Syncing repositories from GitHub cannot reach this class — which is the point.
 * A customer who installs the App on thirty repositories has granted access to thirty and asked
 * for maintenance on none.
 */
@Service
public class ManagedRepositoryService {

    private static final Logger log = LoggerFactory.getLogger(ManagedRepositoryService.class);

    private static final String RESOURCE_TYPE = "MANAGED_REPOSITORY";

    private final GithubRepositories repositories;
    private final ManagedRepositories managed;
    private final TenantScope tenantScope;
    private final AuditLog audit;

    ManagedRepositoryService(
            GithubRepositories repositories,
            ManagedRepositories managed,
            TenantScope tenantScope,
            AuditLog audit) {
        this.repositories = repositories;
        this.managed = managed;
        this.tenantScope = tenantScope;
        this.audit = audit;
    }

    /**
     * Starts maintaining a repository.
     *
     * <p>Empty when the repository is not one this workspace can see. Row-level security means a
     * repository belonging to another tenant simply is not there, so an id copied from elsewhere
     * cannot be enabled here.
     *
     * <p>Also empty when another workspace already maintains the same real repository. That is a
     * different reason for the same answer, and deliberately indistinguishable to the caller: the
     * controller renders both as 404 rather than 403, on the existing principle that saying
     * "forbidden" would confirm the repository exists somewhere. The distinction is recorded in the
     * log for operators, who do need it.
     */
    public Optional<ManagedRepositoryView> enable(UUID workspaceId, UUID repositoryId, UUID actorId) {
        try {
            return enableWithinTenant(workspaceId, repositoryId, actorId);
        } catch (DataIntegrityViolationException e) {
            // managed_repositories_single_writer fired. As with installation binding, the conflicting
            // row is invisible to this tenant's SELECT, so the collision can only surface here — the
            // database is what actually guarantees one writer per repository, not the lookup above.
            log.warn(
                    "Refused to maintain repository {} for workspace {}: another workspace already maintains it",
                    repositoryId,
                    workspaceId);
            return Optional.empty();
        }
    }

    private Optional<ManagedRepositoryView> enableWithinTenant(UUID workspaceId, UUID repositoryId, UUID actorId) {
        return tenantScope.runInTenant(workspaceId, () -> {
            Optional<GithubRepository> repository = repositories.findById(repositoryId);
            if (repository.isEmpty() || repository.get().getRemovedAt() != null) {
                // Enabling something ForgeStack cannot currently see would create a managed repository
                // that is broken from birth.
                return Optional.empty();
            }

            ManagedRepository row = managed.findByGithubRepositoryId(repositoryId)
                    .map(existing -> {
                        existing.reEnable(actorId);
                        return existing;
                    })
                    .orElseGet(() -> managed.save(ManagedRepository.enable(
                            workspaceId, repositoryId, repository.get().getGithubRepoId(), actorId)));

            audit.record(
                    workspaceId,
                    ActorType.HUMAN,
                    actorId,
                    "REPOSITORY_MANAGEMENT_ENABLED",
                    RESOURCE_TYPE,
                    row.getId(),
                    Map.of(
                            "repository", repository.get().getFullName(),
                            "autonomy_level", row.getAutonomyLevel().name()));

            log.info(
                    "ForgeStack now maintains {} at autonomy {}",
                    repository.get().getFullName(),
                    row.getAutonomyLevel());

            return Optional.of(view(row, repository.get()));
        });
    }

    /**
     * The maintenance record for one repository, if there is one.
     *
     * <p>Exposes {@code status} so callers can distinguish a human pausing maintenance from ForgeStack
     * losing access — the second is a problem to surface, the first is not.
     */
    public Optional<ManagedRepositoryView> find(UUID workspaceId, UUID repositoryId) {
        return tenantScope.runInTenant(
                workspaceId,
                () -> managed.findByGithubRepositoryId(repositoryId)
                        .flatMap(row -> repositories.findById(repositoryId).map(repository -> view(row, repository))));
    }

    /** Stops maintaining a repository. Idempotent: disabling something already off is fine. */
    public boolean disable(UUID workspaceId, UUID repositoryId, UUID actorId) {
        return tenantScope.runInTenant(workspaceId, () -> managed.findByGithubRepositoryId(repositoryId)
                .map(row -> {
                    row.disable();
                    audit.record(
                            workspaceId,
                            ActorType.HUMAN,
                            actorId,
                            "REPOSITORY_MANAGEMENT_DISABLED",
                            RESOURCE_TYPE,
                            row.getId(),
                            Map.of("repository_id", repositoryId.toString()));
                    return true;
                })
                .orElse(false));
    }

    private ManagedRepositoryView view(ManagedRepository row, GithubRepository repository) {
        return new ManagedRepositoryView(
                row.getId(),
                repository.getId(),
                repository.getFullName(),
                row.getStatus().name(),
                row.getAutonomyLevel());
    }
}

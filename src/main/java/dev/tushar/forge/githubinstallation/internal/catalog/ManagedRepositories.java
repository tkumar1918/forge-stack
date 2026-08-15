package dev.tushar.forge.githubinstallation.internal.catalog;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Stored opt-in decisions. See {@link GithubRepositories} for why these are named in the plural. */
public interface ManagedRepositories extends JpaRepository<ManagedRepository, UUID> {

    Optional<ManagedRepository> findByGithubRepositoryId(UUID githubRepositoryId);

    List<ManagedRepository> findByWorkspaceId(UUID workspaceId);

    List<ManagedRepository> findByGithubRepositoryIdIn(List<UUID> githubRepositoryIds);
}

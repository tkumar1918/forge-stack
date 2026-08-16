package dev.tushar.forgestack.githubinstallation.internal.catalog;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Stored repositories the installation exposes.
 *
 * <p>Named in the plural rather than {@code GithubRepositoryRepository}. "Repository" means two
 * unrelated things here — a Git repository and the Spring Data pattern — and stacking them reads
 * as a typo. Elsewhere in the codebase the {@code XRepository} convention holds; this package is
 * the exception, because the domain noun got there first.
 */
public interface GithubRepositories extends JpaRepository<GithubRepository, UUID> {

    /**
     * Looked up by workspace rather than by installation, because that is the row's identity.
     *
     * <p>Matching on the installation would miss a repository the workspace already knows through a
     * previous install of the App, and insert a duplicate beside it.
     */
    Optional<GithubRepository> findByWorkspaceIdAndGithubRepoId(UUID workspaceId, long githubRepoId);

    List<GithubRepository> findByGithubInstallationId(UUID installationId);

    /** What a user may choose from: visible now, so nothing ForgeStack has lost access to. */
    List<GithubRepository> findByWorkspaceIdAndRemovedAtIsNullOrderByFullName(UUID workspaceId);
}

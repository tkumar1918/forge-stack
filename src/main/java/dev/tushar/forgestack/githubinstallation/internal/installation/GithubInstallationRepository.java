package dev.tushar.forgestack.githubinstallation.internal.installation;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GithubInstallationRepository extends JpaRepository<GithubInstallation, UUID> {

    /**
     * Looks an installation up by GitHub's id.
     *
     * <p>Row-level security scopes this to the current workspace, so an installation bound to a
     * different tenant reads as absent rather than as a conflict. The binding flow relies on that:
     * it must not be able to tell a caller whether someone else already owns an installation.
     */
    Optional<GithubInstallation> findByInstallationId(long installationId);

    List<GithubInstallation> findByWorkspaceIdAndDeletedAtIsNull(UUID workspaceId);
}

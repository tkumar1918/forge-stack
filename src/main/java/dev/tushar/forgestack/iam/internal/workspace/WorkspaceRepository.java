package dev.tushar.forgestack.iam.internal.workspace;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface WorkspaceRepository extends JpaRepository<Workspace, UUID> {

    Optional<Workspace> findBySlug(String slug);

    boolean existsBySlug(String slug);

    @Query(
            """
            select w from Workspace w
            where w.id in (
                select m.id.workspaceId from WorkspaceMember m where m.id.userId = :userId
            )
            order by w.name
            """)
    List<Workspace> findAllForUser(UUID userId);
}

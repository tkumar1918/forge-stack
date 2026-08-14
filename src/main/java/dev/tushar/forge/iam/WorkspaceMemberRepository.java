package dev.tushar.forge.iam;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface WorkspaceMemberRepository extends JpaRepository<WorkspaceMember, WorkspaceMember.Key> {

    Optional<WorkspaceMember> findByIdWorkspaceIdAndIdUserId(UUID workspaceId, UUID userId);
}

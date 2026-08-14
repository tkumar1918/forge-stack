package dev.tushar.forge.iam;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The read surface other modules use.
 *
 * <p>Repositories stay package-private so no other module can reach past this and start issuing
 * arbitrary queries against IAM tables — the module's API is these methods, not its schema.
 */
@Service
@Transactional(readOnly = true)
public class IamQueries {

    private final UserRepository users;
    private final WorkspaceRepository workspaces;
    private final WorkspaceMemberRepository members;

    IamQueries(UserRepository users, WorkspaceRepository workspaces, WorkspaceMemberRepository members) {
        this.users = users;
        this.workspaces = workspaces;
        this.members = members;
    }

    public Optional<User> findUser(UUID userId) {
        return users.findById(userId);
    }

    public List<Workspace> workspacesFor(UUID userId) {
        return workspaces.findAllForUser(userId);
    }

    /**
     * The role a user holds in a workspace, if any.
     *
     * <p>Authorization must always be checked against this rather than against a workspace id
     * supplied by the client — a path parameter is a request, not a proof of membership.
     */
    public Optional<WorkspaceMember.Role> roleIn(UUID workspaceId, UUID userId) {
        return members.findByIdWorkspaceIdAndIdUserId(workspaceId, userId).map(WorkspaceMember::getRole);
    }

    public boolean isMember(UUID workspaceId, UUID userId) {
        return roleIn(workspaceId, userId).isPresent();
    }
}

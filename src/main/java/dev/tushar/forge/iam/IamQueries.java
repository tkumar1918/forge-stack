package dev.tushar.forge.iam;

import dev.tushar.forge.iam.internal.workspace.WorkspaceMemberRepository;
import dev.tushar.forge.iam.internal.workspace.WorkspaceRepository;
import dev.tushar.forge.iam.internal.user.UserRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The read surface other modules use.
 *
 * <p>Repositories and entities live in {@code iam.internal}, which Spring Modulith treats as
 * private to this module. The API is these methods and the records they return — not the schema.
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

    public Optional<UserProfile> findUser(UUID userId) {
        return users.findById(userId)
                .map(user -> new UserProfile(
                        user.getId(), user.getPrimaryEmail(), user.getDisplayName(), user.getAvatarUrl()));
    }

    public List<WorkspaceSummary> workspacesFor(UUID userId) {
        return workspaces.findAllForUser(userId).stream()
                .map(workspace ->
                        new WorkspaceSummary(workspace.getId(), workspace.getSlug(), workspace.getName()))
                .toList();
    }

    /**
     * The role a user holds in a workspace, if any.
     *
     * <p>Authorization must always be checked against this rather than against a workspace id
     * supplied by the client — a path parameter is a request, not proof of membership.
     */
    public Optional<WorkspaceRole> roleIn(UUID workspaceId, UUID userId) {
        return members.findByIdWorkspaceIdAndIdUserId(workspaceId, userId).map(member -> member.getRole());
    }

    public boolean isMember(UUID workspaceId, UUID userId) {
        return roleIn(workspaceId, userId).isPresent();
    }
}

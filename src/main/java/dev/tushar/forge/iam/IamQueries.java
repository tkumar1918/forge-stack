package dev.tushar.forge.iam;

import dev.tushar.forge.iam.internal.user.UserIdentity;
import dev.tushar.forge.iam.internal.user.UserIdentityRepository;
import dev.tushar.forge.iam.internal.user.UserRepository;
import dev.tushar.forge.iam.internal.workspace.WorkspaceMemberRepository;
import dev.tushar.forge.iam.internal.workspace.WorkspaceRepository;
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
    private final UserIdentityRepository identities;
    private final WorkspaceRepository workspaces;
    private final WorkspaceMemberRepository members;

    IamQueries(
            UserRepository users,
            UserIdentityRepository identities,
            WorkspaceRepository workspaces,
            WorkspaceMemberRepository members) {
        this.users = users;
        this.identities = identities;
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

    /**
     * The user's numeric GitHub account id, as GitHub reported it at login.
     *
     * <p>Exposed because it is the only thing Forge holds that can prove a person owns a GitHub
     * account. Binding a GitHub App installation compares it against the installation's
     * {@code account.id}, which is what stops someone claiming a stranger's installation.
     *
     * <p>The numeric id and not the login: logins are renameable and reusable, so a check against
     * one is a check against whoever holds that name today.
     */
    public Optional<String> githubUserId(UUID userId) {
        return identities
                .findByUserIdAndProvider(userId, UserIdentity.PROVIDER_GITHUB)
                .map(UserIdentity::getProviderUserId);
    }
}

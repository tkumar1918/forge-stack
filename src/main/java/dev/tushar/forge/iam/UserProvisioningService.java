package dev.tushar.forge.iam;

import dev.tushar.forge.iam.internal.user.User;
import dev.tushar.forge.iam.internal.user.UserIdentity;
import dev.tushar.forge.iam.internal.user.UserIdentityRepository;
import dev.tushar.forge.iam.internal.user.UserRepository;
import dev.tushar.forge.iam.internal.workspace.Workspace;
import dev.tushar.forge.iam.internal.workspace.WorkspaceMember;
import dev.tushar.forge.iam.internal.workspace.WorkspaceMemberRepository;
import dev.tushar.forge.iam.internal.workspace.WorkspaceRepository;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns a verified external identity into a Forge user, workspace, and membership.
 *
 * <p>Called on every login. First login provisions; later logins refresh the profile.
 */
@Service
public class UserProvisioningService {

    private final UserRepository users;
    private final UserIdentityRepository identities;
    private final WorkspaceRepository workspaces;
    private final WorkspaceMemberRepository members;

    UserProvisioningService(
            UserRepository users,
            UserIdentityRepository identities,
            WorkspaceRepository workspaces,
            WorkspaceMemberRepository members) {
        this.users = users;
        this.identities = identities;
        this.workspaces = workspaces;
        this.members = members;
    }

    @Transactional
    public UserProfile provision(GithubProfile profile) {
        User user = identities
                .findByProviderAndProviderUserId(UserIdentity.PROVIDER_GITHUB, profile.providerUserId())
                .map(identity -> refreshExisting(identity, profile))
                .orElseGet(() -> createNew(profile));

        return new UserProfile(user.getId(), user.getPrimaryEmail(), user.getDisplayName(), user.getAvatarUrl());
    }

    private User refreshExisting(UserIdentity identity, GithubProfile profile) {
        identity.updateLogin(profile.login());
        User user = users.findById(identity.getUserId())
                .orElseThrow(() -> new IllegalStateException(
                        "Identity %s references missing user %s".formatted(identity.getId(), identity.getUserId())));
        user.updateProfile(profile.name(), profile.avatarUrl());
        return user;
    }

    private User createNew(GithubProfile profile) {
        // Link by provider id first (done by the caller), then fall back to email. Matching on
        // email alone would be unsafe — GitHub emails are only trustworthy because we just
        // completed an OAuth exchange with GitHub for this specific account.
        User user = users.findByPrimaryEmail(User.normalizeEmail(profile.email()))
                .orElseGet(() -> users.save(User.create(profile.email(), profile.name(), profile.avatarUrl())));

        identities.save(UserIdentity.github(user.getId(), profile.providerUserId(), profile.login()));

        // Every user gets a personal workspace so there is always somewhere to install the
        // GitHub App. It grants no repository access on its own.
        Workspace workspace = workspaces.save(
                Workspace.create(uniqueSlug(profile.login()), "%s's workspace".formatted(profile.login())));
        members.save(WorkspaceMember.of(workspace.getId(), user.getId(), WorkspaceRole.OWNER, null));

        return user;
    }

    private String uniqueSlug(String login) {
        String base = login.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]", "-");
        if (!workspaces.existsBySlug(base)) {
            return base;
        }
        // Collisions are rare and not worth a retry loop; a short random suffix settles it.
        return base + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}

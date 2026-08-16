package dev.tushar.forgestack.githubinstallation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tushar.forgestack.githubinstallation.InstallationBindingResult.Bound;
import dev.tushar.forgestack.githubinstallation.InstallationBindingResult.Reason;
import dev.tushar.forgestack.githubinstallation.InstallationBindingResult.Rejected;
import dev.tushar.forgestack.iam.GithubProfile;
import dev.tushar.forgestack.iam.IamQueries;
import dev.tushar.forgestack.iam.UserProvisioningService;
import dev.tushar.forgestack.platform.tenancy.TenantScope;
import dev.tushar.forgestack.support.AbstractGithubAppTest;
import dev.tushar.forgestack.support.FakeGithub;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The anti-hijack contract for binding a GitHub App installation.
 *
 * <p>Runs against a real HTTP stub for GitHub rather than a mocked client, deliberately: the
 * response is snake_case JSON and the mapping to {@code InstallationView} is exactly the sort of
 * thing that compiles, passes a mocked test, and returns nulls against the real API.
 */
class InstallationBindingServiceTest extends AbstractGithubAppTest {

    @Autowired
    private InstallationBindingService bindings;

    @Autowired
    private UserProvisioningService provisioning;

    @Autowired
    private IamQueries iam;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TenantScope tenantScope;

    private UUID userId;
    private UUID workspaceId;
    private String githubUserId;

    @BeforeEach
    void provisionUser() {
        this.githubUserId = String.valueOf(FakeGithub.nextId());
        this.userId = newUser(githubUserId).id();
        this.workspaceId = iam.workspacesFor(userId).getFirst().id();
    }

    /**
     * The reason this class exists.
     *
     * <p>The attacker holds a stranger's installation id — they are guessable and leak into logs —
     * and a nonce that is entirely legitimate, because they started their own install flow. That
     * combination defeats CSRF protection completely: only checking the installation's account
     * against the caller's GitHub identity stops it.
     */
    @Test
    @DisplayName("binding a stranger's installation is rejected even with a valid nonce")
    void cannotBindAnInstallationOwnedBySomeoneElse() {
        long victimInstallation = FakeGithub.installation(999_001L, "victim", "User");

        String ownNonce = bindings.beginSetup(userId);
        var result = bindings.completeSetup(victimInstallation, ownNonce, userId, workspaceId);

        assertThat(result).isEqualTo(new Rejected(Reason.NOT_YOUR_ACCOUNT));
        assertThat(installationRowsIn(workspaceId, victimInstallation)).isZero();
    }

    @Test
    @DisplayName("binding your own installation succeeds and records the account")
    void bindsOwnInstallation() {
        long installationId = FakeGithub.installation(Long.parseLong(githubUserId), "octo", "User");

        String nonce = bindings.beginSetup(userId);
        var result = bindings.completeSetup(installationId, nonce, userId, workspaceId);

        assertThat(result).isInstanceOfSatisfying(Bound.class, bound -> {
            assertThat(bound.installation().installationId()).isEqualTo(installationId);
            assertThat(bound.installation().accountLogin()).isEqualTo("octo");
        });
        assertThat(installationRowsIn(workspaceId, installationId)).isEqualTo(1);
    }

    @Test
    @DisplayName("organization installs are refused rather than bound unverified")
    void organizationInstallsAreRefused() {
        // Confirming org admin rights needs the Members permission, which the App does not request.
        // Failing closed beats binding something we cannot prove the caller controls.
        long orgInstallation = FakeGithub.installation(Long.parseLong(githubUserId), "acme-corp", "Organization");

        String nonce = bindings.beginSetup(userId);
        var result = bindings.completeSetup(orgInstallation, nonce, userId, workspaceId);

        assertThat(result).isEqualTo(new Rejected(Reason.ORGANIZATION_NOT_SUPPORTED));
        assertThat(installationRowsIn(workspaceId, orgInstallation)).isZero();
    }

    @Test
    @DisplayName("a nonce cannot be replayed")
    void nonceIsSingleUse() {
        long installationId = FakeGithub.installation(Long.parseLong(githubUserId), "octo", "User");
        String nonce = bindings.beginSetup(userId);

        assertThat(bindings.completeSetup(installationId, nonce, userId, workspaceId))
                .isInstanceOf(Bound.class);

        // An install link leaking through browser history or a referrer header must be inert.
        assertThat(bindings.completeSetup(installationId, nonce, userId, workspaceId))
                .isEqualTo(new Rejected(Reason.SETUP_STATE_EXPIRED));
    }

    @Test
    @DisplayName("a nonce issued to another user is rejected as foreign, not as expired")
    void nonceIsBoundToItsUser() {
        long installationId = FakeGithub.installation(Long.parseLong(githubUserId), "octo", "User");
        String someoneElsesNonce = bindings.beginSetup(UUID.randomUUID());

        var result = bindings.completeSetup(installationId, someoneElsesNonce, userId, workspaceId);

        // The distinction is the point. A stale link and a nonce belonging to someone else look
        // identical to the caller and must not look identical in the log: one is routine, the other
        // is the CSRF attempt the nonce exists to stop.
        assertThat(result).isEqualTo(new Rejected(Reason.SETUP_STATE_FOREIGN));
    }

    @Test
    @DisplayName("a nonce that was never issued is expired, not foreign")
    void unknownNonceIsExpired() {
        long installationId = FakeGithub.installation(Long.parseLong(githubUserId), "octo", "User");

        var result = bindings.completeSetup(installationId, "never-issued", userId, workspaceId);

        assertThat(result).isEqualTo(new Rejected(Reason.SETUP_STATE_EXPIRED));
    }

    @Test
    @DisplayName("an installation GitHub does not know is rejected")
    void unknownInstallationIsRejected() {
        String nonce = bindings.beginSetup(userId);

        var result = bindings.completeSetup(404_404L, nonce, userId, workspaceId);

        assertThat(result).isEqualTo(new Rejected(Reason.UNKNOWN_INSTALLATION));
    }

    /**
     * A broken ForgeStack must not look like a suspicious user.
     *
     * <p>GitHub answers 401 when it refuses the App credentials themselves — a wrong app id, a
     * revoked key, a well-formed key belonging to a different App. That is a fact about our own
     * configuration, identical for every installation id, so folding it into the rejection path
     * told every user their installation was not theirs while the real fault sat in our config.
     *
     * <p>Surfacing it leaks nothing precisely because it does not depend on the id asked for.
     */
    @Test
    @DisplayName("GitHub rejecting ForgeStack's own credentials is a server fault, not a user rejection")
    void badAppCredentialsFailLoudly() {
        long installationId = FakeGithub.unauthorized();
        String nonce = bindings.beginSetup(userId);

        assertThatThrownBy(() -> bindings.completeSetup(installationId, nonce, userId, workspaceId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("401");
    }

    /**
     * The guarantee §7 leans on is the database constraint, not the application check.
     *
     * <p>Row-level security hides the existing row from the second workspace's lookup, so the code
     * genuinely tries to insert and the unique index refuses it. That is the intended shape: the
     * hijack fails even when application logic has no idea a conflict exists.
     */
    @Test
    @DisplayName("one installation cannot be bound to two workspaces")
    void installationCannotBeBoundTwice() {
        long installationId = FakeGithub.installation(Long.parseLong(githubUserId), "octo", "User");

        assertThat(bindings.completeSetup(
                        installationId, bindings.beginSetup(userId), userId, workspaceId))
                .isInstanceOf(Bound.class);

        UUID secondWorkspace = newWorkspaceFor(userId);
        var result = bindings.completeSetup(
                installationId, bindings.beginSetup(userId), userId, secondWorkspace);

        assertThat(result).isEqualTo(new Rejected(Reason.ALREADY_BOUND_ELSEWHERE));
        assertThat(installationRowsIn(workspaceId, installationId)).isEqualTo(1);
        assertThat(installationRowsIn(secondWorkspace, installationId)).isZero();
    }

    @Test
    @DisplayName("re-running setup on an installation this workspace already holds refreshes it")
    void rebindingInOwnWorkspaceIsARefresh() {
        long installationId = FakeGithub.installation(Long.parseLong(githubUserId), "octo", "User");
        bindings.completeSetup(installationId, bindings.beginSetup(userId), userId, workspaceId);

        // The user changed which repositories the App can see; GitHub is the authority on that.
        FakeGithub.reconfigure(installationId, Long.parseLong(githubUserId), "octo", "all");
        var result = bindings.completeSetup(
                installationId, bindings.beginSetup(userId), userId, workspaceId);

        assertThat(result).isInstanceOf(Bound.class);
        assertThat(installationRowsIn(workspaceId, installationId)).isEqualTo(1);
        assertThat(tenantScope.runInTenant(
                        workspaceId,
                        () -> jdbcTemplate.queryForObject(
                                "SELECT repository_selection FROM github_installations WHERE installation_id = ?",
                                String.class,
                                installationId)))
                .isEqualTo("all");
    }

    @Test
    @DisplayName("a rejected binding is audited, not silently dropped")
    void rejectionIsAudited() {
        long victimInstallation = FakeGithub.installation(999_002L, "victim", "User");

        bindings.completeSetup(victimInstallation, bindings.beginSetup(userId), userId, workspaceId);

        // A failed hijack attempt is exactly the event an operator needs to see.
        Integer rejections = tenantScope.runInTenant(
                workspaceId,
                () -> jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM audit_events WHERE action = 'INSTALLATION_BIND_REJECTED'",
                        Integer.class));
        assertThat(rejections).isEqualTo(1);
    }

    // --- helpers -------------------------------------------------------------------------------

    private dev.tushar.forgestack.iam.UserProfile newUser(String providerUserId) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return provisioning.provision(new GithubProfile(
                providerUserId, "user-" + suffix, suffix + "@example.com", "Test User", "https://example.com/a.png"));
    }

    private Integer installationRowsIn(UUID workspace, long installationId) {
        return tenantScope.runInTenant(
                workspace,
                () -> jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM github_installations WHERE installation_id = ?",
                        Integer.class,
                        installationId));
    }

    private UUID newWorkspaceFor(UUID owner) {
        UUID id = UUID.randomUUID();
        String slug = "ws-" + id.toString().substring(0, 8);
        jdbcTemplate.update("INSERT INTO workspaces (id, slug, name) VALUES (?, ?, ?)", id, slug, slug);
        jdbcTemplate.update(
                "INSERT INTO workspace_members (workspace_id, user_id, role) VALUES (?, ?, 'OWNER')", id, owner);
        return id;
    }

}

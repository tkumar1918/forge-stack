package dev.tushar.forgestack.githubinstallation;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tushar.forgestack.iam.GithubProfile;
import dev.tushar.forgestack.iam.IamQueries;
import dev.tushar.forgestack.iam.UserProfile;
import dev.tushar.forgestack.iam.UserProvisioningService;
import dev.tushar.forgestack.support.AbstractGithubAppTest;
import dev.tushar.forgestack.support.FakeGithub;
import dev.tushar.forgestack.support.FakeGithub.Repo;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Available versus managed: the distinction the whole product rests on.
 *
 * <p>Granting the App access to a repository says "you may see this". Only a person choosing it
 * says "maintain this". These tests exist to keep those two from collapsing into one, which is the
 * single most likely way this system would start acting on repositories nobody asked it to touch.
 */
class RepositoryCatalogTest extends AbstractGithubAppTest {

    @Autowired
    private InstallationBindingService bindings;

    @Autowired
    private RepositorySyncService sync;

    @Autowired
    private ManagedRepositoryService managed;

    @Autowired
    private UserProvisioningService provisioning;

    @Autowired
    private IamQueries iam;

    private UUID userId;
    private UUID workspaceId;
    private long installationId;

    // Held as fields so a test can re-expose the same repository. Repo.named() mints a fresh
    // GitHub id each call, and a repository with the same name but a new id is genuinely a
    // different repository — GitHub reuses names after a delete.
    private Repo alpha;
    private Repo beta;

    @BeforeEach
    void bindAnInstallation() {
        String githubUserId = String.valueOf(FakeGithub.nextId());
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        UserProfile user = provisioning.provision(new GithubProfile(
                githubUserId, "user-" + suffix, suffix + "@example.com", "Test User", "https://example.com/a.png"));
        this.userId = user.id();
        this.workspaceId = iam.workspacesFor(userId).getFirst().id();

        this.installationId = FakeGithub.installation(Long.parseLong(githubUserId), "octo", "User");
        this.alpha = Repo.named("octo/alpha");
        this.beta = Repo.named("octo/beta");
        FakeGithub.exposes(installationId, alpha, beta);

        UUID sessionId = UUID.randomUUID();
        bindings.completeSetup(installationId, bindings.beginSetup(sessionId), sessionId, userId, workspaceId);
    }

    /**
     * The rule that matters most.
     *
     * <p>Installing the App on repositories is not consent to be maintained. If syncing ever
     * created managed rows, a user granting access to thirty repositories would find ForgeStack working
     * on all thirty.
     */
    @Test
    @DisplayName("syncing discovers repositories but manages none of them")
    void syncNeverEnablesMaintenance() {
        var available = sync.available(workspaceId);

        assertThat(available).hasSize(2).extracting(AvailableRepository::fullName)
                .containsExactly("octo/alpha", "octo/beta");
        assertThat(available).allSatisfy(repository -> assertThat(repository.managed()).isFalse());
    }

    @Test
    @DisplayName("binding an installation syncs its repositories")
    void bindingSyncsRepositories() {
        // The sync at bind time is best-effort, but when GitHub is reachable it must actually run —
        // otherwise a user finishes the install flow and lands on an empty list.
        assertThat(sync.available(workspaceId)).isNotEmpty();
    }

    @Test
    @DisplayName("enabling one repository leaves the others alone")
    void enablingIsPerRepository() {
        UUID alpha = repositoryNamed("octo/alpha").id();

        var enabled = managed.enable(workspaceId, alpha, userId);

        assertThat(enabled).isPresent();
        assertThat(enabled.get().autonomyLevel()).isEqualTo(AutonomyLevel.PR_WITH_APPROVAL);
        assertThat(repositoryNamed("octo/alpha").managed()).isTrue();
        assertThat(repositoryNamed("octo/beta").managed()).isFalse();
    }

    @Test
    @DisplayName("a newly managed repository defaults to needing human approval")
    void defaultAutonomyIsTheCautiousOne() {
        UUID alpha = repositoryNamed("octo/alpha").id();

        var enabled = managed.enable(workspaceId, alpha, userId).orElseThrow();

        // The safe default is the one you get without thinking about it.
        assertThat(enabled.autonomyLevel()).isEqualTo(AutonomyLevel.PR_WITH_APPROVAL);
    }

    @Test
    @DisplayName("disabling stops maintenance but keeps the repository available")
    void disablingKeepsItAvailable() {
        UUID alpha = repositoryNamed("octo/alpha").id();
        managed.enable(workspaceId, alpha, userId);

        assertThat(managed.disable(workspaceId, alpha, userId)).isTrue();

        assertThat(repositoryNamed("octo/alpha").managed()).isFalse();
        assertThat(sync.available(workspaceId)).extracting(AvailableRepository::fullName).contains("octo/alpha");
    }

    @Test
    @DisplayName("re-enabling reuses the existing row rather than creating a second")
    void reEnablingIsIdempotent() {
        UUID alpha = repositoryNamed("octo/alpha").id();

        var first = managed.enable(workspaceId, alpha, userId).orElseThrow();
        managed.disable(workspaceId, alpha, userId);
        var second = managed.enable(workspaceId, alpha, userId).orElseThrow();

        // Same row: re-enabling must not lose the settings someone chose the first time.
        assertThat(second.id()).isEqualTo(first.id());
        assertThat(repositoryNamed("octo/alpha").managed()).isTrue();
    }

    @Test
    @DisplayName("a repository removed from the installation disappears from the choices")
    void removedRepositoriesLeaveTheCatalog() {
        FakeGithub.exposes(installationId, alpha);

        var report = sync.sync(workspaceId, installationId);

        assertThat(report.noLongerVisible()).isEqualTo(1);
        assertThat(sync.available(workspaceId)).extracting(AvailableRepository::fullName)
                .containsExactly("octo/alpha");
    }

    /**
     * Losing access to something ForgeStack was maintaining has to be loud.
     *
     * <p>A customer must never discover that ForgeStack quietly stopped maintaining a repository three
     * weeks ago. The managed row survives with {@code ACCESS_LOST} so the reason can be shown,
     * rather than being deleted or silently paused.
     */
    @Test
    @DisplayName("losing access to a managed repository is recorded, not silently dropped")
    void losingAccessToAManagedRepositoryIsVisible() {
        UUID beta = repositoryNamed("octo/beta").id();
        managed.enable(workspaceId, beta, userId);

        FakeGithub.exposes(installationId, alpha);
        sync.sync(workspaceId, installationId);

        assertThat(managedStatusOf(beta)).isEqualTo("ACCESS_LOST");
    }

    @Test
    @DisplayName("a repository id from another workspace cannot be enabled")
    void cannotEnableAnotherWorkspacesRepository() {
        UUID alpha = repositoryNamed("octo/alpha").id();

        String otherGithubId = String.valueOf(FakeGithub.nextId());
        UserProfile stranger = provisioning.provision(new GithubProfile(
                otherGithubId, "stranger-" + otherGithubId, otherGithubId + "@example.com", "Stranger", null));
        UUID strangerWorkspace = iam.workspacesFor(stranger.id()).getFirst().id();

        // Row-level security means the repository is not merely forbidden, it is not there.
        assertThat(managed.enable(strangerWorkspace, alpha, stranger.id())).isEmpty();
        assertThat(repositoryNamed("octo/alpha").managed()).isFalse();
    }

    @Test
    @DisplayName("a rename updates the repository rather than orphaning it")
    void renameKeepsTheSameRow() {
        UUID alphaBefore = repositoryNamed("octo/alpha").id();

        // Matching is on GitHub's numeric id, so a rename is the same repository.
        FakeGithub.exposes(installationId, new Repo(alpha.id(), "octo/alpha-renamed", true, "main", false), beta);
        sync.sync(workspaceId, installationId);

        assertThat(repositoryNamed("octo/alpha-renamed").id()).isEqualTo(alphaBefore);
    }

    private AvailableRepository repositoryNamed(String fullName) {
        return sync.available(workspaceId).stream()
                .filter(repository -> repository.fullName().equals(fullName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No repository named " + fullName));
    }

    private String managedStatusOf(UUID repositoryId) {
        return managed.find(workspaceId, repositoryId)
                .map(ManagedRepositoryView::status)
                .orElseThrow(() -> new AssertionError("Not managed: " + repositoryId));
    }
}

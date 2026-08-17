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
    private long accountId;
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

        this.accountId = Long.parseLong(githubUserId);
        this.installationId = FakeGithub.installation(accountId, "octo", "User");
        this.alpha = Repo.named("octo/alpha");
        this.beta = Repo.named("octo/beta");
        FakeGithub.exposes(installationId, alpha, beta);

        bindings.completeSetup(installationId, bindings.beginSetup(userId), userId, workspaceId);
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

    /**
     * Reinstalling the App must not duplicate the catalog.
     *
     * <p>GitHub issues a brand new {@code installation_id} on every install, so uninstall-then-
     * reinstall leaves the old installation row looking exactly as valid as the new one. Both then
     * carry their own copy of the same repositories, and nothing in the listing can tell them
     * apart. Found live: two rounds of this left 18 rows for 9 real repositories.
     *
     * <p>A repository's identity is the workspace it belongs to plus GitHub's numeric id. Which
     * installation currently exposes it is a mutable fact about it, not part of what it is.
     */
    @Test
    @DisplayName("reinstalling the App does not duplicate the repository catalog")
    void reinstallingDoesNotDuplicateTheCatalog() {
        long reinstalled = FakeGithub.installation(accountId, "octo", "User");
        FakeGithub.exposes(reinstalled, alpha, beta);

        bindings.completeSetup(reinstalled, bindings.beginSetup(userId), userId, workspaceId);

        assertThat(sync.available(workspaceId)).extracting(AvailableRepository::fullName)
                .containsExactly("octo/alpha", "octo/beta");
    }

    /**
     * The opt-in has to survive a reinstall.
     *
     * <p>If a reinstall created fresh repository rows, the {@code managed_repositories} row would
     * still point at the old ones — so a repository the user had chosen would silently come back
     * unmanaged while still appearing in the list. Losing that consent quietly is the same class of
     * failure as losing access quietly.
     */
    @Test
    @DisplayName("a managed repository stays managed across a reinstall")
    void reinstallingKeepsMaintenanceEnabled() {
        managed.enable(workspaceId, repositoryNamed("octo/alpha").id(), userId);

        long reinstalled = FakeGithub.installation(accountId, "octo", "User");
        FakeGithub.exposes(reinstalled, alpha, beta);
        bindings.completeSetup(reinstalled, bindings.beginSetup(userId), userId, workspaceId);

        // Asserted over every matching row rather than through repositoryNamed(), which takes the
        // first match: with a duplicate present that picks one of two arbitrarily and would report
        // consent intact while an unmanaged twin sat beside it.
        assertThat(sync.available(workspaceId))
                .filteredOn(repository -> repository.fullName().equals("octo/alpha"))
                .singleElement()
                .satisfies(repository -> assertThat(repository.managed()).isTrue());
    }

    /**
     * Reinstalling with a narrower selection must not leave the old repositories behind.
     *
     * <p>Found in real use: install with all repositories, uninstall, reinstall picking four. The
     * four synced correctly, but the five the old installation had exposed stayed in the catalog
     * with {@code removed_at} null, so {@code GET /api/repositories} listed nine — four reachable
     * and five that ForgeStack could no longer touch at all. Confirmed against GitHub: the old
     * installation answers 404.
     *
     * <p>No webhook is needed to know this. GitHub allows one installation of an App per account
     * and never revives an id, so a second live installation for the same account proves the first
     * is dead.
     */
    @Test
    @DisplayName("reinstalling with fewer repositories retires the ones the old installation exposed")
    void reinstallingWithANarrowerSelectionRetiresTheRest() {
        long reinstalled = FakeGithub.installation(accountId, "octo", "User");
        FakeGithub.exposes(reinstalled, alpha);

        bindings.completeSetup(reinstalled, bindings.beginSetup(userId), userId, workspaceId);

        assertThat(sync.available(workspaceId)).extracting(AvailableRepository::fullName)
                .as("beta was only ever reachable through the installation that has been replaced")
                .containsExactly("octo/alpha");
    }

    /** Losing a repository to a replaced installation is as loud as losing it any other way. */
    @Test
    @DisplayName("a managed repository dropped by a reinstall becomes ACCESS_LOST")
    void managedRepositoryDroppedByAReinstallIsFlagged() {
        UUID beta = repositoryNamed("octo/beta").id();
        managed.enable(workspaceId, beta, userId);

        long reinstalled = FakeGithub.installation(accountId, "octo", "User");
        FakeGithub.exposes(reinstalled, alpha);
        bindings.completeSetup(reinstalled, bindings.beginSetup(userId), userId, workspaceId);

        assertThat(managedStatusOf(beta)).isEqualTo("ACCESS_LOST");
    }

    /**
     * GitHub's own "Redirect on update" carries no nonce, and it is not an error.
     *
     * <p>Changing repository access from GitHub's settings sends the user to the setup URL with an
     * {@code installation_id} and no {@code state}. That was being refused as a stale link — with a
     * message suggesting the user had switched accounts — while the change itself had already taken
     * effect on GitHub, so ForgeStack's catalog silently disagreed with reality until someone
     * happened to call sync.
     */
    @Test
    @DisplayName("GitHub's update redirect carries no nonce and still refreshes the catalog")
    void updateRedirectWithoutANonceRefreshesTheCatalog() {
        FakeGithub.exposes(installationId, alpha);

        var result = bindings.completeSetup(installationId, null, userId, workspaceId);

        assertThat(result).isInstanceOf(InstallationBindingResult.Bound.class);
        assertThat(sync.available(workspaceId)).extracting(AvailableRepository::fullName)
                .containsExactly("octo/alpha");
    }

    /**
     * The one thing the nonce still guards.
     *
     * <p>Refreshing a binding the workspace already holds grants nothing new, which is what makes
     * accepting a missing nonce safe. Creating one does grant something, so it still has to start
     * from {@code /api/installations/start}.
     */
    @Test
    @DisplayName("a callback with no nonce cannot connect an installation for the first time")
    void updateRedirectCannotCreateANewBinding() {
        long unconnected = FakeGithub.installation(accountId, "octo", "User");
        FakeGithub.exposes(unconnected, alpha);

        var result = bindings.completeSetup(unconnected, null, userId, workspaceId);

        assertThat(result)
                .isEqualTo(new InstallationBindingResult.Rejected(
                        InstallationBindingResult.Reason.SETUP_NOT_STARTED_HERE));
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

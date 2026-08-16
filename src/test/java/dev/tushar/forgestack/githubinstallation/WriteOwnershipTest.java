package dev.tushar.forgestack.githubinstallation;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tushar.forgestack.iam.GithubProfile;
import dev.tushar.forgestack.iam.IamQueries;
import dev.tushar.forgestack.iam.UserProfile;
import dev.tushar.forgestack.iam.UserProvisioningService;
import dev.tushar.forgestack.platform.tenancy.TenantScope;
import dev.tushar.forgestack.support.AbstractGithubAppTest;
import dev.tushar.forgestack.support.FakeGithub;
import dev.tushar.forgestack.support.FakeGithub.Repo;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * At most one workspace maintains any real GitHub repository.
 *
 * <p>Two workspaces writing to one repository does not just make coordination hard, it voids
 * guarantees already built. {@code github_action_log} is unique on
 * {@code (workspace_id, fingerprint)}, so a second writer gets a second idempotency ledger and
 * neither can see the other's — the protection against retrying a mutation GitHub already applied
 * stops working, silently.
 *
 * <p><strong>None of this is reachable through the API today</strong>, and the setup here says so
 * out loud: it takes two installations, on two GitHub accounts, both exposing one repository, which
 * GitHub itself would never produce. The constraint exists for the shared-installation model that
 * has not been built. Testing it against the state it defends is the only way to know it works
 * before that state can occur — the alternative is discovering it in production, on the one path
 * where being wrong means two agents pushing to one branch.
 */
class WriteOwnershipTest extends AbstractGithubAppTest {

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

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TenantScope tenantScope;

    /** The one real repository both workspaces can see. */
    private Repo shared;

    private UUID ownerUserId;
    private UUID ownerWorkspace;
    private UUID rivalUserId;
    private UUID rivalWorkspace;

    @BeforeEach
    void twoWorkspacesSeeingOneRepository() {
        this.shared = Repo.named("acme/auth-service");

        Tenant owner = provisionTenantSeeing(shared);
        this.ownerUserId = owner.userId();
        this.ownerWorkspace = owner.workspaceId();

        Tenant rival = provisionTenantSeeing(shared);
        this.rivalUserId = rival.userId();
        this.rivalWorkspace = rival.workspaceId();
    }

    @Test
    @DisplayName("two workspaces cannot both maintain one real repository")
    void onlyOneWorkspaceMayMaintainARepository() {
        assertThat(managed.enable(ownerWorkspace, repositoryIn(ownerWorkspace), ownerUserId))
                .as("the first workspace to claim the repository gets it")
                .isPresent();

        assertThat(managed.enable(rivalWorkspace, repositoryIn(rivalWorkspace), rivalUserId))
                .as("the second must be refused, by the database rather than by a lookup")
                .isEmpty();

        assertThat(activeWriterCount()).isEqualTo(1);
    }

    /**
     * Pausing releases the claim.
     *
     * <p>The deliberate half of the decision, pinned here so it stays a decision. A workspace that
     * has stopped maintaining a repository should not hold a global lock on it that nobody else can
     * see or appeal — so the index is partial on {@code ACTIVE} rather than covering every row.
     */
    @Test
    @DisplayName("pausing maintenance releases the claim for another workspace")
    void pausingReleasesTheClaim() {
        UUID ownersRow = repositoryIn(ownerWorkspace);
        managed.enable(ownerWorkspace, ownersRow, ownerUserId);
        managed.disable(ownerWorkspace, ownersRow, ownerUserId);

        assertThat(managed.enable(rivalWorkspace, repositoryIn(rivalWorkspace), rivalUserId))
                .as("nobody is maintaining it, so it can be picked up")
                .isPresent();

        assertThat(activeWriterCount()).isEqualTo(1);
    }

    /**
     * The sharp edge of the predicate above, and the reason it is worth a test of its own.
     *
     * <p>Releasing on pause means re-enabling can fail. That has to read as a refusal the caller can
     * act on, not as a crash — the constraint violation surfaces from the commit, well after the
     * lookup that would normally have caught a conflict.
     */
    @Test
    @DisplayName("re-enabling a repository claimed in the meantime is refused, not a crash")
    void reEnablingAfterLosingTheClaimIsRefused() {
        UUID ownersRow = repositoryIn(ownerWorkspace);
        managed.enable(ownerWorkspace, ownersRow, ownerUserId);
        managed.disable(ownerWorkspace, ownersRow, ownerUserId);
        managed.enable(rivalWorkspace, repositoryIn(rivalWorkspace), rivalUserId);

        assertThat(managed.enable(ownerWorkspace, ownersRow, ownerUserId))
                .as("the original owner does not get it back by default")
                .isEmpty();

        assertThat(activeWriterCount()).isEqualTo(1);
    }

    /**
     * A tripwire, not a behaviour test.
     *
     * <p>Every reason the invariant above is currently unreachable rests on this one constraint:
     * one GitHub account has exactly one installation of the App, and a unique
     * {@code installation_id} binds it to exactly one workspace, so a repository can only ever
     * reach one workspace's catalog. Several things are safe only because of that and would have to
     * change with it — most importantly {@link TokenScope}, which has no workspace component, so two
     * workspaces sharing an installation would compute the same cache fingerprint and share a
     * credential.
     *
     * <p>If this fails, the policy was changed on purpose. The failure is the checklist.
     */
    @Test
    @DisplayName("installations remain bound to exactly one workspace")
    void installationRemainsBoundToOneWorkspace() {
        List<String> uniqueOnInstallationId = jdbcTemplate.queryForList(
                """
                SELECT indexdef FROM pg_indexes
                 WHERE tablename = 'github_installations'
                   AND indexdef LIKE '%UNIQUE%'
                   AND indexdef LIKE '%installation_id%'
                """,
                String.class);

        assertThat(uniqueOnInstallationId)
                .as(
                        """
                        installation_id is no longer uniquely constrained, so one installation can now \
                        serve several workspaces. Before shipping that, these must change with it:
                          - TokenScope: add a workspace component to the record and its fingerprint, or \
                        two workspaces requesting the same scope will share one cached credential
                          - RepositorySyncService.available(): decide what a workspace sees when an \
                        installation it does not own exposes a repository
                          - repository concurrency: key the semaphore on github_repo_id, never on a \
                        workspace-local ManagedRepository id
                          - WriteOwnershipTest above stops being hypothetical and becomes reachable""")
                .isNotEmpty();
    }

    // ---------------------------------------------------------------------------------------

    private record Tenant(UUID userId, UUID workspaceId) {}

    /**
     * A fresh user, workspace, and installation, all seeing {@code repo}.
     *
     * <p>Two installations exposing the same repository is not a state GitHub can produce — a
     * repository has one owning account, and an account has one installation. It is constructed
     * deliberately, because it is exactly the state a shared-installation model would create.
     */
    private Tenant provisionTenantSeeing(Repo repo) {
        String githubUserId = String.valueOf(FakeGithub.nextId());
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        UserProfile user = provisioning.provision(new GithubProfile(
                githubUserId, "user-" + suffix, suffix + "@example.com", "Test User", null));
        UUID workspaceId = iam.workspacesFor(user.id()).getFirst().id();

        long installationId = FakeGithub.installation(Long.parseLong(githubUserId), "acme-" + suffix, "User");
        FakeGithub.exposes(installationId, repo);
        bindings.completeSetup(installationId, bindings.beginSetup(user.id()), user.id(), workspaceId);

        return new Tenant(user.id(), workspaceId);
    }

    /** The workspace-local row id for the shared repository — different in each workspace. */
    private UUID repositoryIn(UUID workspaceId) {
        return sync.available(workspaceId).stream()
                .filter(repository -> repository.fullName().equals(shared.fullName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Workspace " + workspaceId + " cannot see " + shared.fullName()))
                .id();
    }

    /**
     * Counted from the table inside each tenant's own scope, then summed.
     *
     * <p>Not one query across both: the application role is subject to row-level security and holds
     * no BYPASSRLS, so an unscoped count returns zero however many rows exist. Summing per tenant is
     * the only honest way to ask, and it demonstrates the property that makes this constraint
     * necessary in the first place — neither workspace can see the other's claim, so nothing short
     * of the database can arbitrate between them.
     */
    private int activeWriterCount() {
        return activeWritersVisibleIn(ownerWorkspace) + activeWritersVisibleIn(rivalWorkspace);
    }

    private int activeWritersVisibleIn(UUID workspaceId) {
        return tenantScope.runInTenant(workspaceId, () -> {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM managed_repositories WHERE github_repo_id = ? AND status = 'ACTIVE'",
                    Integer.class,
                    shared.id());
            return count == null ? 0 : count;
        });
    }
}

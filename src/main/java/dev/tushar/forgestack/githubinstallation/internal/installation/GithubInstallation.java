package dev.tushar.forgestack.githubinstallation.internal.installation;

import dev.tushar.forgestack.githubinstallation.internal.app.GithubAppClient;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

/**
 * A GitHub App installation bound to one ForgeStack workspace.
 *
 * <p>The bond is one-way and exclusive: {@code installation_id} carries a unique constraint, so the
 * database itself refuses to let the same installation appear in two workspaces. That is deliberate
 * — it means an installation hijack fails even if the application-level check is ever bypassed.
 *
 * <p>{@code permissions} records what GitHub actually granted. It is the ceiling on every token
 * ForgeStack can mint: policy narrows below it and can never widen past it.
 */
@Entity
@Table(name = "github_installations")
public class GithubInstallation {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "workspace_id", nullable = false, updatable = false)
    private UUID workspaceId;

    @Column(name = "installation_id", nullable = false, updatable = false)
    private long installationId;

    @Column(name = "account_login", nullable = false)
    private String accountLogin;

    @Column(name = "account_type", nullable = false)
    private String accountType;

    @Column(name = "account_id", nullable = false)
    private long accountId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "permissions", nullable = false)
    private Map<String, String> permissions;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "events", nullable = false)
    private List<String> events;

    @Column(name = "repository_selection")
    private @Nullable String repositorySelection;

    @Column(name = "installed_by_user_id")
    private @Nullable UUID installedByUserId;

    @Column(name = "suspended_at")
    private @Nullable Instant suspendedAt;

    @Column(name = "deleted_at")
    private @Nullable Instant deletedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected GithubInstallation() {}

    public static GithubInstallation bind(
            UUID workspaceId, UUID installedByUserId, GithubAppClient.InstallationView view) {

        GithubInstallation installation = new GithubInstallation();
        Instant now = Instant.now();
        installation.id = UUID.randomUUID();
        installation.workspaceId = workspaceId;
        installation.installationId = view.id();
        installation.accountLogin = view.account().login();
        installation.accountType = view.account().type();
        installation.accountId = view.account().id();
        installation.permissions = view.permissions() == null ? Map.of() : Map.copyOf(view.permissions());
        installation.events = view.events() == null ? List.of() : List.copyOf(view.events());
        installation.repositorySelection = view.repositorySelection();
        installation.installedByUserId = installedByUserId;
        installation.suspendedAt = view.suspendedAt();
        installation.createdAt = now;
        installation.updatedAt = now;
        return installation;
    }

    /**
     * Refreshes the mutable facts from GitHub.
     *
     * <p>GitHub is the authority on what an installation grants, and it changes without telling us
     * synchronously — a user can add permissions or suspend the App at any time. Re-running the
     * setup flow re-reads rather than assuming the original grant still holds.
     */
    public void refreshFrom(GithubAppClient.InstallationView view) {
        this.accountLogin = view.account().login();
        this.permissions = view.permissions() == null ? Map.of() : Map.copyOf(view.permissions());
        this.events = view.events() == null ? List.of() : List.copyOf(view.events());
        this.repositorySelection = view.repositorySelection();
        this.suspendedAt = view.suspendedAt();
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getWorkspaceId() {
        return workspaceId;
    }

    public long getInstallationId() {
        return installationId;
    }

    public String getAccountLogin() {
        return accountLogin;
    }

    public String getAccountType() {
        return accountType;
    }

    public long getAccountId() {
        return accountId;
    }

    public Map<String, String> getPermissions() {
        return permissions;
    }

    public @Nullable String getRepositorySelection() {
        return repositorySelection;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof GithubInstallation installation && Objects.equals(id, installation.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}

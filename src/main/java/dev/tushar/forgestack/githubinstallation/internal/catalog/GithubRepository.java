package dev.tushar.forgestack.githubinstallation.internal.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A repository the installation exposes to ForgeStack — <em>available</em>, not maintained.
 *
 * <p>The distinction from {@link ManagedRepository} is the product's central rule, and it is a
 * separate table on purpose: having access to a repository is not consent to act on it. A user who
 * grants the App ten repositories has said "you may look at these", not "maintain these". The two
 * tables have different writers — this one is written by syncing from GitHub, the other only by an
 * explicit human decision.
 */
@Entity
@Table(name = "github_repositories")
public class GithubRepository {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "workspace_id", nullable = false, updatable = false)
    private UUID workspaceId;

    @Column(name = "github_installation_id", nullable = false, updatable = false)
    private UUID githubInstallationId;

    @Column(name = "github_repo_id", nullable = false, updatable = false)
    private long githubRepoId;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Column(name = "private", nullable = false)
    private boolean isPrivate;

    @Column(name = "default_branch")
    private @Nullable String defaultBranch;

    @Column(name = "archived", nullable = false)
    private boolean archived;

    @Column(name = "last_synced_at", nullable = false)
    private Instant lastSyncedAt;

    /**
     * When ForgeStack stopped being able to see this repository.
     *
     * <p>Set rather than deleted. A customer must never discover that ForgeStack quietly lost access
     * three weeks ago — losing access has to be visible, and a deleted row says nothing.
     */
    @Column(name = "removed_at")
    private @Nullable Instant removedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected GithubRepository() {}

    public static GithubRepository seen(
            UUID workspaceId,
            UUID githubInstallationId,
            long githubRepoId,
            String fullName,
            boolean isPrivate,
            @Nullable String defaultBranch,
            boolean archived) {

        GithubRepository repository = new GithubRepository();
        Instant now = Instant.now();
        repository.id = UUID.randomUUID();
        repository.workspaceId = workspaceId;
        repository.githubInstallationId = githubInstallationId;
        repository.githubRepoId = githubRepoId;
        repository.fullName = fullName;
        repository.isPrivate = isPrivate;
        repository.defaultBranch = defaultBranch;
        repository.archived = archived;
        repository.lastSyncedAt = now;
        repository.createdAt = now;
        return repository;
    }

    /** Re-observed during a sync: refresh the mutable facts and clear any prior removal. */
    public void seenAgain(String fullName, boolean isPrivate, @Nullable String defaultBranch, boolean archived) {
        this.fullName = fullName;
        this.isPrivate = isPrivate;
        this.defaultBranch = defaultBranch;
        this.archived = archived;
        this.lastSyncedAt = Instant.now();
        // Access can come back — a repository removed from the installation and later re-added is
        // the same repository, and forgetting that would orphan anything referencing it.
        this.removedAt = null;
    }

    /** Absent from the latest sync: ForgeStack can no longer see it. */
    public void noLongerVisible(Instant at) {
        if (this.removedAt == null) {
            this.removedAt = at;
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getWorkspaceId() {
        return workspaceId;
    }

    public long getGithubRepoId() {
        return githubRepoId;
    }

    public String getFullName() {
        return fullName;
    }

    public boolean isPrivate() {
        return isPrivate;
    }

    public @Nullable String getDefaultBranch() {
        return defaultBranch;
    }

    public boolean isArchived() {
        return archived;
    }

    public @Nullable Instant getRemovedAt() {
        return removedAt;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof GithubRepository repository && Objects.equals(id, repository.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}

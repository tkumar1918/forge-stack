package dev.tushar.forgestack.githubinstallation.internal.catalog;

import dev.tushar.forgestack.githubinstallation.AutonomyLevel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

/**
 * A repository a human has explicitly told ForgeStack to maintain.
 *
 * <p>Only ever created by a person choosing it. Nothing about syncing repositories from GitHub
 * creates a row here — that is the whole point of keeping it apart from {@link GithubRepository}.
 *
 * <p>{@code autonomyLevel} is the ceiling a human sets on what the agent may do. It defaults to
 * {@link AutonomyLevel#PR_WITH_APPROVAL} rather than to the most capable option, because the safe
 * default is the one you get by not thinking about it.
 */
@Entity
@Table(name = "managed_repositories")
public class ManagedRepository {

    /** Whether ForgeStack is currently maintaining this repository, and if not, why not. */
    public enum Status {
        ACTIVE,
        PAUSED,
        /** The installation no longer exposes it. Surfaced loudly, never silently dropped. */
        ACCESS_LOST
    }

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "workspace_id", nullable = false, updatable = false)
    private UUID workspaceId;

    @Column(name = "github_repository_id", nullable = false, updatable = false)
    private UUID githubRepositoryId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private Status status;

    @Enumerated(EnumType.STRING)
    @Column(name = "autonomy_level", nullable = false)
    private AutonomyLevel autonomyLevel;

    /**
     * How "done" is decided for this repository: setup, build and test commands.
     *
     * <p>Declared by a human and never authored by the model. An agent that can edit its own
     * grading rubric cannot be verified, so this stays outside anything the model can write.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "verification_contract")
    private @Nullable String verificationContract;

    @Column(name = "enabled_by")
    private @Nullable UUID enabledBy;

    @Column(name = "enabled_at", nullable = false)
    private Instant enabledAt;

    @Column(name = "disabled_at")
    private @Nullable Instant disabledAt;

    protected ManagedRepository() {}

    public static ManagedRepository enable(UUID workspaceId, UUID githubRepositoryId, UUID enabledBy) {
        ManagedRepository managed = new ManagedRepository();
        managed.id = UUID.randomUUID();
        managed.workspaceId = workspaceId;
        managed.githubRepositoryId = githubRepositoryId;
        managed.status = Status.ACTIVE;
        managed.autonomyLevel = AutonomyLevel.PR_WITH_APPROVAL;
        managed.enabledBy = enabledBy;
        managed.enabledAt = Instant.now();
        return managed;
    }

    /** Re-enabling something previously switched off, rather than creating a duplicate row. */
    public void reEnable(UUID enabledBy) {
        this.status = Status.ACTIVE;
        this.enabledBy = enabledBy;
        this.enabledAt = Instant.now();
        this.disabledAt = null;
    }

    /**
     * Stops ForgeStack maintaining this repository.
     *
     * <p>Kept as a row with {@code disabledAt} set rather than deleted, so that turning maintenance
     * off is itself part of the history — and so re-enabling does not silently lose the settings
     * someone chose.
     */
    public void disable() {
        this.status = Status.PAUSED;
        this.disabledAt = Instant.now();
    }

    /** The installation stopped exposing the repository; distinct from a human pausing it. */
    public void accessLost() {
        this.status = Status.ACCESS_LOST;
    }

    public UUID getId() {
        return id;
    }

    public UUID getGithubRepositoryId() {
        return githubRepositoryId;
    }

    public Status getStatus() {
        return status;
    }

    public AutonomyLevel getAutonomyLevel() {
        return autonomyLevel;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ManagedRepository managed && Objects.equals(id, managed.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}

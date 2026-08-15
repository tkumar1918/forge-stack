package dev.tushar.forgestack.iam.internal.workspace;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** The tenant boundary. Every piece of workspace-owned data hangs off exactly one of these. */
@Entity
@Table(name = "workspaces")
public class Workspace {

    public enum Status {
        ACTIVE,
        SUSPENDED,
        DELETED
    }

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "slug", nullable = false)
    private String slug;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "plan", nullable = false)
    private String plan;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private Status status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected Workspace() {}

    public static Workspace create(String slug, String name) {
        Workspace workspace = new Workspace();
        Instant now = Instant.now();
        workspace.id = UUID.randomUUID();
        workspace.slug = slug;
        workspace.name = name;
        workspace.plan = "ALPHA";
        workspace.status = Status.ACTIVE;
        workspace.createdAt = now;
        workspace.updatedAt = now;
        return workspace;
    }

    public UUID getId() {
        return id;
    }

    public String getSlug() {
        return slug;
    }

    public String getName() {
        return name;
    }

    public Status getStatus() {
        return status;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Workspace workspace && Objects.equals(id, workspace.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}

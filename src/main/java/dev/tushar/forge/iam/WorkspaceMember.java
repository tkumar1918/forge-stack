package dev.tushar.forge.iam;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Membership of a user in a workspace, with the role that membership carries. */
@Entity
@Table(name = "workspace_members")
public class WorkspaceMember {

    /** Four roles, deliberately. Richer RBAC is deferred until someone asks for it. */
    public enum Role {
        OWNER,
        ADMIN,
        MAINTAINER,
        VIEWER
    }

    @Embeddable
    public record Key(
            @Column(name = "workspace_id", nullable = false) UUID workspaceId,
            @Column(name = "user_id", nullable = false) UUID userId)
            implements Serializable {}

    @EmbeddedId
    private Key id;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false)
    private Role role;

    @Column(name = "invited_by")
    private UUID invitedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected WorkspaceMember() {}

    public static WorkspaceMember of(UUID workspaceId, UUID userId, Role role, UUID invitedBy) {
        WorkspaceMember member = new WorkspaceMember();
        member.id = new Key(workspaceId, userId);
        member.role = role;
        member.invitedBy = invitedBy;
        member.createdAt = Instant.now();
        return member;
    }

    public UUID getWorkspaceId() {
        return id.workspaceId();
    }

    public UUID getUserId() {
        return id.userId();
    }

    public Role getRole() {
        return role;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof WorkspaceMember member && Objects.equals(id, member.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}

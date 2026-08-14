package dev.tushar.forge.iam;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * An opaque server-side session.
 *
 * <p>Only the SHA-256 hash of the token is stored, so a database leak yields no usable sessions.
 *
 * <p>Not a JWT: removing someone from a workspace must revoke their access immediately, and
 * embedded claims go stale exactly when staleness is dangerous. A session lookup is one indexed
 * read, which is a cheap price for instant revocation.
 *
 * <p>The {@code ip} column exists in the schema but is intentionally unmapped — Postgres
 * {@code inet} has no natural JPA type, and nothing reads it yet.
 */
@Entity
@Table(name = "sessions")
public class Session {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** The workspace this session is currently acting in. Null until one is selected. */
    @Column(name = "workspace_id")
    private UUID workspaceId;

    @Column(name = "token_hash", nullable = false, updatable = false)
    private byte[] tokenHash;

    @Column(name = "user_agent")
    private String userAgent;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected Session() {}

    static Session issue(UUID userId, byte[] tokenHash, String userAgent, Instant expiresAt) {
        Session session = new Session();
        Instant now = Instant.now();
        session.id = UUID.randomUUID();
        session.userId = userId;
        session.tokenHash = tokenHash;
        session.userAgent = userAgent;
        session.createdAt = now;
        session.lastSeenAt = now;
        session.expiresAt = expiresAt;
        return session;
    }

    public boolean isActive(Instant at) {
        return revokedAt == null && expiresAt.isAfter(at);
    }

    void touch(Instant at) {
        this.lastSeenAt = at;
    }

    void revoke(Instant at) {
        if (this.revokedAt == null) {
            this.revokedAt = at;
        }
    }

    public void selectWorkspace(UUID workspaceId) {
        this.workspaceId = workspaceId;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getWorkspaceId() {
        return workspaceId;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Session session && Objects.equals(id, session.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}

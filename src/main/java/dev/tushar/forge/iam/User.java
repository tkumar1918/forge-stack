package dev.tushar.forge.iam;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** A human. Global, not owned by any workspace — one person may belong to several. */
@Entity
@Table(name = "users")
public class User {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "primary_email", nullable = false)
    private String primaryEmail;

    @Column(name = "display_name")
    private String displayName;

    @Column(name = "avatar_url")
    private String avatarUrl;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected User() {}

    public User(UUID id, String primaryEmail, String displayName, String avatarUrl) {
        Instant now = Instant.now();
        this.id = id;
        this.primaryEmail = primaryEmail;
        this.displayName = displayName;
        this.avatarUrl = avatarUrl;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static User create(String primaryEmail, String displayName, String avatarUrl) {
        return new User(UUID.randomUUID(), normalizeEmail(primaryEmail), displayName, avatarUrl);
    }

    /**
     * Emails are stored lower-cased so a plain unique index enforces case-insensitivity.
     *
     * <p>Every lookup must normalise the same way — see {@code UserProvisioningService}. The
     * alternative, a {@code citext} column, fails Hibernate schema validation.
     */
    public static String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase(java.util.Locale.ROOT);
    }

    public void updateProfile(String displayName, String avatarUrl) {
        this.displayName = displayName;
        this.avatarUrl = avatarUrl;
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getPrimaryEmail() {
        return primaryEmail;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getAvatarUrl() {
        return avatarUrl;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    // Identity-based equality: two instances of the same row are the same user, regardless of
    // which fields happen to be loaded.
    @Override
    public boolean equals(Object other) {
        return other instanceof User user && Objects.equals(id, user.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}

package dev.tushar.forge.iam.internal.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A login identity at an external provider.
 *
 * <p>Deliberately stores no access token. The GitHub OAuth token is used once during login to
 * fetch the profile and then discarded: a stored user token is a standing credential able to act
 * as that person on GitHub, and the agent never needs it — it uses short-lived,
 * repository-scoped GitHub App installation tokens instead.
 */
@Entity
@Table(name = "user_identities")
public class UserIdentity {

    public static final String PROVIDER_GITHUB = "github";

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "provider", nullable = false)
    private String provider;

    @Column(name = "provider_user_id", nullable = false)
    private String providerUserId;

    @Column(name = "provider_login")
    private String providerLogin;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected UserIdentity() {}

    public static UserIdentity github(UUID userId, String providerUserId, String providerLogin) {
        UserIdentity identity = new UserIdentity();
        Instant now = Instant.now();
        identity.id = UUID.randomUUID();
        identity.userId = userId;
        identity.provider = PROVIDER_GITHUB;
        identity.providerUserId = providerUserId;
        identity.providerLogin = providerLogin;
        identity.createdAt = now;
        identity.updatedAt = now;
        return identity;
    }

    public void updateLogin(String providerLogin) {
        this.providerLogin = providerLogin;
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getProvider() {
        return provider;
    }

    public String getProviderUserId() {
        return providerUserId;
    }

    public String getProviderLogin() {
        return providerLogin;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof UserIdentity identity && Objects.equals(id, identity.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}

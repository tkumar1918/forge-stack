package dev.tushar.forgestack.iam;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tushar.forgestack.support.AbstractIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class SessionServiceTest extends AbstractIntegrationTest {

    @Autowired
    private SessionService sessions;

    @Autowired
    private UserProvisioningService provisioning;

    @Autowired
    private IamQueries iam;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID userId;

    @BeforeEach
    void createUser() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UserProfile user = provisioning.provision(new GithubProfile(
                "gh-" + suffix, "octo-" + suffix, suffix + "@example.com", "Octo Cat", "https://example.com/a.png"));
        this.userId = user.id();
    }

    @Test
    @DisplayName("an issued session validates and resolves to its user")
    void issuedSessionValidates() {
        var issued = sessions.issue(userId, "JUnit");

        assertThat(sessions.validate(issued.token()))
                .isPresent()
                .get()
                .satisfies(session -> assertThat(session.userId()).isEqualTo(userId));
    }

    /**
     * Regression: {@code Session.selectWorkspace} existed but had no callers, so every session was
     * issued with a null workspace. Nothing tenant-scoped could be written by a logged-in user,
     * because {@code TenantScope} refuses a null workspace — the failure was silent until something
     * actually tried to write.
     */
    @Test
    @DisplayName("a new session starts in the user's own workspace")
    void issuedSessionCarriesDefaultWorkspace() {
        UUID ownWorkspace = iam.workspacesFor(userId).getFirst().id();

        var issued = sessions.issue(userId, "JUnit");

        assertThat(sessions.validate(issued.token()))
                .isPresent()
                .get()
                .satisfies(session -> assertThat(session.workspaceId()).isEqualTo(ownWorkspace));
    }

    @Test
    @DisplayName("the raw token is never stored")
    void rawTokenIsNotStored() {
        var issued = sessions.issue(userId, "JUnit");

        // A database leak must not yield usable sessions. Only the SHA-256 digest is persisted,
        // so searching for the raw token must find nothing.
        Integer matches = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM sessions WHERE encode(token_hash, 'escape') LIKE ?",
                Integer.class,
                "%" + issued.token() + "%");

        assertThat(matches).isZero();
    }

    @Test
    @DisplayName("a revoked session stops working immediately")
    void revokedSessionStopsWorking() {
        var issued = sessions.issue(userId, "JUnit");
        assertThat(sessions.validate(issued.token())).isPresent();

        sessions.revoke(issued.token());

        // This is the reason for opaque server-side sessions over JWTs: revocation takes effect
        // on the very next request rather than whenever a token would have expired.
        assertThat(sessions.validate(issued.token())).isEmpty();
    }

    @Test
    @DisplayName("revoking all sessions for a user logs out every device")
    void revokeAllForUser() {
        var first = sessions.issue(userId, "device-1");
        var second = sessions.issue(userId, "device-2");

        int revoked = sessions.revokeAllForUser(userId);

        assertThat(revoked).isEqualTo(2);
        assertThat(sessions.validate(first.token())).isEmpty();
        assertThat(sessions.validate(second.token())).isEmpty();
    }

    @Test
    @DisplayName("unknown and malformed tokens are rejected without error")
    void unknownTokensAreRejected() {
        assertThat(sessions.validate("not-a-real-token")).isEmpty();
        assertThat(sessions.validate("")).isEmpty();
        assertThat(sessions.validate(null)).isEmpty();
    }

    @Test
    @DisplayName("an expired session is rejected")
    void expiredSessionIsRejected() {
        var issued = sessions.issue(userId, "JUnit");

        jdbcTemplate.update("UPDATE sessions SET expires_at = now() - interval '1 minute' WHERE id = ?", issued.sessionId());

        assertThat(sessions.validate(issued.token())).isEmpty();
    }
}

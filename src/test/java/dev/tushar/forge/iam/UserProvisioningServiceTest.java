package dev.tushar.forge.iam;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tushar.forge.support.AbstractIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class UserProvisioningServiceTest extends AbstractIntegrationTest {

    @Autowired
    private UserProvisioningService provisioning;

    @Autowired
    private IamQueries iam;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static GithubProfile profile(String suffix) {
        return new GithubProfile(
                "gh-" + suffix, "octo-" + suffix, suffix + "@example.com", "Octo " + suffix, "https://example.com/a.png");
    }

    @Test
    @DisplayName("first login creates a user, a workspace, and an owner membership")
    void firstLoginProvisionsEverything() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        UserProfile user = provisioning.provision(profile(suffix));

        var workspaces = iam.workspacesFor(user.id());
        assertThat(workspaces).hasSize(1);
        assertThat(iam.roleIn(workspaces.getFirst().id(), user.id()))
                .contains(WorkspaceRole.OWNER);
    }

    @Test
    @DisplayName("logging in again reuses the same user rather than creating a second one")
    void repeatLoginIsIdempotent() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        UserProfile first = provisioning.provision(profile(suffix));
        UserProfile second = provisioning.provision(profile(suffix));

        assertThat(second.id()).isEqualTo(first.id());
        // The important part: no second workspace. Provisioning runs on every login, so a
        // non-idempotent path would quietly accumulate a workspace per sign-in.
        assertThat(iam.workspacesFor(first.id())).hasSize(1);
    }

    @Test
    @DisplayName("no GitHub access token is persisted for the identity")
    void noAccessTokenIsPersisted() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        provisioning.provision(profile(suffix));

        // Enforced by the schema having no such column. Asserted here so that adding one later
        // is a conscious act that breaks a test explaining why it must not happen.
        Integer tokenColumns = jdbcTemplate.queryForObject(
                """
                SELECT count(*) FROM information_schema.columns
                WHERE table_name = 'user_identities'
                  AND (column_name LIKE '%token%' OR column_name LIKE '%secret%')
                """,
                Integer.class);

        assertThat(tokenColumns)
                .as("a stored user OAuth token is a standing credential that can act as that human on GitHub")
                .isZero();
    }

    @Test
    @DisplayName("two users with clashing logins get distinct workspace slugs")
    void workspaceSlugsAreUnique() {
        String login = "octo-" + UUID.randomUUID().toString().substring(0, 8);

        UserProfile first = provisioning.provision(
                new GithubProfile("gh-1-" + login, login, login + "-1@example.com", "A", null));
        UserProfile second = provisioning.provision(
                new GithubProfile("gh-2-" + login, login, login + "-2@example.com", "B", null));

        String slugA = iam.workspacesFor(first.id()).getFirst().slug();
        String slugB = iam.workspacesFor(second.id()).getFirst().slug();

        assertThat(slugA).isNotEqualTo(slugB);
    }
}

package dev.tushar.forge.githubinstallation;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import dev.tushar.forge.githubinstallation.InstallationBindingResult.Bound;
import dev.tushar.forge.githubinstallation.InstallationBindingResult.Reason;
import dev.tushar.forge.githubinstallation.InstallationBindingResult.Rejected;
import dev.tushar.forge.iam.GithubProfile;
import dev.tushar.forge.iam.IamQueries;
import dev.tushar.forge.iam.UserProvisioningService;
import dev.tushar.forge.platform.tenancy.TenantScope;
import dev.tushar.forge.support.AbstractIntegrationTest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The anti-hijack contract for binding a GitHub App installation.
 *
 * <p>Runs against a real HTTP stub for GitHub rather than a mocked client, deliberately: the
 * response is snake_case JSON and the mapping to {@code InstallationView} is exactly the sort of
 * thing that compiles, passes a mocked test, and returns nulls against the real API.
 */
class InstallationBindingServiceTest extends AbstractIntegrationTest {

    /** installation id → the JSON GitHub would return for it. */
    private static final Map<Long, String> GITHUB_INSTALLATIONS = new ConcurrentHashMap<>();

    private static final AtomicLong NEXT_ID = new AtomicLong(9_000);

    private static final HttpServer GITHUB = startFakeGithub();
    private static final KeyPair APP_KEY = generateKey();

    @DynamicPropertySource
    static void githubApp(DynamicPropertyRegistry registry) {
        registry.add("forge.github.app.app-id", () -> "123456");
        registry.add("forge.github.app.private-key-pem", () -> toPkcs8Pem(APP_KEY));
        registry.add("forge.github.app.slug", () -> "forge-test");
        registry.add(
                "forge.github.app.api-base-url",
                () -> "http://localhost:" + GITHUB.getAddress().getPort());
    }

    @Autowired
    private InstallationBindingService bindings;

    @Autowired
    private UserProvisioningService provisioning;

    @Autowired
    private IamQueries iam;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TenantScope tenantScope;

    private UUID userId;
    private UUID workspaceId;
    private String githubUserId;
    private UUID sessionId;

    @BeforeEach
    void provisionUser() {
        this.githubUserId = String.valueOf(NEXT_ID.incrementAndGet());
        this.userId = newUser(githubUserId).id();
        this.workspaceId = iam.workspacesFor(userId).getFirst().id();
        this.sessionId = UUID.randomUUID();
    }

    /**
     * The reason this class exists.
     *
     * <p>The attacker holds a stranger's installation id — they are guessable and leak into logs —
     * and a nonce that is entirely legitimate, because they started their own install flow. That
     * combination defeats CSRF protection completely: only checking the installation's account
     * against the caller's GitHub identity stops it.
     */
    @Test
    @DisplayName("binding a stranger's installation is rejected even with a valid nonce")
    void cannotBindAnInstallationOwnedBySomeoneElse() {
        long victimInstallation = githubInstallation(999_001L, "victim", "User");

        String ownNonce = bindings.beginSetup(sessionId);
        var result = bindings.completeSetup(victimInstallation, ownNonce, sessionId, userId, workspaceId);

        assertThat(result).isEqualTo(new Rejected(Reason.NOT_YOUR_ACCOUNT));
        assertThat(installationRowsIn(workspaceId, victimInstallation)).isZero();
    }

    @Test
    @DisplayName("binding your own installation succeeds and records the account")
    void bindsOwnInstallation() {
        long installationId = githubInstallation(Long.parseLong(githubUserId), "octo", "User");

        String nonce = bindings.beginSetup(sessionId);
        var result = bindings.completeSetup(installationId, nonce, sessionId, userId, workspaceId);

        assertThat(result).isInstanceOfSatisfying(Bound.class, bound -> {
            assertThat(bound.installation().installationId()).isEqualTo(installationId);
            assertThat(bound.installation().accountLogin()).isEqualTo("octo");
        });
        assertThat(installationRowsIn(workspaceId, installationId)).isEqualTo(1);
    }

    @Test
    @DisplayName("organization installs are refused rather than bound unverified")
    void organizationInstallsAreRefused() {
        // Confirming org admin rights needs the Members permission, which the App does not request.
        // Failing closed beats binding something we cannot prove the caller controls.
        long orgInstallation = githubInstallation(Long.parseLong(githubUserId), "acme-corp", "Organization");

        String nonce = bindings.beginSetup(sessionId);
        var result = bindings.completeSetup(orgInstallation, nonce, sessionId, userId, workspaceId);

        assertThat(result).isEqualTo(new Rejected(Reason.ORGANIZATION_NOT_SUPPORTED));
        assertThat(installationRowsIn(workspaceId, orgInstallation)).isZero();
    }

    @Test
    @DisplayName("a nonce cannot be replayed")
    void nonceIsSingleUse() {
        long installationId = githubInstallation(Long.parseLong(githubUserId), "octo", "User");
        String nonce = bindings.beginSetup(sessionId);

        assertThat(bindings.completeSetup(installationId, nonce, sessionId, userId, workspaceId))
                .isInstanceOf(Bound.class);

        // An install link leaking through browser history or a referrer header must be inert.
        assertThat(bindings.completeSetup(installationId, nonce, sessionId, userId, workspaceId))
                .isEqualTo(new Rejected(Reason.INVALID_SETUP_STATE));
    }

    @Test
    @DisplayName("a nonce issued to another session is rejected")
    void nonceIsBoundToItsSession() {
        long installationId = githubInstallation(Long.parseLong(githubUserId), "octo", "User");
        String someoneElsesNonce = bindings.beginSetup(UUID.randomUUID());

        var result = bindings.completeSetup(installationId, someoneElsesNonce, sessionId, userId, workspaceId);

        assertThat(result).isEqualTo(new Rejected(Reason.INVALID_SETUP_STATE));
    }

    @Test
    @DisplayName("an installation GitHub does not know is rejected")
    void unknownInstallationIsRejected() {
        String nonce = bindings.beginSetup(sessionId);

        var result = bindings.completeSetup(404_404L, nonce, sessionId, userId, workspaceId);

        assertThat(result).isEqualTo(new Rejected(Reason.UNKNOWN_INSTALLATION));
    }

    /**
     * The guarantee §7 leans on is the database constraint, not the application check.
     *
     * <p>Row-level security hides the existing row from the second workspace's lookup, so the code
     * genuinely tries to insert and the unique index refuses it. That is the intended shape: the
     * hijack fails even when application logic has no idea a conflict exists.
     */
    @Test
    @DisplayName("one installation cannot be bound to two workspaces")
    void installationCannotBeBoundTwice() {
        long installationId = githubInstallation(Long.parseLong(githubUserId), "octo", "User");

        assertThat(bindings.completeSetup(
                        installationId, bindings.beginSetup(sessionId), sessionId, userId, workspaceId))
                .isInstanceOf(Bound.class);

        UUID secondWorkspace = newWorkspaceFor(userId);
        var result = bindings.completeSetup(
                installationId, bindings.beginSetup(sessionId), sessionId, userId, secondWorkspace);

        assertThat(result).isEqualTo(new Rejected(Reason.ALREADY_BOUND_ELSEWHERE));
        assertThat(installationRowsIn(workspaceId, installationId)).isEqualTo(1);
        assertThat(installationRowsIn(secondWorkspace, installationId)).isZero();
    }

    @Test
    @DisplayName("re-running setup on an installation this workspace already holds refreshes it")
    void rebindingInOwnWorkspaceIsARefresh() {
        long installationId = githubInstallation(Long.parseLong(githubUserId), "octo", "User");
        bindings.completeSetup(installationId, bindings.beginSetup(sessionId), sessionId, userId, workspaceId);

        // The user changed which repositories the App can see; GitHub is the authority on that.
        GITHUB_INSTALLATIONS.put(
                installationId, installationJson(installationId, Long.parseLong(githubUserId), "octo", "User", "all"));

        var result = bindings.completeSetup(
                installationId, bindings.beginSetup(sessionId), sessionId, userId, workspaceId);

        assertThat(result).isInstanceOf(Bound.class);
        assertThat(installationRowsIn(workspaceId, installationId)).isEqualTo(1);
        assertThat(tenantScope.runInTenant(
                        workspaceId,
                        () -> jdbcTemplate.queryForObject(
                                "SELECT repository_selection FROM github_installations WHERE installation_id = ?",
                                String.class,
                                installationId)))
                .isEqualTo("all");
    }

    @Test
    @DisplayName("a rejected binding is audited, not silently dropped")
    void rejectionIsAudited() {
        long victimInstallation = githubInstallation(999_002L, "victim", "User");

        bindings.completeSetup(victimInstallation, bindings.beginSetup(sessionId), sessionId, userId, workspaceId);

        // A failed hijack attempt is exactly the event an operator needs to see.
        Integer rejections = tenantScope.runInTenant(
                workspaceId,
                () -> jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM audit_events WHERE action = 'INSTALLATION_BIND_REJECTED'",
                        Integer.class));
        assertThat(rejections).isEqualTo(1);
    }

    // --- helpers -------------------------------------------------------------------------------

    private dev.tushar.forge.iam.UserProfile newUser(String providerUserId) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return provisioning.provision(new GithubProfile(
                providerUserId, "user-" + suffix, suffix + "@example.com", "Test User", "https://example.com/a.png"));
    }

    /** Registers an installation with the fake GitHub and returns its id. */
    private static long githubInstallation(long accountId, String login, String accountType) {
        long installationId = NEXT_ID.incrementAndGet();
        GITHUB_INSTALLATIONS.put(
                installationId, installationJson(installationId, accountId, login, accountType, "selected"));
        return installationId;
    }

    private static String installationJson(
            long id, long accountId, String login, String accountType, String repositorySelection) {
        return """
                {"id":%d,
                 "account":{"id":%d,"login":"%s","type":"%s"},
                 "repository_selection":"%s",
                 "permissions":{"contents":"read","metadata":"read"},
                 "events":["push"],
                 "suspended_at":null}
                """
                .formatted(id, accountId, login, accountType, repositorySelection);
    }

    /**
     * Counts installation rows visible <em>inside</em> {@code workspace}.
     *
     * <p>There is no {@code BYPASSRLS} role, deliberately — not even in tests, because a test that
     * can see across tenants is not testing the system that ships. So this asks the sharper
     * question anyway: is the row in <em>this</em> workspace, rather than does it exist somewhere.
     */
    private Integer installationRowsIn(UUID workspace, long installationId) {
        return tenantScope.runInTenant(
                workspace,
                () -> jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM github_installations WHERE installation_id = ?",
                        Integer.class,
                        installationId));
    }

    private UUID newWorkspaceFor(UUID owner) {
        UUID id = UUID.randomUUID();
        String slug = "ws-" + id.toString().substring(0, 8);
        jdbcTemplate.update("INSERT INTO workspaces (id, slug, name) VALUES (?, ?, ?)", id, slug, slug);
        jdbcTemplate.update(
                "INSERT INTO workspace_members (workspace_id, user_id, role) VALUES (?, ?, 'OWNER')", id, owner);
        return id;
    }

    private static HttpServer startFakeGithub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
            server.createContext("/app/installations", exchange -> {
                String path = exchange.getRequestURI().getPath();
                String body = GITHUB_INSTALLATIONS.get(Long.parseLong(path.substring(path.lastIndexOf('/') + 1)));

                if (body == null) {
                    exchange.sendResponseHeaders(404, -1);
                } else {
                    byte[] out = body.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, out.length);
                    exchange.getResponseBody().write(out);
                }
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static KeyPair generateKey() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String toPkcs8Pem(KeyPair keyPair) {
        String base64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8))
                .encodeToString(keyPair.getPrivate().getEncoded());
        return "-----BEGIN PRIVATE KEY-----\n" + base64 + "\n-----END PRIVATE KEY-----\n";
    }
}

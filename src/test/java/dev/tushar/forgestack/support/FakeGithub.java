package dev.tushar.forgestack.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A real HTTP server standing in for GitHub.
 *
 * <p>A real server rather than a mocked client, deliberately. GitHub speaks snake_case JSON and the
 * mapping to our records is exactly the kind of thing that compiles, satisfies a mock, and returns
 * nulls in production — Spring Boot 4 ships Jackson 3, so a wrong annotation package would fail
 * silently and only against the live API.
 *
 * <p>One instance per JVM on a fixed port so every test class resolves the same property values and
 * can share a Spring context.
 */
public final class FakeGithub {

    private static final Map<Long, String> INSTALLATIONS = new ConcurrentHashMap<>();
    private static final Map<Long, List<Repo>> REPOSITORIES = new ConcurrentHashMap<>();
    private static final Set<Long> UNAUTHORIZED = ConcurrentHashMap.newKeySet();
    private static final AtomicLong NEXT_ID = new AtomicLong(9_000);
    private static final AtomicReference<String> OAUTH_USER = new AtomicReference<>();
    private static final HttpServer SERVER = start();

    private FakeGithub() {}

    /** A repository as GitHub would list it. */
    public record Repo(long id, String fullName, boolean isPrivate, String defaultBranch, boolean archived) {

        public static Repo named(String fullName) {
            return new Repo(NEXT_ID.incrementAndGet(), fullName, true, "main", false);
        }
    }

    public static String baseUrl() {
        return "http://localhost:" + SERVER.getAddress().getPort();
    }

    /**
     * A generated App key, so no real credential is needed to exercise the whole signing path.
     *
     * <p>Held here rather than per test class so every class resolves the same property value.
     * Differing values would give each class its own Spring context and its own set of containers
     * to wait for.
     */
    public static String privateKeyPem() {
        return APP_KEY_PEM;
    }

    private static final String APP_KEY_PEM = generateKeyPem();

    private static String generateKeyPem() {
        try {
            java.security.KeyPairGenerator generator = java.security.KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            String base64 = java.util.Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8))
                    .encodeToString(generator.generateKeyPair().getPrivate().getEncoded());
            return "-----BEGIN PRIVATE KEY-----\n" + base64 + "\n-----END PRIVATE KEY-----\n";
        } catch (Exception e) {
            throw new IllegalStateException("Could not generate a test App key", e);
        }
    }

    /** A fresh id nothing else is using, for accounts and installations alike. */
    public static long nextId() {
        return NEXT_ID.incrementAndGet();
    }

    /** Registers an installation and returns its id. */
    public static long installation(long accountId, String login, String accountType) {
        long installationId = NEXT_ID.incrementAndGet();
        INSTALLATIONS.put(installationId, installationJson(installationId, accountId, login, accountType, "selected"));
        REPOSITORIES.put(installationId, List.of());
        return installationId;
    }

    /**
     * An installation id GitHub answers with 401 — the shape of a rejected App key.
     *
     * <p>Registered per id rather than as a global toggle: every test class shares this one static
     * server, so a flag would be a way for one test to change another's GitHub.
     */
    public static long unauthorized() {
        long installationId = NEXT_ID.incrementAndGet();
        UNAUTHORIZED.add(installationId);
        return installationId;
    }

    /** Replaces what the installation exposes — the way a user changing their selection would. */
    public static void exposes(long installationId, Repo... repos) {
        REPOSITORIES.put(installationId, List.of(repos));
    }

    public static void reconfigure(long installationId, long accountId, String login, String repositorySelection) {
        INSTALLATIONS.put(
                installationId, installationJson(installationId, accountId, login, "User", repositorySelection));
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

    /** Where Spring Security should exchange an authorization code. */
    public static String tokenUri() {
        return baseUrl() + "/login/oauth/access_token";
    }

    /** Where Spring Security should fetch the logged-in profile. */
    public static String userInfoUri() {
        return baseUrl() + "/user";
    }

    /**
     * Sets the account the next OAuth login returns, and gives back its GitHub numeric id.
     *
     * <p>That id is what {@code InstallationBindingService} compares against an installation's
     * owner, so a test binding an installation has to register both with the same number.
     */
    public static long oauthUser(String login, String email) {
        long id = NEXT_ID.incrementAndGet();
        OAUTH_USER.set(
                """
                {"id":%d,"login":"%s","name":"%s","avatar_url":"https://example.invalid/%s.png","email":"%s"}
                """
                        .formatted(id, login, login, login, email));
        return id;
    }

    private static HttpServer start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
            // Answers for the App itself, naming no installation. The client asks here on a 404 to
            // tell "your App id is wrong" apart from "that installation does not exist" — real
            // GitHub returns 404 for both, so without this the two are indistinguishable.
            server.createContext("/app", FakeGithub::handleApp);
            server.createContext("/app/installations", FakeGithub::handleInstallations);
            server.createContext("/installation/repositories", FakeGithub::handleRepositories);
            server.createContext("/login/oauth/access_token", FakeGithub::handleAccessToken);
            server.createContext("/user", FakeGithub::handleUser);
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The authorization-code exchange.
     *
     * <p>The code is not checked. What this exists to exercise is everything downstream of a
     * successful exchange — the user service, the session handover, and the filter chain — none of
     * which a mocked client would run.
     */
    private static void handleAccessToken(HttpExchange exchange) throws IOException {
        respond(exchange, 200, """
                {"access_token":"gho_fake","token_type":"bearer","scope":"read:user,user:email"}
                """);
    }

    private static void handleUser(HttpExchange exchange) throws IOException {
        String body = OAUTH_USER.get();
        if (body == null) {
            // A test drove a login without saying who is logging in. Failing here beats provisioning
            // a user from a default nobody chose.
            respond(exchange, 404, "");
            return;
        }
        respond(exchange, 200, body);
    }

    /**
     * The App behind the presented JWT.
     *
     * <p>Always recognised here. The credentials this fake is driven with are always the ones it
     * issued, so the interesting case — an App id GitHub has never heard of — cannot arise against
     * it. That path is verified against real GitHub instead; see known-gaps.md §1.1.
     */
    private static void handleApp(HttpExchange exchange) throws IOException {
        if (!"/app".equals(exchange.getRequestURI().getPath())) {
            respond(exchange, 404, "");
            return;
        }
        respond(exchange, 200, """
                {"id":1,"slug":"forgestack-test","name":"ForgeStack Test"}
                """);
    }

    private static void handleInstallations(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();

        if (path.endsWith("/access_tokens")) {
            // The token carries the installation id so the repositories endpoint can tell which
            // installation is asking — which is how the real API works, via the token rather than
            // the URL.
            long installationId = Long.parseLong(path.split("/")[3]);
            respond(
                    exchange,
                    200,
                    """
                    {"token":"tok-%d","expires_at":"%s"}
                    """
                            .formatted(installationId, java.time.Instant.now().plusSeconds(3600)));
            return;
        }

        long installationId = Long.parseLong(path.substring(path.lastIndexOf('/') + 1));
        if (UNAUTHORIZED.contains(installationId)) {
            // What GitHub says when the App JWT itself is refused: a key from another App, a
            // revoked one, or a wrong app id. Nothing to do with which installation was asked for.
            respond(exchange, 401, "");
            return;
        }

        String body = INSTALLATIONS.get(installationId);
        if (body == null) {
            // GitHub's 404 carries a JSON body, and that detail is load-bearing: an empty one lets a
            // client "handle" the status and then deserialize nothing, which looks like success.
            // This fake returned an empty body and hid a 500 on the guessed-id path for months.
            respond(
                    exchange,
                    404,
                    """
                    {"message":"Not Found","documentation_url":"https://docs.github.com/rest","status":"404"}
                    """);
        } else {
            respond(exchange, 200, body);
        }
    }

    private static void handleRepositories(HttpExchange exchange) throws IOException {
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        long installationId = Long.parseLong(authorization.replace("Bearer tok-", "").trim());

        List<Repo> repos = REPOSITORIES.getOrDefault(installationId, List.of());
        List<String> entries = new ArrayList<>();
        for (Repo repo : repos) {
            entries.add(
                    """
                    {"id":%d,"full_name":"%s","private":%b,"default_branch":"%s","archived":%b}
                    """
                            .formatted(
                                    repo.id(),
                                    repo.fullName(),
                                    repo.isPrivate(),
                                    repo.defaultBranch(),
                                    repo.archived()));
        }

        respond(
                exchange,
                200,
                """
                {"total_count":%d,"repositories":[%s]}
                """
                        .formatted(entries.size(), String.join(",", entries)));
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        if (body.isEmpty()) {
            exchange.sendResponseHeaders(status, -1);
        } else {
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, out.length);
            exchange.getResponseBody().write(out);
        }
        exchange.close();
    }
}

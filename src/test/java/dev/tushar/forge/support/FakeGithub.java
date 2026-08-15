package dev.tushar.forge.support;

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

    private static HttpServer start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
            server.createContext("/app/installations", FakeGithub::handleInstallations);
            server.createContext("/installation/repositories", FakeGithub::handleRepositories);
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
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
            respond(exchange, 404, "");
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

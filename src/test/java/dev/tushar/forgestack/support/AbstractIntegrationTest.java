package dev.tushar.forgestack.support;

import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base class for integration tests, providing real Postgres and Redis.
 *
 * <p>Real Postgres, not H2: the schema depends on row-level security, partitioning, and partial
 * unique indexes. A test against an in-memory database would verify a different system than the
 * one that ships — and RLS is precisely the property most worth verifying.
 *
 * <p>Containers are started once per JVM by the static initialiser below and deliberately never
 * stopped. The JUnit {@code @Testcontainers} extension stops {@code @Container} fields after each
 * test <em>class</em>, which for a shared static field means the second class inherits a dead
 * container. Ryuk removes these on JVM exit.
 *
 * <p>{@code @AutoConfigureMockMvc} is here rather than on the one class that needs it so the
 * context-cache key stays identical across every subclass. Spring caches a context per distinct
 * configuration, and an annotation on a single class would quietly buy a third context and a third
 * wait for containers.
 */
@AutoConfigureMockMvc
@SpringBootTest(
        properties = {
            // The context must start without real credentials.
            "spring.ai.openai.api-key=test-key",
            "forgestack.role=all",
            // Long enough never to fire. The reconciler reclaims leases and re-queues tasks across
            // every workspace in the database, so a sweep landing in the middle of a test would
            // change the very rows that test is asserting on — and only sometimes. Tests that want
            // a sweep call it directly, which is also the only way to assert what one did.
            "forgestack.jobs.reconcile-interval=PT24H",
            // And the same for the worker, which matters more. Its default is one second, so without
            // this it would quietly claim and run every task any other test left queued — changing
            // the rows those tests are asserting on, from another thread, sometimes. Tests that want
            // work done call runAvailableWork() directly.
            "forgestack.runtime.poll-interval=PT24H"
        })
public abstract class AbstractIntegrationTest {

    protected static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine")
            .withDatabaseName("forgestack")
            .withInitScript("db/test-roles.sql");

    protected static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:8-alpine").withExposedPorts(6379);

    static {
        POSTGRES.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        // Two database roles, exactly as in production: the application is not the schema owner
        // and cannot bypass RLS. Testing as the owner would prove nothing.
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "forgestack_app");
        registry.add("spring.datasource.password", () -> "forgestack_app");

        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", () -> "forgestack_migrator");
        registry.add("spring.flyway.password", () -> "forgestack_migrator");

        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));

        // Point GitHub's OAuth endpoints at the fake, in the shared base class, so "no test can
        // reach github.com" is a property of the harness rather than of each test remembering.
        //
        // Only these two are overridden on purpose: OAuth2ClientPropertiesMapper starts from
        // CommonOAuth2Provider.GITHUB and overlays what is configured, so the authorization URI,
        // user-name-attribute and client authentication method all keep their real values.
        registry.add("spring.security.oauth2.client.provider.github.token-uri", FakeGithub::tokenUri);
        registry.add("spring.security.oauth2.client.provider.github.user-info-uri", FakeGithub::userInfoUri);
        registry.add("spring.security.oauth2.client.registration.github.client-id", () -> "test-client");
        registry.add("spring.security.oauth2.client.registration.github.client-secret", () -> "test-secret");
    }
}

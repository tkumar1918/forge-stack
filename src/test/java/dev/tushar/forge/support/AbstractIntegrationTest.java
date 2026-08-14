package dev.tushar.forge.support;

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
 */
@SpringBootTest(
        properties = {
            // The context must start without real credentials.
            "spring.ai.openai.api-key=test-key",
            "forge.role=all"
        })
public abstract class AbstractIntegrationTest {

    protected static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine")
            .withDatabaseName("forge")
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
        registry.add("spring.datasource.username", () -> "forge_app");
        registry.add("spring.datasource.password", () -> "forge_app");

        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", () -> "forge_migrator");
        registry.add("spring.flyway.password", () -> "forge_migrator");

        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }
}

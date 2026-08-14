package dev.tushar.forge.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base class for integration tests that need a real Postgres.
 *
 * <p>Real Postgres, not H2: the schema depends on row-level security, partitioning, partial unique
 * indexes, and {@code citext}. A test against an in-memory database would verify a different
 * system than the one that ships — and RLS is precisely the property most worth verifying.
 *
 * <p><strong>Singleton container, started once per JVM.</strong> Deliberately not
 * {@code @Testcontainers} + {@code @Container}: that extension stops the container when the first
 * test class finishes, while Spring caches and reuses the application context across classes. The
 * next class then inherits a context pointing at a dead port and fails with "connection refused",
 * which looks like a configuration bug and is not. Starting it here and never stopping it leaves
 * cleanup to Ryuk at JVM exit.
 */
@SpringBootTest(
        properties = {
            // The context must start without real credentials.
            "spring.ai.openai.api-key=test-key",
            "forge.role=all"
        })
public abstract class AbstractPostgresIT {

    protected static final PostgreSQLContainer POSTGRES;

    static {
        POSTGRES = new PostgreSQLContainer("postgres:18-alpine")
                .withDatabaseName("forge")
                .withInitScript("db/test-roles.sql");
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        // Two roles, exactly as in production: the application is not the schema owner and
        // cannot bypass RLS. Testing as the owner would prove nothing.
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "forge_app");
        registry.add("spring.datasource.password", () -> "forge_app");

        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", () -> "forge_migrator");
        registry.add("spring.flyway.password", () -> "forge_migrator");
    }
}

package dev.tushar.forgestack;

import dev.tushar.forgestack.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

/**
 * Starts the whole context against a real Postgres, which also proves the Flyway baseline applies
 * cleanly and that Hibernate validates against the migrated schema ({@code ddl-auto: validate}).
 */
class ForgeStackApplicationTests extends AbstractIntegrationTest {

    @Test
    void contextLoads() {}
}

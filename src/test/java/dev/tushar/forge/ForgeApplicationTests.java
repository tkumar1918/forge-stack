package dev.tushar.forge;

import dev.tushar.forge.support.AbstractPostgresIT;
import org.junit.jupiter.api.Test;

/**
 * Starts the whole context against a real Postgres, which also proves the Flyway baseline applies
 * cleanly and that Hibernate validates against the migrated schema ({@code ddl-auto: validate}).
 */
class ForgeApplicationTests extends AbstractPostgresIT {

    @Test
    void contextLoads() {}
}

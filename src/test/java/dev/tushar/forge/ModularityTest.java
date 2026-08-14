package dev.tushar.forge;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/**
 * Verifies the module boundaries described in the architecture.
 *
 * <p>These boundaries are the reason the agent runtime and execution harness can later be
 * extracted into separate services. Left to review discipline they erode; enforced here, a
 * violation fails the build.
 */
class ModularityTest {

    static final ApplicationModules MODULES = ApplicationModules.of(ForgeApplication.class);

    @Test
    void modulesAreValid() {
        MODULES.verify();
    }

    @Test
    void printModuleStructure() {
        MODULES.forEach(System.out::println);
    }
}

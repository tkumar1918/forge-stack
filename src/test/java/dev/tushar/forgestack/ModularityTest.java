package dev.tushar.forgestack;

import static org.assertj.core.api.Assertions.assertThat;

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

    static final ApplicationModules MODULES = ApplicationModules.of(ForgeStackApplication.class);

    @Test
    void modulesAreValid() {
        MODULES.verify();
    }

    /**
     * The module inventory, declared. Adding or renaming a module must edit this list.
     *
     * <p>No tool can tell that two names are confusable. {@code githubauth} and {@code githubapp}
     * were each accurate and together unreadable — one identified a human, the other granted the
     * agent authority over repositories, and both javadocs had to open by explaining they were not
     * the other one. Nothing mechanical catches that; it is a judgement, so this test exists to
     * force the judgement rather than to make it. It fails until the new name is written down
     * beside its neighbours, which is the moment to ask whether it can be mistaken for any of them.
     *
     * <p>Deliberately closed, for the same reason the transition table is: safety here comes from
     * concentration in one reviewed place, not from extensibility.
     */
    @Test
    void moduleNamesAreDeliberate() {
        assertThat(MODULES.stream().map(module -> module.getIdentifier().toString()))
                .containsExactlyInAnyOrder(
                        "api",
                        "audit",
                        "diffguard",
                        "githubinstallation",
                        "githublogin",
                        // "harness" and "runtime" both sound like "the thing that runs the work",
                        // which is exactly the confusion this test exists to force someone to look at.
                        // They divide on which side of the process boundary they sit: runtime is the
                        // Java control loop that decides what happens, harness is the port to the
                        // sandbox where a model actually touches the repository. Runtime is ours and
                        // always will be; harness is a seam over somebody else's code (Appendix B).
                        "harness",
                        "iam",
                        "platform",
                        "runtime",
                        "sandbox",
                        "task");
    }

    @Test
    void printModuleStructure() {
        MODULES.forEach(System.out::println);
    }
}

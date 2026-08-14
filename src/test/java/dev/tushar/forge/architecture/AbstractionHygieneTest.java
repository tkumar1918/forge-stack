package dev.tushar.forge.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import dev.tushar.forge.platform.Port;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Enforces the abstraction and coupling rules from the architecture.
 *
 * <p>The default is not to introduce interfaces. An abstraction must answer to a pressure that
 * exists today. Left to review discipline, the Spring reflex reintroduces {@code XService} /
 * {@code XServiceImpl} pairs within weeks, so it is enforced here instead.
 *
 * <p>A concrete class that later needs an interface is a ten-minute refactor. A wrong abstraction
 * propagates into every call site and is rarely removed. Late abstraction is cheap; wrong
 * abstraction is not.
 *
 * <p>{@link AbstractionRulesFireTest} proves these rules reject a real violation.
 */
class AbstractionHygieneTest {

    private static final String ROOT = "dev.tushar.forge";

    private static JavaClasses productionClasses;

    @BeforeAll
    static void importClasses() {
        productionClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(ROOT);
    }

    @Test
    void interfacesMustJustifyTheirExistence() {
        AbstractionRules.interfacesMustJustifyThemselves(ROOT)
                // There are currently no interfaces in production code at all, which is the
                // intended starting state.
                .allowEmptyShould(true)
                .check(productionClasses);
    }

    @Test
    void noImplSuffix() {
        AbstractionRules.noImplSuffix(ROOT).check(productionClasses);
    }

    /**
     * Modules are organised by concept, not by technical layer.
     *
     * <p>Inside a module, {@code internal} is grouped by aggregate — {@code internal/user},
     * {@code internal/workspace}, {@code internal/session} — so an entity and its repository sit
     * together and change together.
     */
    @Test
    void packagesAreNamedAfterConceptsNotLayers() {
        AbstractionRules.packagesAreNamedAfterConceptsNotLayers(ROOT).check(productionClasses);
    }

    /**
     * Keeps the execution substrate swappable.
     *
     * <p>A clean {@code SandboxProvider} interface does not decouple anything on its own — the
     * coupling hides in Docker-specific types leaking into callers.
     */
    @Test
    void dockerApiStaysInsideItsAdapter() {
        noClasses()
                .that()
                .resideOutsideOfPackage(ROOT + ".sandbox.docker..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("com.github.dockerjava..")
                .because("the Docker client is an implementation detail of one adapter")
                .check(productionClasses);
    }

    /** Structural guarantee that no GitHub credential path can reach the sandbox. */
    @Test
    void sandboxCannotReachGithubCredentials() {
        noClasses()
                .that()
                .resideInAPackage(ROOT + ".sandbox..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(ROOT + ".githubapp..")
                .because("repository content is attacker-controlled; a token inside the sandbox "
                        + "with any egress is a one-prompt exfiltration")
                // The sandbox module does not exist yet. The rule arms itself the moment it does,
                // which is the point of writing it now rather than remembering to write it later.
                .allowEmptyShould(true)
                .check(productionClasses);
    }

    /** Authority decisions must be computable without a model in the path. */
    @Test
    void policyDoesNotDependOnLlm() {
        noClasses()
                .that()
                .resideInAPackage(ROOT + ".policy..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(ROOT + ".llm..")
                .because("a compromised or mistaken model must not be able to widen its own authority")
                .allowEmptyShould(true)
                .check(productionClasses);
    }

    /**
     * The HTTP layer is a leaf. Nothing may depend on it.
     *
     * <p>Modulith enforces this only for as long as no module happens to list {@code api} in its
     * {@code allowedDependencies} — an omission, not a guarantee. Stated directly here, because
     * "moving to a different transport touches only this package" stops being true the moment a
     * controller package acquires a dependent.
     */
    @Test
    void nothingDependsOnTheApiModule() {
        noClasses()
                .that()
                .resideOutsideOfPackage(ROOT + ".api..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage(ROOT + ".api..")
                .because("the HTTP layer is the outermost ring; a dependent would invert it")
                .check(productionClasses);
    }

    /** Prints the current inventory so growth is noticed in review, not a year later. */
    @Test
    void printPortInventory() {
        List<String> ports = productionClasses.stream()
                .filter(JavaClass::isInterface)
                .filter(c -> c.isAnnotatedWith(Port.class))
                .map(JavaClass::getName)
                .sorted()
                .toList();

        System.out.println("@Port inventory (" + ports.size() + "):");
        ports.forEach(p -> System.out.println("  - " + p));
    }
}

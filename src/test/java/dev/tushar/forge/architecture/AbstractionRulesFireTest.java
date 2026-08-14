package dev.tushar.forge.architecture;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves the abstraction rules actually fail on a violation.
 *
 * <p>A rule nobody has seen fail is a rule nobody knows works. ArchUnit rules pass silently when a
 * package pattern matches nothing, so a suite can sit green for months while enforcing nothing —
 * which is worse than having no rule, because it buys false confidence.
 *
 * <p>Points the real rules at {@code fixtures}, which deliberately contains an unjustified
 * interface and an {@code Impl} class, and asserts they are rejected.
 */
class AbstractionRulesFireTest {

    private static final String FIXTURES = "dev.tushar.forge.architecture.fixtures";

    private static JavaClasses fixtureClasses;

    @BeforeAll
    static void importFixtures() {
        fixtureClasses = new ClassFileImporter().importPackages(FIXTURES);
    }

    @Test
    @DisplayName("an unjustified interface is rejected")
    void unjustifiedInterfaceIsRejected() {
        assertThatThrownBy(() -> AbstractionRules.interfacesMustJustifyThemselves(FIXTURES).check(fixtureClasses))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("ReportGenerator")
                .hasMessageContaining("no justification");
    }

    @Test
    @DisplayName("an Impl-suffixed class is rejected")
    void implSuffixIsRejected() {
        assertThatThrownBy(() -> AbstractionRules.noImplSuffix(FIXTURES).check(fixtureClasses))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("ReportGeneratorImpl");
    }
}

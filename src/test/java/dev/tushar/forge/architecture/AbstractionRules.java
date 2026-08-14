package dev.tushar.forge.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import dev.tushar.forge.platform.Port;

/**
 * The abstraction-hygiene rules, shared between the real check and the test that proves they fire.
 *
 * <p>Parameterised by base package so the same rule can be pointed at production code and at
 * deliberately-bad fixtures.
 */
final class AbstractionRules {

    private AbstractionRules() {}

    static ArchRule interfacesMustJustifyThemselves(String basePackage) {
        return ArchRuleDefinition.classes()
                .that()
                .areInterfaces()
                .and()
                .resideInAPackage(basePackage + "..")
                .and(notAnnotationsOrPackageDescriptors())
                .should(justifyBeingAnAbstraction());
    }

    static ArchRule noImplSuffix(String basePackage) {
        return noClasses()
                .that()
                .resideInAPackage(basePackage + "..")
                .should()
                .haveSimpleNameEndingWith("Impl")
                .because("an interface with a single Impl is indirection with no payoff; "
                        + "write the concrete class and revisit when a second implementation exists");
    }

    private static DescribedPredicate<JavaClass> notAnnotationsOrPackageDescriptors() {
        return new DescribedPredicate<>("are not annotations or package descriptors") {
            @Override
            public boolean test(JavaClass javaClass) {
                // package-info compiles to an interface, and Modulith module descriptors are
                // package-info files. They are not abstraction seams.
                return !javaClass.isAnnotation() && !javaClass.getSimpleName().equals("package-info");
            }
        };
    }

    private static ArchCondition<JavaClass> justifyBeingAnAbstraction() {
        return new ArchCondition<>("be @Port, a Spring Data repository, sealed, or have 2+ implementations") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                if (item.isAnnotatedWith(Port.class) || isSpringDataRepository(item) || item.reflect().isSealed()) {
                    return;
                }
                int implementations = item.getAllSubclasses().size();
                if (implementations >= 2) {
                    return;
                }
                events.add(SimpleConditionEvent.violated(
                        item,
                        ("%s is an interface with %d implementation(s) and no justification. "
                                        + "Annotate it @Port(\"why\"), make it sealed, or delete it and use the concrete class.")
                                .formatted(item.getName(), implementations)));
            }
        };
    }

    private static boolean isSpringDataRepository(JavaClass item) {
        return item.getAllRawInterfaces().stream()
                .anyMatch(i -> i.getName().startsWith("org.springframework.data.repository."));
    }
}

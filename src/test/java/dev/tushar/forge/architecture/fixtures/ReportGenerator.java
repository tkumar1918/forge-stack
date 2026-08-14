package dev.tushar.forge.architecture.fixtures;

/**
 * Deliberately violates the abstraction rules: an interface with exactly one implementation and no
 * {@code @Port} justification. Exists only so {@code AbstractionRulesFireTest} can prove the rules
 * still bite. Do not "fix" it.
 */
interface ReportGenerator {
    String generate();
}

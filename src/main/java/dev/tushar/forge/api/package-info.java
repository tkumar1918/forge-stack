/**
 * HTTP surface: controllers and DTOs only, no business logic.
 *
 * <p>Nothing depends on this module — asserted by {@code AbstractionHygieneTest}, not merely left
 * unlisted. It is the outermost layer and must stay thin enough that moving to a different
 * transport would touch only this package.
 *
 * <p>Organised by resource ({@code session}, {@code installation}), one sub-package per thing the
 * API exposes. Sub-packaging is free here precisely because nothing depends on this module, so
 * there are no named interfaces to declare.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "API",
        allowedDependencies = {"iam", "githublogin", "githubinstallation"})
package dev.tushar.forge.api;

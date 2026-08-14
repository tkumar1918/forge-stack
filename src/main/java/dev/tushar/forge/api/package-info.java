/**
 * HTTP surface: controllers and DTOs only, no business logic.
 *
 * <p>Nothing depends on this module. It is the outermost layer and must stay thin enough that
 * moving to a different transport would touch only this package.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "API",
        allowedDependencies = {"iam", "githubauth"})
package dev.tushar.forge.api;

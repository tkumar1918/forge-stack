/**
 * GitHub OAuth login — authenticating a human into Forge.
 *
 * <p>This module grants the agent nothing. It requests {@code read:user} and {@code user:email}
 * and never {@code repo}: logging into Forge must not imply that the agent may touch any
 * repository. Authority for the agent to act on GitHub comes from an explicitly installed GitHub
 * App, and lives in {@code githubapp}.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "GitHub OAuth",
        allowedDependencies = {"iam"})
package dev.tushar.forge.githubauth;

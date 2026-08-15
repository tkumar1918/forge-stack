/**
 * Signing a human in with GitHub, and nothing else.
 *
 * <p>Requests {@code read:user} and {@code user:email}, never {@code repo}: signing in identifies
 * a person and grants no access to any repository. Everything here is about the browser session —
 * the OAuth handshake, the session cookie, and the authenticated principal.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "GitHub Login",
        allowedDependencies = {"iam"})
package dev.tushar.forgestack.githublogin;

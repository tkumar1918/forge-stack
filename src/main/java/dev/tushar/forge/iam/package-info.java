/**
 * Identity and access: users, external identities, workspaces, memberships, sessions.
 *
 * <p>Owns the tenant boundary itself. Authentication into Forge happens here; authority for the
 * agent to act on GitHub does not — that belongs to {@code githubinstallation}.
 */
@org.springframework.modulith.ApplicationModule(displayName = "IAM")
package dev.tushar.forge.iam;

package dev.tushar.forgestack.githublogin;

import java.util.UUID;

/**
 * The authenticated caller, as the rest of the application sees it.
 *
 * <p>Carries the ForgeStack user id and the session's active workspace. The workspace is resolved from
 * the server-side session, never from a client-supplied header or path parameter — those are
 * requests, not proof of membership.
 */
public record ForgeStackPrincipal(UUID userId, UUID sessionId, UUID activeWorkspaceId) {}

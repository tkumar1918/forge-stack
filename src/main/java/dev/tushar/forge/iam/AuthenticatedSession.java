package dev.tushar.forge.iam;

import java.util.UUID;

/**
 * A validated session.
 *
 * <p>Carries no token — the caller already presented one, and re-exposing it would only widen the
 * number of places a credential can be logged or leaked.
 */
public record AuthenticatedSession(UUID sessionId, UUID userId, UUID workspaceId) {}

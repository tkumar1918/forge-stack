package dev.tushar.forge.iam;

import java.time.Instant;
import java.util.UUID;

/**
 * A newly issued session and its raw token.
 *
 * <p>This is the only moment the token exists outside the caller's request — only its SHA-256
 * digest is persisted, so it cannot be recovered afterwards.
 */
public record IssuedSession(UUID sessionId, String token, Instant expiresAt) {}

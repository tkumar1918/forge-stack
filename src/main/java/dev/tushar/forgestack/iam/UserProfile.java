package dev.tushar.forgestack.iam;

import java.util.UUID;

/**
 * A user as other modules see them.
 *
 * <p>The {@code User} entity stays inside {@code iam.internal}. Handing a JPA entity across a
 * module boundary exports the schema as API, drags lazy-loading and transaction lifetime into
 * unrelated code, and makes the entity impossible to change without a survey of every caller.
 */
public record UserProfile(UUID id, String email, String displayName, String avatarUrl) {}

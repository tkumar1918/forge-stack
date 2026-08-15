package dev.tushar.forge.githubinstallation;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A repository the workspace could have Forge maintain.
 *
 * <p>{@code managed} is on this record rather than in a separate list because the question a user
 * is actually asking is "which of my repositories is Forge looking after" — answering it from two
 * endpoints invites a UI that shows them disagreeing.
 */
public record AvailableRepository(
        UUID id,
        String fullName,
        boolean isPrivate,
        @Nullable String defaultBranch,
        boolean archived,
        boolean managed) {}

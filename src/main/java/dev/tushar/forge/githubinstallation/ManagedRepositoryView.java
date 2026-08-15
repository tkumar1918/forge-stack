package dev.tushar.forge.githubinstallation;

import java.util.UUID;

/** A repository Forge is maintaining, as other modules and the API see it. */
public record ManagedRepositoryView(
        UUID id, UUID repositoryId, String fullName, String status, AutonomyLevel autonomyLevel) {}

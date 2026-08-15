package dev.tushar.forgestack.githubinstallation;

import java.util.UUID;

/** A repository ForgeStack is maintaining, as other modules and the API see it. */
public record ManagedRepositoryView(
        UUID id, UUID repositoryId, String fullName, String status, AutonomyLevel autonomyLevel) {}

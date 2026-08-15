package dev.tushar.forgestack.githubinstallation;

import java.util.UUID;

/**
 * A bound installation, as other modules see it.
 *
 * <p>Carries no permission map: what an installation granted is the input to minting a token, and
 * that decision belongs to this module. Handing the raw grant outward invites callers to reason
 * about authority themselves, which is how a check ends up being made in two places and enforced
 * in neither.
 */
public record InstallationBinding(
        UUID id, long installationId, String accountLogin, String accountType, String repositorySelection) {}

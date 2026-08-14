package dev.tushar.forge.iam;

import java.util.UUID;

/** A workspace as other modules see them. */
public record WorkspaceSummary(UUID id, String slug, String name) {}

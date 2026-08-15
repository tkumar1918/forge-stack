package dev.tushar.forgestack.githubinstallation;

/**
 * What one sync changed.
 *
 * <p>{@code noLongerVisible} is worth surfacing rather than logging: it is how a customer finds
 * out that ForgeStack lost access to something, which must never be a silent event.
 */
public record RepositorySyncReport(int visible, int added, int noLongerVisible) {}

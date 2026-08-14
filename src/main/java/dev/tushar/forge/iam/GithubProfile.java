package dev.tushar.forge.iam;

/**
 * A verified GitHub identity, as supplied by the login flow.
 *
 * <p>Deliberately carries no access token: the OAuth token is used once to fetch the profile and
 * discarded, so there is nothing for this record to pass along.
 */
public record GithubProfile(String providerUserId, String login, String email, String name, String avatarUrl) {}

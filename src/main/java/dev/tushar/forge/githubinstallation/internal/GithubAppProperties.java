package dev.tushar.forge.githubinstallation.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * GitHub App configuration.
 *
 * @param appId numeric App id, used as the JWT issuer
 * @param privateKeyPem the App's private key in PKCS#8 PEM form
 * @param slug the App's URL slug, used to build the installation URL
 * @param webhookSecret HMAC secret for verifying webhook deliveries
 * @param apiBaseUrl GitHub REST base, overridable for GitHub Enterprise and for tests
 */
@ConfigurationProperties(prefix = "forge.github.app")
public record GithubAppProperties(
        String appId, String privateKeyPem, String slug, String webhookSecret, String apiBaseUrl) {

    public GithubAppProperties {
        if (apiBaseUrl == null || apiBaseUrl.isBlank()) {
            apiBaseUrl = "https://api.github.com";
        }
    }

    public boolean isConfigured() {
        return appId != null && !appId.isBlank() && privateKeyPem != null && !privateKeyPem.isBlank();
    }
}

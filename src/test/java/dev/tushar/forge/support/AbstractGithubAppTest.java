package dev.tushar.forge.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Integration tests that need a configured GitHub App pointed at {@link FakeGithub}.
 *
 * <p>The properties live here rather than in each test class so they resolve identically
 * everywhere. Spring caches a context per distinct property set, so a class that configured the
 * same things slightly differently would silently pay for a second context and a second round of
 * container startup.
 */
public abstract class AbstractGithubAppTest extends AbstractIntegrationTest {

    @DynamicPropertySource
    static void githubApp(DynamicPropertyRegistry registry) {
        registry.add("forge.github.app.app-id", () -> "123456");
        registry.add("forge.github.app.private-key-pem", FakeGithub::privateKeyPem);
        registry.add("forge.github.app.slug", () -> "forge-test");
        registry.add("forge.github.app.api-base-url", FakeGithub::baseUrl);
    }
}

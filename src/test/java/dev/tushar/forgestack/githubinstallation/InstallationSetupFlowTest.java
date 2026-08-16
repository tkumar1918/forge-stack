package dev.tushar.forgestack.githubinstallation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import dev.tushar.forgestack.support.AbstractGithubAppTest;
import dev.tushar.forgestack.support.BrowserLogin;
import dev.tushar.forgestack.support.FakeGithub;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * The install flow as a browser actually walks it, end to end over HTTP.
 *
 * <p>{@code InstallationBindingServiceTest} covers the same decisions at the service layer and is
 * the more precise place to assert them. This class exists for the one property that only appears
 * over HTTP: the flow spans several minutes and several requests on the user's side, so it can
 * straddle a re-login. That is what failed on the first real install — the nonce was bound to the
 * session that started the flow, the user logged in again while choosing repositories on GitHub, and
 * the callback arrived on a different session. GitHub had installed the App; ForgeStack recorded
 * nothing and rendered a blank 400.
 */
class InstallationSetupFlowTest extends AbstractGithubAppTest {

    @Autowired
    private MockMvc mvc;

    @Test
    @DisplayName("an install survives the user logging in again while GitHub has them")
    void installSurvivesReLogin() throws Exception {
        long githubUserId = FakeGithub.oauthUser("octocat", "octocat@example.invalid");
        long installationId = FakeGithub.installation(githubUserId, "octocat", "User");

        BrowserLogin first = BrowserLogin.logIn(mvc);
        String nonce = beginSetup(first);

        // The GitHub install screen takes minutes, and docs/local-setup.md sends a first-timer off
        // to check their Setup URL on the way. Re-authenticating in that window is ordinary.
        BrowserLogin second = BrowserLogin.logIn(mvc);

        MvcResult callback = mvc.perform(get("/api/installations/callback")
                        .param("installation_id", String.valueOf(installationId))
                        .param("setup_action", "install")
                        .param("state", nonce)
                        .session(second.servletSession())
                        .cookie(second.cookie()))
                .andReturn();

        assertThat(callback.getResponse().getStatus())
                .as("the same human, on a newer session, is not a CSRF attempt")
                .isEqualTo(302);
    }

    @Test
    @DisplayName("a nonce from a different user is refused")
    void nonceFromAnotherUserIsRefused() throws Exception {
        long victimGithubId = FakeGithub.oauthUser("victim", "victim@example.invalid");
        long installationId = FakeGithub.installation(victimGithubId, "victim", "User");
        BrowserLogin victim = BrowserLogin.logIn(mvc);
        String victimNonce = beginSetup(victim);

        // A different human entirely. Loosening the binding from session to user must not loosen it
        // to "anyone" — this is the CSRF protection the nonce exists for.
        FakeGithub.oauthUser("attacker", "attacker@example.invalid");
        BrowserLogin attacker = BrowserLogin.logIn(mvc);

        MvcResult callback = mvc.perform(get("/api/installations/callback")
                        .param("installation_id", String.valueOf(installationId))
                        .param("state", victimNonce)
                        .session(attacker.servletSession())
                        .cookie(attacker.cookie()))
                .andReturn();

        assertThat(callback.getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    @DisplayName("a rejection explains itself instead of rendering a blank page")
    void rejectionHasAReadableBody() throws Exception {
        FakeGithub.oauthUser("octocat-blank", "octocat-blank@example.invalid");
        BrowserLogin session = BrowserLogin.logIn(mvc);

        // GitHub redirects a real browser here, so an empty body is Chrome's "This page is not
        // working" and the user is told nothing at all.
        MvcResult callback = mvc.perform(get("/api/installations/callback")
                        .param("installation_id", "12345")
                        .param("state", "a-nonce-that-was-never-issued")
                        .session(session.servletSession())
                        .cookie(session.cookie()))
                .andReturn();

        assertThat(callback.getResponse().getStatus()).isEqualTo(400);
        assertThat(callback.getResponse().getContentAsString())
                .as("a browser-facing endpoint must say what went wrong")
                .isNotBlank()
                .contains("/api/installations/start");
    }

    /** Walks {@code /api/installations/start} and returns the nonce GitHub would carry back. */
    private String beginSetup(BrowserLogin login) throws Exception {
        MvcResult start = mvc.perform(get("/api/installations/start")
                        .session(login.servletSession())
                        .cookie(login.cookie()))
                .andReturn();

        String installUrl = Objects.requireNonNull(start.getResponse().getRedirectedUrl());
        return URLDecoder.decode(
                Objects.requireNonNull(UriComponentsBuilder.fromUriString(installUrl)
                        .build()
                        .getQueryParams()
                        .getFirst("state")),
                StandardCharsets.UTF_8);
    }
}

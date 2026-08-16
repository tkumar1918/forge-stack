package dev.tushar.forgestack.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import dev.tushar.forgestack.githublogin.internal.ForgeStackSessionCookie;
import jakarta.servlet.http.Cookie;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Drives a real GitHub login through the filter chain, against {@link FakeGithub}.
 *
 * <p>Shared rather than duplicated because the handshake has two details that are easy to get wrong
 * and produce the same opaque error either way — the servlet session must be the same object across
 * both legs, and the {@code state} must be URL-decoded (see {@link #logIn}).
 *
 * @param servletSession the JSESSIONID equivalent, to be passed to later requests
 * @param cookie the ForgeStack session cookie, which is what actually authenticates the API
 */
public record BrowserLogin(MockHttpSession servletSession, Cookie cookie) {

    /**
     * Completes an OAuth login and returns what a browser would then be holding.
     *
     * <p>The authorization endpoint is never called: Spring keys the stored authorization request by
     * {@code state}, so reading it out of the redirect is enough to play the callback directly.
     *
     * <p>Call {@link FakeGithub#oauthUser} first to say who is logging in. Calling this twice for
     * the same user is how a re-login is simulated — it yields a second, independent session.
     */
    public static BrowserLogin logIn(MockMvc mvc) throws Exception {
        MockHttpSession servletSession = new MockHttpSession();

        MvcResult start = mvc.perform(get("/oauth2/authorization/github").session(servletSession))
                .andReturn();
        String location = Objects.requireNonNull(start.getResponse().getRedirectedUrl());

        // Decoded deliberately. The state is base64 and routinely ends in '=', which reaches the
        // Location header as %3D; UriComponents does not decode on build(), so handing it straight
        // back produces a state matching nothing — surfacing as an OAuth2AuthenticationException
        // of [authorization_request_not_found], several layers from the cause.
        String state = URLDecoder.decode(
                Objects.requireNonNull(UriComponentsBuilder.fromUriString(location)
                        .build()
                        .getQueryParams()
                        .getFirst("state")),
                StandardCharsets.UTF_8);

        MvcResult callback = mvc.perform(get("/login/oauth2/code/github")
                        .param("code", "fake-code")
                        .param("state", state)
                        .session(servletSession))
                .andReturn();

        // Asserted here rather than left to fail downstream: without it a broken handshake shows up
        // as a NullPointerException on a null cookie, in tests that are not about the handshake.
        assertThat(callback.getResponse().getRedirectedUrl())
                .as("login should redirect on success, not to /login?error")
                .doesNotContain("error");

        Cookie cookie = callback.getResponse().getCookie(ForgeStackSessionCookie.NAME);
        assertThat(cookie).as("login should issue a ForgeStack session cookie").isNotNull();

        return new BrowserLogin(servletSession, cookie);
    }
}

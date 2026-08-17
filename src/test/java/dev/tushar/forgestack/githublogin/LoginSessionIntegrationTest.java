package dev.tushar.forgestack.githublogin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import dev.tushar.forgestack.iam.SessionService;
import dev.tushar.forgestack.support.AbstractIntegrationTest;
import dev.tushar.forgestack.support.BrowserLogin;
import dev.tushar.forgestack.support.FakeGithub;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;


/**
 * A browser logging in with GitHub, then using the API.
 *
 * <p>The gap this closes: nothing else in the suite authenticates a request at all, so the entire
 * filter chain was unverified. The first real browser login proved why that matters — login
 * succeeded and every authenticated endpoint then returned 500, because {@code oauth2Login}
 * persisted an {@code OAuth2AuthenticationToken} into the servlet session and
 * {@code ForgeStackSessionAuthenticationFilter} deferred to it instead of reading the ForgeStack
 * cookie. Controllers declaring {@code ForgeStackPrincipal} got null.
 *
 * <p>The bug needs a client holding <em>both</em> {@code JSESSIONID} and {@code forge_session} to
 * appear, which is why every {@code curl} check passed. One {@link MockHttpSession} is shared
 * across the requests below for exactly that reason — the session surviving between requests is the
 * defect, so it is made explicit rather than incidental.
 *
 * <p>MockMvc rather than a real port: the defect lives entirely inside the servlet filter chain,
 * which MockMvc runs for real. Note it performs no ERROR dispatch, so a failure here surfaces as a
 * thrown exception or a raw status rather than the Whitelabel page a browser sees.
 */
class LoginSessionIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private SessionService sessions;

    @Autowired
    private OAuth2AuthorizedClientService authorizedClients;

    private MockHttpSession servletSession;
    private Cookie forgeSession;
    private String githubLogin;

    @BeforeEach
    void logIn() throws Exception {
        githubLogin = "octocat-" + FakeGithub.nextId();
        FakeGithub.oauthUser(githubLogin, githubLogin + "@example.invalid");

        BrowserLogin login = BrowserLogin.logIn(mvc);
        servletSession = login.servletSession();
        forgeSession = login.cookie();
    }

    @Test
    @DisplayName("the callback issues a ForgeStack session cookie")
    void callbackIssuesSessionCookie() {
        assertThat(forgeSession).isNotNull();
        assertThat(forgeSession.getValue()).isNotBlank();
    }

    @Test
    @DisplayName("a logged-in browser reaches the API as its ForgeStack principal")
    void loggedInBrowserReachesTheApi() throws Exception {
        MvcResult result = mvc.perform(
                        get("/api/session").session(servletSession).cookie(forgeSession))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentAsString()).contains("activeWorkspaceId");
        assertThat(result.getResponse().getContentAsString()).doesNotContain("\"activeWorkspaceId\":null");
    }

    @Test
    @DisplayName("the servlet session alone is not a credential")
    void servletSessionIsNotACredential() throws Exception {
        // No ForgeStack cookie. Only JSESSIONID, which must authenticate nothing.
        MvcResult result =
                mvc.perform(get("/api/session").session(servletSession)).andReturn();

        // 401 rather than a redirect, because MockMvc sends none of the headers a browser navigating
        // to a page would. What is being pinned is the refusal; which form it takes for a person in
        // an address bar is ApiAuthenticationTest's job.
        assertThat(result.getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("login writes no security context into the servlet session")
    void loginPersistsNoSecurityContext() {
        // Directly pins the fix rather than its symptom: if nothing is stored, nothing can be
        // restored on the next request to shadow the cookie.
        assertThat(servletSession.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY))
                .isNull();
    }

    @Test
    @DisplayName("revoking the session logs the browser out immediately")
    void revocationTakesEffectAtOnce() throws Exception {
        // The filter's javadoc promises revocation lands on the next request. A surviving servlet
        // session would silently defeat that, which is the part worth pinning.
        sessions.revoke(forgeSession.getValue());

        MvcResult result = mvc.perform(
                        get("/api/session").session(servletSession).cookie(forgeSession))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("the GitHub user token is not kept after login")
    void githubUserTokenIsDiscarded() {
        // Not an assertion about the servlet session: Boot autoconfigures an
        // InMemoryOAuth2AuthorizedClientService, so the token is held on the heap keyed by
        // principal name — which ForgeStackOAuth2UserService sets to the GitHub login.
        //
        // Bound to a typed local first: loadAuthorizedClient is generic, and inlining it makes
        // assertThat ambiguous between the Predicate and IntPredicate overloads.
        OAuth2AuthorizedClient retained = authorizedClients.loadAuthorizedClient("github", githubLogin);

        assertThat(retained).isNull();
    }

    @Test
    @DisplayName("an OAuth2 token cannot reach the API")
    void oauth2TokenIsRefusedAtTheGate() throws Exception {
        // oauth2Login() bypasses the SecurityContextRepository entirely — it prefers a request
        // attribute — so this cannot be made to pass by not persisting the context. It passes only
        // once /api/** demands a ForgeStackPrincipal, which is the point of asserting it separately.
        MvcResult result =
                mvc.perform(get("/api/session").with(oauth2Login())).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
    }
}

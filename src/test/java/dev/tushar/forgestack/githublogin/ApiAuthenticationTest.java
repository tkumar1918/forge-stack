package dev.tushar.forgestack.githublogin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.tushar.forgestack.support.AbstractIntegrationTest;
import dev.tushar.forgestack.support.BrowserLogin;
import dev.tushar.forgestack.support.FakeGithub;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * What an unauthenticated request gets, depending on who is asking.
 *
 * <p>One answer suits nobody. A redirect to GitHub is right for a person in an address bar and
 * useless to {@code fetch}, which follows it to github.com and fails on CORS with an error mentioning
 * neither authentication nor this application. A bare 401 is right for a program and renders as a
 * blank page for a person.
 *
 * <p>Both halves are pinned here because they are one decision, and changing either without the other
 * is how this ends up wrong for one caller and nobody notices — the API half was wrong for a month
 * and only surfaced when there was a plan to build a frontend.
 */
class ApiAuthenticationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Nested
    @DisplayName("a person moving between pages")
    class BrowserNavigation {

        @Test
        @DisplayName("is sent to GitHub to sign in")
        void navigatingToTheApiRedirects() throws Exception {
            mvc.perform(get("/api/session")
                            .header("Sec-Fetch-Mode", "navigate")
                            .accept(MediaType.TEXT_HTML))
                    .andExpect(status().isFound())
                    .andExpect(result -> assertThat(result.getResponse().getRedirectedUrl())
                            .endsWith("/oauth2/authorization/github"));
        }

        /**
         * The case a path-based rule would have broken.
         *
         * <p>{@code /api/session} is where a successful login lands, so it is a URL people navigate to
         * and bookmark. An expired session there must re-authenticate and come back rather than
         * answering 401 — behaviour {@code known-gaps.md} §1.5 records as correct after it was
         * mistaken for a bug once already.
         */
        @Test
        @DisplayName("still gets sent to sign in when it is an old browser with no Sec-Fetch-Mode")
        void olderBrowsersFallBackToAccept() throws Exception {
            mvc.perform(get("/api/session").accept(MediaType.TEXT_HTML))
                    .andExpect(status().isFound())
                    .andExpect(result -> assertThat(result.getResponse().getRedirectedUrl())
                            .endsWith("/oauth2/authorization/github"));
        }

        @Test
        @DisplayName("and login itself still works end to end")
        void loggingInStillWorks() throws Exception {
            String login = "navigator-" + FakeGithub.nextId();
            FakeGithub.oauthUser(login, login + "@example.test");

            BrowserLogin session = BrowserLogin.logIn(mvc);

            mvc.perform(get("/api/session").cookie(session.cookie()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.activeWorkspaceId").isNotEmpty());
        }
    }

    @Nested
    @DisplayName("a program calling the API")
    class ProgrammaticCallers {

        @Test
        @DisplayName("gets 401 with somewhere to send the person next")
        void fetchGets401() throws Exception {
            mvc.perform(get("/api/tasks")
                            .header("Sec-Fetch-Mode", "cors")
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error").value("unauthenticated"))
                    .andExpect(jsonPath("$.signIn").value("/oauth2/authorization/github"));
        }

        /**
         * A {@code fetch} asking for HTML is still a {@code fetch}.
         *
         * <p>Deciding on {@code Accept} alone would get this one wrong, which is why
         * {@code Sec-Fetch-Mode} is consulted first rather than as a tie-break.
         */
        @Test
        @DisplayName("gets 401 even when it asks for HTML")
        void fetchAskingForHtmlStillGets401() throws Exception {
            mvc.perform(get("/api/tasks")
                            .header("Sec-Fetch-Mode", "same-origin")
                            .accept(MediaType.TEXT_HTML))
                    .andExpect(status().isUnauthorized());
        }

        /**
         * {@code curl} sends neither header, and 401 is the answer it wants.
         *
         * <p>A terminal cannot do anything with a redirect to a login page, and this one has been
         * read as broken authentication before — {@code known-gaps.md} §1.2 is the same confusion
         * from the other direction.
         */
        @Test
        @DisplayName("gets 401 from a command line with no browser headers at all")
        void curlGets401() throws Exception {
            mvc.perform(get("/api/tasks")).andExpect(status().isUnauthorized());
        }

        /**
         * And leaves nothing behind on the server.
         *
         * <p>Refusing a request used to create a servlet session, because Spring stashes the current
         * request before sending anyone to sign in. Nothing ever read it back — login lands on a
         * fixed URL — so the only lasting effect was that anything polling or scanning the API while
         * signed out accumulated sessions.
         */
        @Test
        @DisplayName("is refused without being given a session to keep")
        void refusingCreatesNoSession() throws Exception {
            mvc.perform(get("/api/tasks"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(result -> assertThat(result.getRequest().getSession(false))
                            .isNull());
        }

        @Test
        @DisplayName("gets a body it can actually parse")
        void theBodyIsJson() throws Exception {
            String body = mvc.perform(get("/api/repositories"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string("Content-Type", MediaType.APPLICATION_JSON_VALUE))
                    .andReturn()
                    .getResponse()
                    .getContentAsString();

            assertThat(body).contains("\"error\":\"unauthenticated\"");
        }
    }

    @Test
    @DisplayName("health is still open to anyone")
    void healthIsUnaffected() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }
}

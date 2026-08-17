package dev.tushar.forgestack.api.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dev.tushar.forgestack.runtime.TaskWorker;
import dev.tushar.forgestack.support.AbstractIntegrationTest;
import dev.tushar.forgestack.support.BrowserLogin;
import dev.tushar.forgestack.support.FakeGithub;
import java.time.Duration;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The task API over the real filter chain.
 *
 * <p>Driven through a browser login rather than a fabricated principal, because the endpoints read
 * the caller's workspace from the session and a test that supplied one directly would prove nothing
 * about what a signed-in person can actually reach. §1.8 was exactly that gap: a green suite and
 * every authenticated endpoint returning 500 in a browser.
 */
class TaskApiTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private TaskWorker worker;

    private BrowserLogin session;

    @BeforeEach
    void signIn() throws Exception {
        String login = "tasker-" + FakeGithub.nextId();
        FakeGithub.oauthUser(login, login + "@example.test");
        this.session = BrowserLogin.logIn(mvc);
    }

    @Test
    @DisplayName("creating a task answers 202 with a queued task")
    void createQueuesTheTask() throws Exception {
        mvc.perform(post("/api/tasks")
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {"title":"Upgrade Spring Boot","goal":"Move to 4.1","simulatedOutcome":"SUCCEED"}
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.state").value("QUEUED"))
                .andExpect(jsonPath("$.title").value("Upgrade Spring Boot"))
                // 202 and not 201: the resource exists, and the thing the caller asked for has not
                // happened. Answering 201 would suggest the work was done.
                .andExpect(jsonPath("$.attemptCount").value(0));
    }

    @Test
    @DisplayName("a task without a goal is refused before it reaches the FSM")
    void validationRejectsAnEmptyTask() throws Exception {
        mvc.perform(post("/api/tasks")
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"","goal":""}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("the detail view replays how a task got where it is")
    void detailShowsTheWholeHistory() throws Exception {
        UUID taskId = create("SUCCEED");
        workUntilDone(taskId);

        mvc.perform(get("/api/tasks/{id}", taskId).cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.state").value("COMPLETED"))
                .andExpect(jsonPath("$.attempts.length()").value(1))
                // Admitted, queued, claimed, completed — each one a row, and in that order.
                .andExpect(jsonPath("$.transitions.length()").value(4))
                .andExpect(jsonPath("$.transitions[0].event").value("ADMIT"))
                .andExpect(jsonPath("$.transitions[3].event").value("COMPLETE"))
                // The completion transition records what every guard concluded, enforced or not —
                // so a task completed today says permanently which rules were not applied to it.
                .andExpect(jsonPath("$.transitions[3].guardResults")
                        .value(Matchers.containsString("NOT_ENFORCED")));
    }

    @Test
    @DisplayName("a person can answer a task that asked for one")
    void answeringAnEscalation() throws Exception {
        UUID taskId = create("ESCALATE");
        workUntilState(taskId, "AWAITING_HUMAN");

        mvc.perform(post("/api/tasks/{id}/answer", taskId)
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"resume":false,"reason":"not this quarter"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("CANCELLED"))
                .andExpect(jsonPath("$.terminalReason").value("not this quarter"));
    }

    /**
     * An event the task's state has no answer to is a conflict, not a bad request.
     *
     * <p>The payload was well formed and would have worked a moment earlier. Answering 400 would send
     * the caller hunting for a mistake in its own request.
     */
    @Test
    @DisplayName("answering a task that never asked is a 409 naming the state")
    void answeringSomethingThatIsNotWaiting() throws Exception {
        UUID taskId = create("SUCCEED");

        mvc.perform(post("/api/tasks/{id}/answer", taskId)
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"resume":true}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("illegal_transition"))
                .andExpect(jsonPath("$.state").value("QUEUED"));
    }

    @Test
    @DisplayName("cancelling stops a task wherever it is")
    void cancelling() throws Exception {
        UUID taskId = create("SUCCEED");

        mvc.perform(post("/api/tasks/{id}/cancel", taskId)
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"no longer needed"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("CANCELLED"));
    }

    @Test
    @DisplayName("another workspace's task is not found rather than forbidden")
    void tasksAreInvisibleAcrossWorkspaces() throws Exception {
        UUID taskId = create("SUCCEED");

        String other = "stranger-" + FakeGithub.nextId();
        FakeGithub.oauthUser(other, other + "@example.test");
        BrowserLogin stranger = BrowserLogin.logIn(mvc);

        mvc.perform(get("/api/tasks/{id}", taskId).cookie(stranger.cookie()))
                // Not 403: saying "forbidden" would confirm the id exists somewhere.
                .andExpect(status().isNotFound());
    }

    /**
     * The API needs a session, and says so in a way a program can read.
     *
     * <p>This was a 302 to github.com until the entry point learned to tell a person from a program —
     * a redirect that {@code fetch} follows, fails on CORS, and reports as something mentioning
     * neither authentication nor this application. The browser half of that decision is pinned in
     * {@code ApiAuthenticationTest}.
     */
    @Test
    @DisplayName("the API refuses a request with no session")
    void unauthenticatedIsRefused() throws Exception {
        mvc.perform(get("/api/tasks"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("unauthenticated"));
    }

    // ---------------------------------------------------------------------------------------

    private UUID create(String simulation) throws Exception {
        String body = mvc.perform(post("/api/tasks")
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {"title":"A task","goal":"do the thing","simulatedOutcome":"%s"}
                                """
                                        .formatted(simulation)))
                .andExpect(status().isAccepted())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    private void workUntilDone(UUID taskId) {
        workUntilState(taskId, "COMPLETED");
    }

    /** Runs the worker until the task reaches a state, waiting out the asynchronous outbox relay. */
    private void workUntilState(UUID taskId, String expected) {
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            worker.runAvailableWork();
            String body = mvc.perform(get("/api/tasks/{id}", taskId).cookie(session.cookie()))
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            assertThat((String) JsonPath.read(body, "$.task.state")).isEqualTo(expected);
        });
    }
}

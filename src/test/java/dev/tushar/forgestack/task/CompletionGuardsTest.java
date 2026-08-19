package dev.tushar.forgestack.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tushar.forgestack.platform.tenancy.TenantScope;
import dev.tushar.forgestack.support.AbstractIntegrationTest;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * What it takes to call a task done, and what it takes to be refused.
 *
 * <p>The other half of step 2.3's exit criterion: {@code COMPLETE} must be refused when any single
 * precondition is removed. Each test below builds a task that would complete, breaks exactly one
 * thing, and asserts the refusal — so a guard that silently stopped deciding anything cannot stay
 * hidden behind the guards beside it.
 */
class CompletionGuardsTest extends AbstractIntegrationTest {

    @Autowired
    private TaskStateService taskStates;

    @Autowired
    private TenantScope tenantScope;

    @Autowired
    private TaskLeases leases;

    @Autowired
    private LeaseScope leaseScope;

    @Autowired
    private JdbcTemplate jdbc;

    private TaskRows rows;
    private UUID workspaceId;
    private UUID taskId;

    @BeforeEach
    void aTaskThatCouldComplete() {
        this.rows = new TaskRows(tenantScope, leases, leaseScope, jdbc);
        this.workspaceId = rows.newWorkspace();
        this.taskId = rows.newTask(workspaceId, "RUNNING");
        attempt(1, "SUCCEEDED");
    }

    @Test
    @DisplayName("a task with a succeeded attempt and no overspend completes")
    void theHappyPathCompletes() {
        assertThatCode(() -> complete()).doesNotThrowAnyException();

        assertThat(rows.stateOf(workspaceId, taskId)).isEqualTo("COMPLETED");
    }

    /**
     * Only this guard may refuse here, and that is the assertion doing the work.
     *
     * <p>Attempt 1 succeeded and attempt 2 is running, so the last finished attempt is still a
     * success — leaving {@code NO_ATTEMPT_IN_FLIGHT} as the only thing standing in the way. If it
     * ever stopped deciding anything, the neighbouring guard would not quietly cover for it.
     */
    @Test
    @DisplayName("completion is refused while an attempt is still running")
    void anAttemptInFlightRefusesCompletion() {
        attempt(2, null);

        assertThatThrownBy(this::complete)
                .isInstanceOf(GuardsRefusedException.class)
                .hasMessageContaining("NO_ATTEMPT_IN_FLIGHT")
                .hasMessageNotContaining("LATEST_ATTEMPT_SUCCEEDED");

        assertThat(rows.stateOf(workspaceId, taskId)).isEqualTo("RUNNING");
    }

    @Test
    @DisplayName("completion is refused when the latest attempt did not succeed")
    void aFailedLatestAttemptRefusesCompletion() {
        attempt(2, "FAILED");

        assertThatThrownBy(this::complete)
                .isInstanceOf(GuardsRefusedException.class)
                .hasMessageContaining("LATEST_ATTEMPT_SUCCEEDED");
    }

    /**
     * An earlier success does not carry forward.
     *
     * <p>The guard reads the <em>latest</em> attempt, and it has to: a task that succeeded once and
     * then failed on a retry has ended up somewhere the earlier success says nothing about.
     */
    @Test
    @DisplayName("an earlier success does not rescue a later failure")
    void onlyTheLatestAttemptCounts() {
        attempt(2, "FAILED");
        attempt(3, "ABORTED");

        assertThatThrownBy(this::complete)
                .isInstanceOf(GuardsRefusedException.class)
                .hasMessageContaining("LATEST_ATTEMPT_SUCCEEDED");
    }

    /**
     * A task cut off mid-verification cannot then be declared finished.
     *
     * <p>Otherwise the cheapest way to complete work is to stop paying for it just before anyone
     * checks it, which is exactly the incentive this system must not create.
     */
    @Test
    @DisplayName("completion is refused when the budget was exceeded")
    void overspendRefusesCompletion() {
        tenantScope.runInTenant(
                workspaceId,
                () -> jdbc.update(
                        "UPDATE tasks SET budget_usd_micros = 1000, consumed_usd_micros = 5000 WHERE id = ?", taskId));

        assertThatThrownBy(this::complete)
                .isInstanceOf(GuardsRefusedException.class)
                .hasMessageContaining("WITHIN_BUDGET");
    }

    @Test
    @DisplayName("giving up needs the attempt cap actually reached")
    void abandoningNeedsTheCapReached() {
        assertThatThrownBy(() -> taskStates.apply(
                        workspaceId, taskId, TaskEvent.ABANDON, Actor.scheduler(), "out of patience"))
                .isInstanceOf(GuardsRefusedException.class)
                .hasMessageContaining("ATTEMPT_CAP_REACHED");

        tenantScope.runInTenant(
                workspaceId, () -> jdbc.update("UPDATE tasks SET attempt_count = max_attempts WHERE id = ?", taskId));

        assertThatCode(() -> taskStates.apply(
                        workspaceId, taskId, TaskEvent.ABANDON, Actor.scheduler(), "attempt cap reached"))
                .doesNotThrowAnyException();
    }

    /**
     * Every guard's verdict is written down, including the ones that decided nothing.
     *
     * <p>This is what stops the unenforced half of the rule from being invisible. A task completed
     * today carries a permanent record that five of its eight preconditions were never checked, so
     * nobody reading its history in a year has to reconstruct what this system verified at the time.
     */
    @Test
    @DisplayName("the transition row records which guards ran and which did not")
    void guardVerdictsAreRecorded() {
        complete();

        String verdicts = tenantScope.runInTenant(workspaceId, () -> jdbc.queryForObject(
                "SELECT guard_results::text FROM task_state_transitions WHERE task_id = ? AND event = 'COMPLETE'",
                String.class,
                taskId));

        assertThat(verdicts).contains("\"LATEST_ATTEMPT_SUCCEEDED\": \"PASSED\"");
        assertThat(verdicts)
                .as("an unenforced guard must say so rather than be absent, which would read as passed")
                .contains("\"VERIFICATION_PASSED\": \"NOT_ENFORCED\"");
    }

    /**
     * The guard that makes the product mean anything.
     *
     * <p>Every other precondition asks whether the work went well. This one asks whether the work
     * was made to look like it went well, which is the only question an autonomous maintainer can be
     * trusted on. Tests green and the failing test deleted is the exact state it exists to refuse.
     */
    @Test
    @DisplayName("a task whose diff guards refused cannot complete, however well it otherwise went")
    void refusedDiffGuardsBlockCompletion() {
        this.taskId = rows.newTask(workspaceId, "RUNNING");
        attempt(1, "SUCCEEDED", "REFUSED");

        assertThatThrownBy(this::complete)
                .isInstanceOf(GuardsRefusedException.class)
                .hasMessageContaining("DIFF_GUARDS_PASSED");
        assertThat(rows.stateOf(workspaceId, taskId)).isEqualTo("RUNNING");
    }

    /**
     * Never having been checked is not the same as having passed.
     *
     * <p>The guard requires an explicit pass rather than the absence of a refusal, so that skipping
     * verification is not a route to completion. A guard written the other way around would be
     * satisfied by an attempt that did nothing at all.
     */
    @Test
    @DisplayName("an attempt that never reached the diff guards cannot complete either")
    void unrunDiffGuardsBlockCompletion() {
        this.taskId = rows.newTask(workspaceId, "RUNNING");
        attempt(1, "SUCCEEDED", null);

        assertThatThrownBy(this::complete)
                .isInstanceOf(GuardsRefusedException.class)
                .hasMessageContaining("DIFF_GUARDS_PASSED");
    }

    /**
     * The pending set, pinned.
     *
     * <p>Four of §10.3's completion preconditions read data that does not exist yet — diff guards
     * were a fifth until §17's arrived. Listing them
     * here means shrinking the set is a deliberate edit and growing it is a conversation — the same
     * device as {@code ModularityTest.moduleNamesAreDeliberate}, for the same reason: nothing
     * mechanical notices a guard quietly joining the list of things nobody checks.
     */
    @Test
    @DisplayName("exactly these guards are declared but not yet enforcing anything")
    void theUnenforcedGuardsAreKnown() {
        List<TaskGuard> pending = Arrays.stream(TaskGuard.values())
                .filter(guard -> guard.enforcement() == TaskGuard.Enforcement.PENDING)
                .toList();

        assertThat(pending)
                .containsExactlyInAnyOrder(
                        TaskGuard.VERIFICATION_PASSED,
                        TaskGuard.NO_OPEN_HUMAN_INTERVENTION,
                        TaskGuard.AUTHORITY_SUFFICIENT,
                        TaskGuard.ACCEPTED_BY_HUMAN_OR_MERGED);
        assertThat(pending).allSatisfy(guard -> assertThat(guard.pendingOn())
                .as("a pending guard must say what it is waiting for, or it reads as an oversight")
                .isNotBlank());
    }

    // ---------------------------------------------------------------------------------------

    private void complete() {
        taskStates.apply(workspaceId, taskId, TaskEvent.COMPLETE, Actor.human(UUID.randomUUID()), "accepted");
    }

    private void attempt(int attemptNo, String outcome) {
        attempt(attemptNo, outcome, "PASSED");
    }

    /**
     * @param diffGuardVerdict what §17's guards made of the attempt's diff. Defaulted to
     *     {@code PASSED} for the fixtures because any attempt that finished verification has one —
     *     null here means "never checked", which is its own test below.
     */
    private void attempt(int attemptNo, String outcome, String diffGuardVerdict) {
        tenantScope.runInTenant(workspaceId, () -> jdbc.update(
                """
                INSERT INTO task_attempts
                    (task_id, workspace_id, attempt_no, outcome, ended_at, diff_guard_verdict, diff_guard_findings)
                VALUES (?, ?, ?, ?, CASE WHEN ?::text IS NULL THEN NULL ELSE now() END, ?,
                        CASE WHEN ?::text = 'REFUSED' THEN 'a test was deleted' ELSE NULL END)
                """,
                taskId,
                workspaceId,
                attemptNo,
                outcome,
                outcome,
                diffGuardVerdict,
                diffGuardVerdict));
    }
}

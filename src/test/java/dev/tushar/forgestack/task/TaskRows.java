package dev.tushar.forgestack.task;

import dev.tushar.forgestack.platform.tenancy.TenantScope;
import java.time.Duration;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Rows to test against, written directly.
 *
 * <p>Direct SQL rather than a service, because the services that would create these do not exist
 * until step 2.3 and the guarantees being tested are the ones the runtime will inherit from the
 * schema. Backdating is here for the same reason: a lease that lapsed and a task queued an hour ago
 * are both states this code has to handle, and neither is worth an hour of a test suite's time.
 *
 * <p>Every write goes through whatever claim currently exists on the task, because V10's trigger
 * refuses unfenced writes and gives test fixtures no exemption. That is worth having rather than
 * working around: a fixture that could write past the rule would be a fixture that could set up
 * states the real system can never reach, and tests against those prove nothing.
 */
class TaskRows {

    private final TenantScope tenantScope;
    private final TaskLeases leases;
    private final LeaseScope leaseScope;
    private final JdbcTemplate jdbc;

    TaskRows(TenantScope tenantScope, TaskLeases leases, LeaseScope leaseScope, JdbcTemplate jdbc) {
        this.tenantScope = tenantScope;
        this.leases = leases;
        this.leaseScope = leaseScope;
        this.jdbc = jdbc;
    }

    UUID newWorkspace() {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO workspaces (id, slug, name) VALUES (?, ?, ?)",
                id,
                "ws-" + id.toString().substring(0, 8),
                "Jobs test");
        return id;
    }

    UUID newTask(UUID workspaceId, String state) {
        return tenantScope.runInTenant(workspaceId, () -> {
            UUID id = UUID.randomUUID();
            jdbc.update(
                    """
                    INSERT INTO tasks (id, workspace_id, origin, title, goal, state)
                    VALUES (?, ?, 'USER', 'Trivial job', 'Prove the substrate', ?)
                    """,
                    id,
                    workspaceId,
                    state);
            return id;
        });
    }

    /**
     * Makes a live claim look like one whose holder stopped renewing — a worker that was killed.
     *
     * <p>Written as the holder, which is also the only party that could honestly do this: nothing
     * outside the worker gets to declare that a worker has stopped, and the reconciler's whole job is
     * to notice it without being told.
     */
    void expireLease(UUID workspaceId, UUID taskId) {
        underCurrentClaim(
                workspaceId,
                taskId,
                () -> jdbc.update(
                        "UPDATE tasks SET lease_expires_at = now() - make_interval(secs => 1) WHERE id = ?", taskId));
    }

    /** Makes a task look as though it entered its current state {@code ago} in the past. */
    void backdateStateEntry(UUID workspaceId, UUID taskId, Duration ago) {
        underCurrentClaim(
                workspaceId,
                taskId,
                () -> jdbc.update(
                        "UPDATE tasks SET state_entered_at = now() - make_interval(secs => ?) WHERE id = ?",
                        (double) ago.toMillis() / 1000,
                        taskId));
    }

    /** Makes the reconciler's last re-queue of a task look old enough for another one to be due. */
    void backdateRequeue(UUID workspaceId, UUID taskId, Duration ago) {
        underCurrentClaim(
                workspaceId,
                taskId,
                () -> jdbc.update(
                        "UPDATE tasks SET requeued_at = now() - make_interval(secs => ?) WHERE id = ?",
                        (double) ago.toMillis() / 1000,
                        taskId));
    }

    String stateOf(UUID workspaceId, UUID taskId) {
        return tenantScope.runInTenant(
                workspaceId, () -> jdbc.queryForObject("SELECT state FROM tasks WHERE id = ?", String.class, taskId));
    }

    int transitionsFor(UUID workspaceId, UUID taskId, String event) {
        Integer count = tenantScope.runInTenant(workspaceId, () -> jdbc.queryForObject(
                "SELECT count(*) FROM task_state_transitions WHERE task_id = ? AND event = ?",
                Integer.class,
                taskId,
                event));
        return count == null ? 0 : count;
    }

    /** Runs a write under whichever claim the task carries right now, which may be none. */
    private void underCurrentClaim(UUID workspaceId, UUID taskId, Runnable write) {
        Lease claim = leases.current(workspaceId, taskId).orElseThrow();
        leaseScope.runUnderLease(claim, write);
    }
}

/**
 * Tasks: the unit of work the whole system is organised around.
 *
 * <p>Currently the durable half only — leases over the {@code tasks} rows, and the reconciler that
 * takes them back when a worker stops proving it is alive. The state machine that decides what a
 * task may do next arrives in step 2.3; nothing here interprets a task's goal or its state beyond
 * the two states a lost worker has to be rescued from.
 *
 * <p><strong>Why leases live here and not in {@code platform.jobs}.</strong> The plan put them in
 * platform beside the queue. They moved because of what fencing actually requires: a stalled worker
 * is stopped by making its write conditional on the epoch <em>in the row it is writing</em>, and a
 * predicate on another table is not the same guarantee — Postgres re-checks a concurrently updated
 * row against the {@code WHERE} clause, but it does not re-run a subquery against a lease table that
 * moved on meanwhile. So the epoch belongs on {@code tasks}, and whatever owns {@code tasks} owns
 * the lease. Reconciliation followed it for a second reason: it has to iterate workspaces to satisfy
 * row-level security, which platform cannot do without knowing what a workspace is.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Task",
        allowedDependencies = {"iam", "platform", "platform::tenancy", "platform::jobs"})
package dev.tushar.forgestack.task;

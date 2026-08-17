/**
 * The work queue, and the transactional outbox that is the only sanctioned way onto it.
 *
 * <p>Nothing here knows what a task is. A job carries a kind, a workspace, and the id of whatever
 * the job is about; interpreting that id is the domain's business. That is what keeps the runtime
 * extractable later — this module would move with it unchanged.
 *
 * <p><strong>The rule this module exists to enforce.</strong> Business code never writes to Redis.
 * It publishes {@link dev.tushar.forgestack.platform.jobs.JobEnqueueRequested} inside its own
 * transaction, which Spring Modulith persists to {@code event_publication}; the relay puts it on the
 * stream after that transaction commits. Enqueueing inside the transaction leaves a phantom job
 * behind a rollback, and enqueueing after commit with nothing durable in between loses the work
 * whenever the process dies in the gap. The outbox is what removes both.
 *
 * <p>A <em>named interface</em> of {@code platform}, for the same reason {@code tenancy} is one:
 * Modulith forbids reaching into another module's nested module, so callers declare
 * {@code platform::jobs}.
 */
@org.springframework.modulith.NamedInterface("jobs")
package dev.tushar.forgestack.platform.jobs;

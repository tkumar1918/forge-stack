package dev.tushar.forgestack.platform.jobs;

/**
 * "This job should be queued once my transaction commits."
 *
 * <p>The one thing business code publishes to get work onto the queue. Spring Modulith persists it
 * to {@code event_publication} in the publishing transaction, so the intent survives a crash, and
 * {@link OutboxRelay} carries it to Redis afterwards.
 *
 * <p>A concrete record rather than an interface domain events implement. Making every queueable
 * event implement a platform type would put a platform import in every domain event and buy nothing:
 * the relay needs the job, and the job is all this carries.
 */
public record JobEnqueueRequested(JobMessage job) {

    public JobEnqueueRequested {
        if (job == null) {
            throw new IllegalArgumentException("nothing to enqueue");
        }
    }
}

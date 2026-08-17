package dev.tushar.forgestack.platform.jobs;

import dev.tushar.forgestack.platform.Port;
import java.time.Duration;
import java.util.List;

/**
 * The queue itself.
 *
 * <p>At-least-once, always. A message is redelivered until it is acknowledged, which means every
 * consumer must be idempotent — that is a hard rule of this system (plan §21), not a caveat.
 */
@Port("a Redis Streams adapter in production and an in-memory queue in tests: the reconciler and "
        + "crash-recovery tests assert on exact delivery counts, which no test can do against a "
        + "broker whose timing it does not control")
public interface JobQueue {

    /**
     * Puts a job on its stream.
     *
     * <p>Call this from the relay, not from business code. A domain module publishes
     * {@link JobEnqueueRequested} instead, so the intent is durable before Redis is involved.
     */
    void enqueue(JobMessage job);

    /**
     * Claims up to {@code count} jobs of one kind for {@code consumer}, waiting up to {@code block}.
     *
     * <p>Claimed, not read: an unacknowledged job stays assigned to this consumer and is visible to
     * anyone asking which worker has been holding what, and for how long.
     */
    List<DeliveredJob> poll(String kind, String consumer, int count, Duration block);

    /** Marks a job done. Until this is called the job is still owed to somebody. */
    void acknowledge(DeliveredJob delivered);

    /** How many jobs of this kind are on the stream — unclaimed and claimed-but-unacknowledged. */
    long depth(String kind);
}

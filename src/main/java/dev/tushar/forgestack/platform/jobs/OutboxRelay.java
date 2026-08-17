package dev.tushar.forgestack.platform.jobs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Carries enqueue intents from the outbox to Redis.
 *
 * <p>The whole crash-safety argument of this module is in the annotation.
 * {@code @ApplicationModuleListener} runs after the publishing transaction commits, and Spring
 * Modulith has already written the event to {@code event_publication} inside that same transaction.
 * So:
 *
 * <ul>
 *   <li>the publisher rolls back → the outbox row rolls back with it, and no job is ever queued;
 *   <li>the process dies between commit and this method → the row is still there, incomplete, and
 *       is republished on the next start ({@code republish-outstanding-events-on-restart});
 *   <li>Redis refuses the write → this method throws, the row stays incomplete, same recovery.
 * </ul>
 *
 * <p>Which leaves exactly one failure mode: this method succeeds and then the completion write is
 * lost, so the job is queued twice. That is why delivery is documented as at-least-once and why
 * every consumer has to be idempotent. Duplicating a job is a cost; losing one is a bug.
 */
@Component
class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final JobQueue queue;

    OutboxRelay(JobQueue queue) {
        this.queue = queue;
    }

    @ApplicationModuleListener
    void on(JobEnqueueRequested request) {
        JobMessage job = request.job();
        queue.enqueue(job);
        log.debug("relayed job {} of kind {} for workspace {}", job.id(), job.kind(), job.workspaceId());
    }
}

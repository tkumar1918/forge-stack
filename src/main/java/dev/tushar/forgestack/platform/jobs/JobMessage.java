package dev.tushar.forgestack.platform.jobs;

import java.time.Instant;
import java.util.UUID;

/**
 * One unit of queued work.
 *
 * <p>Deliberately a pointer, not a payload. The queue carries the id of the row the work is about
 * and nothing else, so a job that sits on the stream for an hour cannot act on an hour-old copy of
 * the world — the consumer re-reads Postgres, which is the only source of truth. It also keeps
 * customer data out of Redis, which is unencrypted, unpersisted, and losable by design.
 *
 * @param id an idempotency key, not just an identifier. Delivery is at-least-once, so the same
 *     message may arrive twice; a consumer that has already handled this id must be able to say so.
 * @param kind which stream this belongs on, and therefore which consumer group handles it
 * @param workspaceId the tenant to re-enter before touching anything. Carried explicitly because
 *     the relay and the consumer both run outside any tenant scope and cannot infer it.
 * @param resourceId what the job is about. Meaningless here on purpose.
 * @param enqueuedAt when the intent was recorded, not when it reached Redis — the difference is
 *     relay lag, which is the number worth alerting on.
 */
public record JobMessage(UUID id, String kind, UUID workspaceId, UUID resourceId, Instant enqueuedAt) {

    public JobMessage {
        if (kind == null || kind.isBlank()) {
            throw new IllegalArgumentException("a job needs a kind: it selects the stream");
        }
        if (workspaceId == null) {
            throw new IllegalArgumentException("a job needs a workspace: the consumer has no other way to find one");
        }
    }

    /** A new job about {@code resourceId}, with a freshly minted idempotency key. */
    public static JobMessage of(String kind, UUID workspaceId, UUID resourceId) {
        return new JobMessage(UUID.randomUUID(), kind, workspaceId, resourceId, Instant.now());
    }
}

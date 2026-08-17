package dev.tushar.forgestack.platform.jobs;

/**
 * A job handed to a consumer, together with the handle needed to acknowledge it.
 *
 * <p>The delivery id belongs to the transport, not to the job: the same {@link JobMessage} redelivered
 * after a consumer died arrives under the same delivery id, while a genuine re-enqueue by the
 * reconciler arrives under a new one. Keeping them apart is what lets a consumer tell "I am seeing
 * this again" from "this was sent again".
 */
public record DeliveredJob(String deliveryId, JobMessage job) {}

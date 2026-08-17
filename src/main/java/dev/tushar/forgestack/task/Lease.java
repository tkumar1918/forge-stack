package dev.tushar.forgestack.task;

import java.time.Instant;
import java.util.UUID;

/**
 * A worker's claim on one task, for a while.
 *
 * <p>The epoch is the part that matters. It increases every time the claim changes hands, and every
 * write made under the claim is conditional on it, so a worker that stalled long enough to be
 * replaced — a long garbage collection, a paused container, a partition that healed — finds its
 * writes rejected rather than silently overwriting its successor's. Without that, a lease is a hint,
 * and "one worker per task" is a hope. This is the classic distributed-lock mistake (plan §21).
 *
 * <p>Carries its workspace because it is a capability, not a description: {@link LeaseScope} needs
 * both the tenant and the claim to open a transaction that may write, and a lease that could not
 * supply both would have to be paired with a workspace id at every call site — which is one more
 * thing to get wrong, and nothing would notice if it were the wrong one.
 *
 * @param expiresAt when this claim lapses, as Postgres reckoned it at acquisition. Not a deadline
 *     for the worker to enforce against its own clock: whether a lease has expired is decided by the
 *     same database that issued it, so no two hosts have to agree on the time.
 */
public record Lease(UUID taskId, UUID workspaceId, String owner, long epoch, Instant expiresAt) {}

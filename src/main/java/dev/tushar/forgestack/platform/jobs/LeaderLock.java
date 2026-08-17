package dev.tushar.forgestack.platform.jobs;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Which process gets to run the periodic work.
 *
 * <p>An optimisation, not a safety mechanism, and it is worth being clear about which. Everything
 * scheduled behind this lock is already safe to run twice: the reconciler claims rows with
 * {@code FOR UPDATE SKIP LOCKED} and bumps a fencing epoch, so a second scheduler racing it finds
 * nothing to do rather than doing it again. The lock exists so that N replicas cost one scan per
 * interval instead of N.
 *
 * <p>That ordering matters. A leader lock that safety depends on is a bug waiting for a network
 * partition — two nodes will eventually both believe they hold it, and the only real defence is
 * that being wrong about it changes nothing.
 */
@Component
public class LeaderLock {

    /**
     * Acquire-or-renew in one round trip.
     *
     * <p>Two statements — read the holder, then extend the TTL — leaves a window in which the lock
     * expires and someone else takes it between the read and the write, after which two processes
     * believe they are leader for a full period. A script is the only way to close it.
     */
    private static final RedisScript<Long> ACQUIRE_OR_RENEW = new DefaultRedisScript<>(
            """
            local holder = redis.call('GET', KEYS[1])
            if holder == false or holder == ARGV[1] then
                redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2])
                return 1
            end
            return 0
            """,
            Long.class);

    private static final String KEY_PREFIX = "forge:leader:";

    /**
     * Identifies this process, and only for the lifetime of this process.
     *
     * <p>Random rather than a hostname: two replicas can share a hostname, a restarted process must
     * not inherit its predecessor's claim, and neither mistake announces itself.
     */
    private final String processId = UUID.randomUUID().toString();

    private final StringRedisTemplate redis;

    LeaderLock(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * True if this process holds {@code role} for the next {@code ttl}.
     *
     * <p>Give the TTL a comfortable multiple of the tick interval. Too short and leadership flaps
     * every time a scan runs long; too long and a dead leader's work stalls until it lapses.
     */
    public boolean isLeaderFor(String role, Duration ttl) {
        Long acquired = redis.execute(
                ACQUIRE_OR_RENEW, List.of(KEY_PREFIX + role), processId, Long.toString(ttl.toMillis()));
        return Long.valueOf(1).equals(acquired);
    }

    /** This process's identity, as recorded in a lease's owner column. */
    public String processId() {
        return processId;
    }
}

package dev.tushar.forgestack.platform.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tushar.forgestack.support.AbstractIntegrationTest;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

/** The transport itself: what survives a round trip, and what happens when Redis forgets. */
class JobQueueTest extends AbstractIntegrationTest {

    @Autowired
    private JobQueue queue;

    @Autowired
    private StringRedisTemplate redis;

    private String kind;
    private UUID workspaceId;
    private UUID resourceId;

    @BeforeEach
    void freshStream() {
        // A stream nobody else is using. Every test class in this JVM shares one Redis, so a fixed
        // name would make these tests depend on the order they happened to run in.
        this.kind = "test-" + UUID.randomUUID().toString().substring(0, 8);
        this.workspaceId = UUID.randomUUID();
        this.resourceId = UUID.randomUUID();
    }

    @Test
    @DisplayName("a job comes back the same on the other side")
    void aJobSurvivesTheRoundTrip() {
        JobMessage sent = JobMessage.of(kind, workspaceId, resourceId);

        queue.enqueue(sent);
        List<DeliveredJob> delivered = queue.poll(kind, "worker-1", 10, Duration.ZERO);

        assertThat(delivered).hasSize(1);
        JobMessage received = delivered.getFirst().job();
        assertThat(received.id()).as("the idempotency key must survive; it is how a duplicate is recognised")
                .isEqualTo(sent.id());
        assertThat(received.workspaceId()).isEqualTo(workspaceId);
        assertThat(received.resourceId()).isEqualTo(resourceId);
        assertThat(received.enqueuedAt()).isEqualTo(sent.enqueuedAt());
    }

    @Test
    @DisplayName("an unacknowledged job is still owed to somebody")
    void anUnacknowledgedJobStaysOnTheStream() {
        queue.enqueue(JobMessage.of(kind, workspaceId, resourceId));
        queue.poll(kind, "worker-1", 10, Duration.ZERO);

        assertThat(queue.depth(kind))
                .as("reading is not finishing: a consumer that dies mid-job must leave a trace")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("acknowledging is what makes a job go away")
    void acknowledgingRemovesTheJob() {
        queue.enqueue(JobMessage.of(kind, workspaceId, resourceId));
        DeliveredJob delivered = queue.poll(kind, "worker-1", 10, Duration.ZERO).getFirst();

        queue.acknowledge(delivered);

        assertThat(queue.depth(kind)).isZero();
        assertThat(queue.poll(kind, "worker-1", 10, Duration.ZERO)).isEmpty();
    }

    /**
     * Redis is allowed to come back empty, and the queue has to keep working when it does.
     *
     * <p>A consumer group is Redis state, so it dies with the rest of it. Without recovery here the
     * first read after a flush fails with NOGROUP, the reconciler's carefully recovered work sits on
     * a stream nobody can read, and the outage outlives the outage.
     */
    @Test
    @DisplayName("a consumer group destroyed by a flush is rebuilt on the next read")
    void aFlushedGroupIsRebuilt() {
        queue.enqueue(JobMessage.of(kind, workspaceId, resourceId));
        queue.poll(kind, "worker-1", 10, Duration.ZERO);

        flushRedis();

        JobMessage afterFlush = JobMessage.of(kind, workspaceId, resourceId);
        queue.enqueue(afterFlush);

        List<DeliveredJob> delivered = queue.poll(kind, "worker-1", 10, Duration.ZERO);
        assertThat(delivered).hasSize(1);
        assertThat(delivered.getFirst().job().id()).isEqualTo(afterFlush.id());
    }

    private void flushRedis() {
        redis.execute((org.springframework.data.redis.core.RedisCallback<Void>) connection -> {
            connection.serverCommands().flushAll();
            return null;
        });
    }
}

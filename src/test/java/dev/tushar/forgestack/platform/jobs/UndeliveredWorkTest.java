package dev.tushar.forgestack.platform.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.tushar.forgestack.support.AbstractIntegrationTest;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * What happens to a job when Redis is not there to take it.
 *
 * <p>This is the failure the outbox exists for, and the only way to see it is to break the relay on
 * purpose. Everything else in this module can be green while this case silently drops work: the
 * publishing transaction commits, the relay throws into a thread nobody is watching, and the task
 * simply never runs. Nothing in the domain notices, because from the domain's side the state change
 * succeeded.
 *
 * <p>Uses its own Spring context so the real Redis-backed queue can be replaced with one that can be
 * told to fail.
 */
class UndeliveredWorkTest extends AbstractIntegrationTest {

    @TestConfiguration
    static class UnreliableTransport {

        @Bean
        @Primary
        UnreliableQueue unreliableQueue() {
            return new UnreliableQueue();
        }
    }

    /** A queue that can be switched off, which is the one thing a real broker will not do on cue. */
    static class UnreliableQueue implements JobQueue {

        private final AtomicBoolean reachable = new AtomicBoolean(true);
        private final List<JobMessage> accepted = new CopyOnWriteArrayList<>();

        @Override
        public void enqueue(JobMessage job) {
            if (!reachable.get()) {
                throw new IllegalStateException("Redis is unreachable");
            }
            accepted.add(job);
        }

        @Override
        public List<DeliveredJob> poll(String kind, String consumer, int count, Duration block) {
            return List.of();
        }

        @Override
        public void acknowledge(DeliveredJob delivered) {}

        @Override
        public long depth(String kind) {
            return accepted.size();
        }
    }

    @Autowired
    private UnreliableQueue queue;

    @Autowired
    private ApplicationEventPublisher events;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private IncompleteEventPublications incomplete;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("a job the relay could not deliver is still owed, and is delivered when Redis returns")
    void undeliveredWorkOutlivesTheOutage() {
        JobMessage job = JobMessage.of("task", UUID.randomUUID(), UUID.randomUUID());
        queue.reachable.set(false);

        transactions.executeWithoutResult(status -> events.publishEvent(new JobEnqueueRequested(job)));

        // The intent survived the failure. Without this row there is nothing left anywhere that
        // knows this job was meant to run.
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(outstanding(job))
                .as("the outbox row must remain, uncompleted")
                .isEqualTo(1));
        // Asserted against this job rather than an empty queue: resubmission is global, so anything
        // another test class left outstanding in the shared database arrives here too, and a test
        // that fails depending on what ran before it is worse than no test.
        assertThat(queue.accepted).doesNotContain(job);

        queue.reachable.set(true);
        incomplete.resubmitIncompletePublications(publication -> true);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(queue.accepted).contains(job);
            assertThat(outstanding(job))
                    .as("a delivered intent is finished with, and completion-mode=delete removes it")
                    .isZero();
        });
    }

    private int outstanding(JobMessage job) {
        Integer count = jdbc.queryForObject(
                """
                SELECT count(*) FROM event_publication
                 WHERE completion_date IS NULL AND serialized_event LIKE ?
                """,
                Integer.class,
                "%" + job.id() + "%");
        return count == null ? 0 : count;
    }
}

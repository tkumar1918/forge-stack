package dev.tushar.forgestack.platform.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.tushar.forgestack.support.AbstractIntegrationTest;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The rule that nothing reaches Redis except through a committed transaction.
 *
 * <p>Both halves matter and they fail in opposite directions. Enqueueing inside the transaction
 * would leave a job pointing at a row that was rolled away; enqueueing after commit without a
 * durable intent in between would lose the job whenever the process died in the gap.
 */
class OutboxTest extends AbstractIntegrationTest {

    @Autowired
    private ApplicationEventPublisher events;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JobQueue queue;

    private String kind;

    @BeforeEach
    void freshStream() {
        this.kind = "outbox-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    @DisplayName("an intent that commits reaches the queue")
    void aCommittedIntentIsRelayed() {
        JobMessage job = JobMessage.of(kind, UUID.randomUUID(), UUID.randomUUID());

        transactions.executeWithoutResult(status -> events.publishEvent(new JobEnqueueRequested(job)));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(queue.depth(kind))
                .isEqualTo(1));
    }

    @Test
    @DisplayName("an intent that rolls back queues nothing")
    void aRolledBackIntentIsNeverRelayed() {
        JobMessage job = JobMessage.of(kind, UUID.randomUUID(), UUID.randomUUID());

        transactions.executeWithoutResult(status -> {
            events.publishEvent(new JobEnqueueRequested(job));
            status.setRollbackOnly();
        });

        // Deliberately waits before asserting rather than checking immediately. The relay is
        // asynchronous, so an implementation that wrongly enqueues would pass an instant assertion
        // simply by not having got there yet — the bug this test exists to catch is a race.
        await().pollDelay(Duration.ofSeconds(1))
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(queue.depth(kind)).isZero());
    }
}

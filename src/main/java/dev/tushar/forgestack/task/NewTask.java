package dev.tushar.forgestack.task;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * What a caller has to say to ask for work.
 *
 * @param idempotencyKey optional, and the thing that makes a retried create safe. A client that
 *     times out and retries must get the original task back rather than a second one, because two
 *     agents on one goal produce two branches and two pull requests (plan §21).
 * @param simulatedOutcome Phase 2 scaffolding. Tells the fake handler how the attempt should turn
 *     out, so the whole lifecycle can be driven without a model. Goes away with the fake.
 */
public record NewTask(
        @NotBlank @Size(max = 200) String title,
        @NotBlank @Size(max = 4000) String goal,
        @Size(max = 4000) String acceptanceCriteria,
        UUID managedRepositoryId,
        @Positive Integer maxAttempts,
        @Size(max = 200) String idempotencyKey,
        SimulatedOutcome simulatedOutcome) {}

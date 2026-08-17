package dev.tushar.forgestack.task;

import java.util.UUID;

/**
 * Everything the guards are allowed to look at, read once inside the locked transaction.
 *
 * <p>Read once and passed around, so every guard evaluating one transition sees the same world. A
 * guard that went back to the database for itself could disagree with the guard beside it about
 * whether an attempt is still running, and a decision assembled from two different moments is a
 * decision about neither.
 *
 * <p>Being a plain record is also what makes guards testable without a database: they are pure
 * functions of this, and the interesting cases are combinations no fixture would set up by accident.
 *
 * @param latestAttemptOutcome how the most recent <em>finished</em> attempt ended, or null when none
 *     has. Finished ones only, so that "the last thing that ran succeeded" and "nothing is running
 *     now" stay two separate questions with two separate guards. Folding them together would mean an
 *     in-flight attempt failed both checks, and a broken guard could then hide behind the other one —
 *     which is precisely how a guard stops guarding without anybody noticing.
 */
public record TaskFacts(
        UUID taskId,
        TaskState state,
        int attemptCount,
        int maxAttempts,
        Long budgetUsdMicros,
        long consumedUsdMicros,
        Long budgetTokens,
        long consumedTokens,
        String latestAttemptOutcome,
        boolean attemptInFlight) {

    /** True when no budget was set, or when neither meter has passed the one that was. */
    public boolean withinBudget() {
        boolean money = budgetUsdMicros == null || consumedUsdMicros <= budgetUsdMicros;
        boolean tokens = budgetTokens == null || consumedTokens <= budgetTokens;
        return money && tokens;
    }
}

package dev.tushar.forgestack.tools;

import java.time.Duration;
import java.util.List;

/**
 * One tool, as data.
 *
 * <p>§15 says "the catalogue is data" and means it: what is offered to a model is composed per
 * attempt, capped, frozen for the attempt's lifetime, and recorded on the attempt row so the audit
 * trail is stable even if the catalogue changes underneath a replay. A definition therefore has to be
 * something that can be written down and compared, not behaviour hidden behind a name.
 *
 * @param name          what the model calls it. Also the key it is resolved by, so it is closed
 *                      vocabulary rather than free text
 * @param description   what the model is told it does
 * @param requiredArguments argument names that must be present. Absent ones are a refusal, never a
 *                      default — §15 says reject on validation failure and explicitly does not coerce,
 *                      because a coerced argument is a model's mistake being hidden from the record
 * @param risk          ForgeStack's rating, never the model's ({@link RiskLevel})
 * @param sideEffect    what it changes ({@link SideEffect})
 * @param idempotent    whether running it twice is the same as running it once. Read by the dedupe
 *                      step, which does not exist yet
 * @param timeout       the ceiling for one call
 */
public record ToolDefinition(
        String name,
        String description,
        List<String> requiredArguments,
        RiskLevel risk,
        SideEffect sideEffect,
        boolean idempotent,
        Duration timeout) {

    public ToolDefinition {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("a tool needs a name");
        }
        requiredArguments = List.copyOf(requiredArguments);
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("a tool without a timeout is a tool that never returns");
        }
    }
}

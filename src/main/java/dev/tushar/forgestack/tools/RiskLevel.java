package dev.tushar.forgestack.tools;

/**
 * How much damage a call could do, as ForgeStack rates it.
 *
 * <p><strong>Never as the model rates it.</strong> §17 allows a model to raise risk and never to
 * lower it, because an argument for lowering risk is precisely what a successful prompt injection
 * produces. The value here is computed from the tool and its arguments; a model's own claim arrives
 * separately as {@code HarnessEvent.ToolCallRequested.declaredRisk}, and the two are combined by
 * taking the higher.
 */
public enum RiskLevel {
    LOW,
    MEDIUM,
    HIGH;

    /** The higher of the two, which is the only way these are ever combined. */
    public RiskLevel raisedTo(RiskLevel other) {
        return compareTo(other) >= 0 ? this : other;
    }
}

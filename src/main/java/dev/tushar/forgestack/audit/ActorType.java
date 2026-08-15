package dev.tushar.forgestack.audit;

/**
 * Who performed an audited action.
 *
 * <p>Model roles are named separately from {@code SYSTEM} on purpose. "The AI did it" is not an
 * answer anyone can act on; "the executor did it during attempt 3" is. Attribution at this
 * granularity is what makes the eventual question — why did ForgeStack do this? — answerable.
 *
 * <p>Mirrors the {@code audit_events_actor_type_ck} constraint; adding a value here means adding it
 * to the migration too, or the insert is rejected.
 */
public enum ActorType {
    HUMAN,
    SYSTEM,
    SCHEDULER,
    SUPERVISOR,
    EXECUTOR
}

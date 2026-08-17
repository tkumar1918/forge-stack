package dev.tushar.forgestack.task;

import java.util.UUID;

/**
 * Who asked for a transition.
 *
 * <p>Recorded on every transition row, and the reason the audit log can answer "why did Forge do
 * this?" rather than only "what happened". Attributing an action to a <em>model role</em> — the
 * supervisor rather than the executor — is most of that value: "the AI did it" is not an answer
 * anybody can act on.
 *
 * @param id the person, when there is one. Null for everything the system did to itself.
 */
public record Actor(Kind kind, UUID id) {

    /** Matches {@code task_transitions_actor_ck}. */
    public enum Kind {
        /** The runtime acting on its own behalf. */
        SYSTEM,
        /** A periodic sweep — reconciliation, reaping, admission. */
        SCHEDULER,
        /** The model role that reviews an attempt from outside it. */
        SUPERVISOR,
        /** A person. */
        HUMAN
    }

    public Actor {
        if (kind == null) {
            throw new IllegalArgumentException("every transition has an actor");
        }
        if (kind == Kind.HUMAN && id == null) {
            throw new IllegalArgumentException("a human actor without an id is an unattributed action");
        }
    }

    public static Actor system() {
        return new Actor(Kind.SYSTEM, null);
    }

    public static Actor scheduler() {
        return new Actor(Kind.SCHEDULER, null);
    }

    public static Actor supervisor() {
        return new Actor(Kind.SUPERVISOR, null);
    }

    public static Actor human(UUID userId) {
        return new Actor(Kind.HUMAN, userId);
    }
}

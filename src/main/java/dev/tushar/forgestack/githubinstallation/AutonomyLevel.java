package dev.tushar.forgestack.githubinstallation;

/**
 * The ceiling a human sets on what the agent may do in one repository.
 *
 * <p>Ordered from least to most capable. Whatever the agent decides it needs, this is the limit,
 * and it is set by a person rather than inferred.
 *
 * <p><strong>There is deliberately no {@code MERGE_AUTONOMOUS}.</strong> Merging its own work is
 * the one capability an agent should not have until there is real data showing its pull requests
 * are good — and the blast radius of getting that wrong is production. Leaving the value out
 * entirely, rather than defining it and refusing to honour it, means nobody can enable it by
 * editing a config row.
 */
public enum AutonomyLevel {
    /** Watch and report. No changes of any kind. */
    OBSERVE_ONLY,

    /** Propose changes as comments; write nothing. */
    SUGGEST,

    /** Open pull requests, but a human approves before the work begins. The default. */
    PR_WITH_APPROVAL,

    /** Open pull requests unprompted. A human still reviews and merges. */
    PR_AUTONOMOUS
}

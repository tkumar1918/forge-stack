package dev.tushar.forgestack.task;

import java.util.function.Predicate;

/**
 * The mechanical preconditions a transition must satisfy.
 *
 * <p>Every guard is a function of {@link TaskFacts} — of committed rows, never of prose. That is the
 * whole point of §10.3: the model's most powerful move is to <em>ask</em> for {@code COMPLETE}, and
 * what decides the answer is a set of checks it cannot argue with. A guard that read a rationale
 * would be a guard the model could talk its way past.
 *
 * <p>An enum, deliberately, and closed for the same reason the transition table is. Guards that could
 * be contributed, discovered, or configured would make "what does completion require?" a question
 * with no single answer — and it is the question the entire product rests on.
 *
 * <h2>Guards that cannot be enforced yet</h2>
 *
 * <p>Four of §10.3's completion preconditions read data that does not exist: there is no
 * {@code evidence} table, no {@code human_interventions}, no policy engine, and no record of a merge
 * or an acceptance. Diff guards used to be a fifth and are now enforced. They are listed here anyway, marked {@link Enforcement#PENDING},
 * for one reason: a guard list that quietly contained three checks and looked like eight would be
 * believed. Instead every transition writes into {@code task_state_transitions.guard_results} exactly
 * which guards ran and which did not, so a task completed today carries a permanent record that it
 * was completed under a weaker rule than the one this system intends to enforce.
 *
 * <p>They pass rather than block, because blocking every completion would make the phases that build
 * the missing data impossible to build. That is a real hole and it is meant to be uncomfortable —
 * {@code CompletionGuardsTest} pins the pending set so shrinking it is a deliberate edit and growing
 * it is a conversation.
 */
public enum TaskGuard {

    /**
     * No attempt is still running.
     *
     * <p>Not in §10.3's list, and it belongs there. Completing a task while an attempt is in flight
     * leaves a worker writing to a finished task, holding a sandbox nobody will reap, against a
     * branch nobody will merge.
     */
    NO_ATTEMPT_IN_FLIGHT(facts -> !facts.attemptInFlight()),

    /** The most recent attempt actually succeeded. */
    LATEST_ATTEMPT_SUCCEEDED(facts -> "SUCCEEDED".equals(facts.latestAttemptOutcome())),

    /**
     * The task did not run past its budget.
     *
     * <p>Guards completion as well as admission, so a task cut off mid-verification cannot then be
     * declared complete — the cheapest possible way to "finish" work is to stop paying for it before
     * anyone checks it.
     */
    WITHIN_BUDGET(TaskFacts::withinBudget),

    /** Every attempt has been spent. The precondition for giving up rather than failing. */
    ATTEMPT_CAP_REACHED(facts -> facts.attemptCount() >= facts.maxAttempts()),

    /** Needs {@code evidence}: the verification contract ran and passed on the final head SHA. */
    VERIFICATION_PASSED(Enforcement.PENDING, "needs the evidence table and a verification contract runner"),

    /**
     * No test was deleted, disabled, or weakened to make the diff pass (§17).
     *
     * <p>Requires an explicit pass, not merely the absence of a refusal. An attempt that never
     * reached verification has no verdict, and "was never checked" must not be a way to complete —
     * that is precisely the shape of the thing this guard exists to catch.
     */
    DIFF_GUARDS_PASSED(facts -> "PASSED".equals(facts.latestAttemptDiffGuardVerdict())),

    /** Needs {@code human_interventions}: nothing is still waiting on a person. */
    NO_OPEN_HUMAN_INTERVENTION(Enforcement.PENDING, "needs the human_interventions table"),

    /** Needs {@code policy}: the authority this task required never exceeded the authority it had. */
    AUTHORITY_SUFFICIENT(Enforcement.PENDING, "needs the policy module and effective-authority resolution"),

    /**
     * Needs PR tracking: a merge happened, or a person said yes.
     *
     * <p>The one that stops "opened a pull request" from counting as done. An autonomous maintainer
     * measured on PRs opened optimises for PRs opened, which is the failure this product exists to
     * avoid.
     */
    ACCEPTED_BY_HUMAN_OR_MERGED(Enforcement.PENDING, "needs pull-request state and an acceptance record");

    /** Whether a guard is deciding anything today. */
    public enum Enforcement {
        ENFORCED,
        PENDING
    }

    /** What a guard concluded, recorded on the transition row. */
    public enum Outcome {
        PASSED,
        REFUSED,
        /** Declared, and not enforced: the data it reads does not exist yet. */
        NOT_ENFORCED
    }

    private final Enforcement enforcement;
    private final Predicate<TaskFacts> satisfied;
    private final String pendingOn;

    TaskGuard(Predicate<TaskFacts> satisfied) {
        this.enforcement = Enforcement.ENFORCED;
        this.satisfied = satisfied;
        this.pendingOn = null;
    }

    TaskGuard(Enforcement enforcement, String pendingOn) {
        this.enforcement = enforcement;
        this.satisfied = facts -> true;
        this.pendingOn = pendingOn;
    }

    public Enforcement enforcement() {
        return enforcement;
    }

    /** What this guard is waiting for, when it is not enforcing anything. */
    public String pendingOn() {
        return pendingOn;
    }

    Outcome evaluate(TaskFacts facts) {
        if (enforcement == Enforcement.PENDING) {
            return Outcome.NOT_ENFORCED;
        }
        return satisfied.test(facts) ? Outcome.PASSED : Outcome.REFUSED;
    }
}

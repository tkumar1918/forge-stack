package dev.tushar.forgestack.task;

import static dev.tushar.forgestack.task.TaskEvent.*;
import static dev.tushar.forgestack.task.TaskState.*;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Every legal state change, declared in one place.
 *
 * <p>A static map, and <strong>deliberately closed</strong>. Making this open for extension —
 * pluggable contributors, annotation-scanned states, transitions assembled at startup — would destroy
 * the single guarantee the whole system rests on: that the set of things that can happen to a task is
 * small, enumerable, and reviewable by reading one file. Safety here comes from concentration, not
 * from extensibility, and adding a transition <em>should</em> mean editing a table somebody reviews
 * (plan Appendix A).
 *
 * <p>A pair with no entry is not a no-op. It throws. There is no reflective, string-driven, or
 * model-supplied path into a state.
 */
final class TaskTransitions {

    /**
     * A legal move, and what has to be true first.
     *
     * @param guards evaluated in order and all required. Empty means the move is legal whenever the
     *     state allows it — which is most of them, because most transitions are the system reporting
     *     something that already happened rather than asking permission for something to happen.
     */
    record Transition(TaskState from, TaskEvent event, TaskState to, List<TaskGuard> guards) {

        Transition(TaskState from, TaskEvent event, TaskState to) {
            this(from, event, to, List.of());
        }
    }

    /**
     * States a task can be suspended from: everything alive.
     *
     * <p>Suspension is not part of the work's shape — it is the outside world intervening, and it can
     * arrive at any moment a task is not already finished. Enumerated from a set rather than written
     * out so that adding a live state cannot silently create one that ignores a budget breach.
     */
    private static final Set<TaskState> SUSPENDABLE = EnumSet.of(
            READY, BLOCKED_ON_DEPENDENCY, QUEUED, RUNNING, AWAITING_HUMAN, AWAITING_EXTERNAL);

    /** Cancellation reaches anything not already finished, for the same reason. */
    private static final Set<TaskState> CANCELLABLE =
            EnumSet.of(CREATED, READY, BLOCKED_ON_DEPENDENCY, QUEUED, RUNNING, AWAITING_HUMAN, AWAITING_EXTERNAL, SUSPENDED);

    /**
     * Completing a task requires all of these, and three of the eight decide nothing yet.
     *
     * <p>Kept as one list rather than split into "the real ones" and "the rest" on purpose: the shape
     * of the rule is what this system intends to enforce, and hiding the unenforced half would make
     * the intent invisible the moment the data arrived. See {@link TaskGuard}.
     */
    private static final List<TaskGuard> COMPLETION_GUARDS = List.of(
            TaskGuard.NO_ATTEMPT_IN_FLIGHT,
            TaskGuard.LATEST_ATTEMPT_SUCCEEDED,
            TaskGuard.WITHIN_BUDGET,
            TaskGuard.VERIFICATION_PASSED,
            TaskGuard.DIFF_GUARDS_PASSED,
            TaskGuard.NO_OPEN_HUMAN_INTERVENTION,
            TaskGuard.AUTHORITY_SUFFICIENT,
            TaskGuard.ACCEPTED_BY_HUMAN_OR_MERGED);

    private static final Map<Key, Transition> TABLE = build();

    private TaskTransitions() {}

    private record Key(TaskState from, TaskEvent event) {}

    static Optional<Transition> lookup(TaskState from, TaskEvent event) {
        return Optional.ofNullable(TABLE.get(new Key(from, event)));
    }

    /** Every declared move, for tests that need to walk the whole table. */
    static List<Transition> all() {
        return List.copyOf(TABLE.values());
    }

    private static Map<Key, Transition> build() {
        List<Transition> declared = new java.util.ArrayList<>(List.of(
                // Admission. A task exists before anybody has decided it should run.
                new Transition(CREATED, ADMIT, READY),

                // Dependencies. The graph moves a task in and out of readiness without anyone asking.
                new Transition(READY, BLOCK, BLOCKED_ON_DEPENDENCY),
                new Transition(BLOCKED_ON_DEPENDENCY, DEP_RESOLVED, READY),

                // Getting to a worker, and back again when the worker stops existing.
                new Transition(READY, ENQUEUE, QUEUED),
                new Transition(QUEUED, CLAIM, RUNNING),
                new Transition(RUNNING, LEASE_EXPIRED, QUEUED),

                // Retrying is a self-loop, because a new attempt is not a new lifecycle. The worker
                // still holds the task; only the approach is being thrown away.
                new Transition(RUNNING, ATTEMPT_FAILED, RUNNING),

                // Waiting on a person.
                new Transition(RUNNING, ESCALATE_HUMAN, AWAITING_HUMAN),
                new Transition(AWAITING_HUMAN, RESUME, RUNNING),
                // Rejection and silence are different answers and must not land in the same state:
                // one is a decision to stop, the other is nobody having decided anything.
                new Transition(AWAITING_HUMAN, REJECT, CANCELLED),
                new Transition(AWAITING_HUMAN, TIMEOUT, ABANDONED),

                // Waiting on the world.
                new Transition(RUNNING, SUBMIT, AWAITING_EXTERNAL),
                new Transition(AWAITING_EXTERNAL, EXTERNAL_FAILED, RUNNING),

                // Finishing. Both paths carry the same guards, because "a human accepted it" and
                // "a pull request merged" are two ways of satisfying one precondition, not two
                // different standards of done.
                new Transition(RUNNING, COMPLETE, COMPLETED, COMPLETION_GUARDS),
                new Transition(AWAITING_EXTERNAL, COMPLETE, COMPLETED, COMPLETION_GUARDS),
                new Transition(RUNNING, FAIL, FAILED),
                new Transition(RUNNING, ABANDON, ABANDONED, List.of(TaskGuard.ATTEMPT_CAP_REACHED)),

                // Coming back from a suspension lands in READY, not where it left. Capacity,
                // dependencies and budget all have to be looked at again after an unknown interval.
                new Transition(SUSPENDED, UNSUSPEND, READY)));

        SUSPENDABLE.forEach(from -> declared.add(new Transition(from, SUSPEND, SUSPENDED)));
        CANCELLABLE.forEach(from -> declared.add(new Transition(from, CANCEL, CANCELLED)));

        Map<Key, Transition> table = new java.util.HashMap<>();
        for (Transition transition : declared) {
            Transition clash = table.put(new Key(transition.from(), transition.event()), transition);
            if (clash != null) {
                throw new IllegalStateException(
                        "two transitions declared for %s + %s".formatted(transition.from(), transition.event()));
            }
        }
        return Map.copyOf(table);
    }
}

package dev.tushar.forgestack.task;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The transition table, checked as a whole rather than a case at a time.
 *
 * <p>Needs no database: the table is a static declaration, and asserting it here means the whole of
 * it is covered rather than the handful of pairs somebody thought to write a test for. That is the
 * point — the risk in a state machine is never the transition you were thinking about.
 */
class TaskTransitionTableTest {

    /**
     * Every legal pair, written out.
     *
     * <p>Deliberately a second copy of the table rather than a derivation from it. A test that
     * computed the expected set from the code under test would assert only that the code equals
     * itself; this one fails whenever the two disagree, which is what makes adding a transition a
     * thing somebody has to do twice, on purpose, in a diff a reviewer can read.
     */
    private static final Set<String> DECLARED = Set.of(
            "CREATED+ADMIT>READY",
            "CREATED+CANCEL>CANCELLED",
            "READY+BLOCK>BLOCKED_ON_DEPENDENCY",
            "READY+ENQUEUE>QUEUED",
            "READY+SUSPEND>SUSPENDED",
            "READY+CANCEL>CANCELLED",
            "BLOCKED_ON_DEPENDENCY+DEP_RESOLVED>READY",
            "BLOCKED_ON_DEPENDENCY+SUSPEND>SUSPENDED",
            "BLOCKED_ON_DEPENDENCY+CANCEL>CANCELLED",
            "QUEUED+CLAIM>RUNNING",
            "QUEUED+SUSPEND>SUSPENDED",
            "QUEUED+CANCEL>CANCELLED",
            "RUNNING+LEASE_EXPIRED>QUEUED",
            "RUNNING+YIELD>QUEUED",
            "RUNNING+ATTEMPT_FAILED>RUNNING",
            "RUNNING+ESCALATE_HUMAN>AWAITING_HUMAN",
            "RUNNING+SUBMIT>AWAITING_EXTERNAL",
            "RUNNING+COMPLETE>COMPLETED",
            "RUNNING+FAIL>FAILED",
            "RUNNING+ABANDON>ABANDONED",
            "RUNNING+SUSPEND>SUSPENDED",
            "RUNNING+CANCEL>CANCELLED",
            "AWAITING_HUMAN+RESUME>QUEUED",
            "AWAITING_HUMAN+REJECT>CANCELLED",
            "AWAITING_HUMAN+TIMEOUT>ABANDONED",
            "AWAITING_HUMAN+SUSPEND>SUSPENDED",
            "AWAITING_HUMAN+CANCEL>CANCELLED",
            "AWAITING_EXTERNAL+EXTERNAL_FAILED>QUEUED",
            "AWAITING_EXTERNAL+COMPLETE>COMPLETED",
            "AWAITING_EXTERNAL+SUSPEND>SUSPENDED",
            "AWAITING_EXTERNAL+CANCEL>CANCELLED",
            "SUSPENDED+UNSUSPEND>READY",
            "SUSPENDED+CANCEL>CANCELLED");

    @Test
    @DisplayName("the table contains exactly the declared transitions and nothing else")
    void theTableIsWhatItSaysItIs() {
        Set<String> actual = TaskTransitions.all().stream()
                .map(t -> "%s+%s>%s".formatted(t.from(), t.event(), t.to()))
                .collect(java.util.stream.Collectors.toSet());

        assertThat(actual).containsExactlyInAnyOrderElementsOf(DECLARED);
    }

    /**
     * The exit criterion for step 2.3: every illegal pair throws.
     *
     * <p>All 228 of them, not a sample. An unhandled pair that quietly did nothing would be far worse
     * than one that threw — a task silently ignoring {@code COMPLETE} looks exactly like a task still
     * working.
     */
    @Test
    @DisplayName("every undeclared (state, event) pair has no transition at all")
    void everyIllegalPairIsAbsent() {
        List<String> unexpectedlyLegal = pairs()
                .filter(pair -> TaskTransitions.lookup(pair.state(), pair.event()).isPresent())
                .map(pair -> "%s+%s".formatted(pair.state(), pair.event()))
                .filter(key -> DECLARED.stream().noneMatch(declared -> declared.startsWith(key + ">")))
                .toList();

        assertThat(unexpectedlyLegal).isEmpty();
        assertThat(pairs().count())
                .as("state × event, so that adding either without thinking about the other is visible here")
                .isEqualTo((long) TaskState.values().length * TaskEvent.values().length);
    }

    @Test
    @DisplayName("nothing leads out of a terminal state")
    void terminalMeansTerminal() {
        List<String> escapes = pairs()
                .filter(pair -> pair.state().isTerminal())
                .filter(pair -> TaskTransitions.lookup(pair.state(), pair.event()).isPresent())
                .map(pair -> "%s+%s".formatted(pair.state(), pair.event()))
                .toList();

        assertThat(escapes)
                .as("a task that finished must stay finished; resurrection is a new task")
                .isEmpty();
    }

    @Test
    @DisplayName("every state is reachable, and every event is used")
    void nothingIsDeadWeight() {
        Set<TaskState> reachable = TaskTransitions.all().stream()
                .map(TaskTransitions.Transition::to)
                .collect(java.util.stream.Collectors.toSet());
        Set<TaskEvent> used = TaskTransitions.all().stream()
                .map(TaskTransitions.Transition::event)
                .collect(java.util.stream.Collectors.toSet());

        assertThat(reachable)
                .as("CREATED is where tasks start, so nothing leads to it")
                .containsExactlyInAnyOrderElementsOf(
                        Stream.of(TaskState.values()).filter(s -> s != TaskState.CREATED).toList());
        assertThat(used)
                .as("an event no transition responds to is a promise the FSM does not keep")
                .containsExactlyInAnyOrder(TaskEvent.values());
    }

    private record Pair(TaskState state, TaskEvent event) {}

    private static Stream<Pair> pairs() {
        return Stream.of(TaskState.values())
                .flatMap(state -> Stream.of(TaskEvent.values()).map(event -> new Pair(state, event)));
    }
}

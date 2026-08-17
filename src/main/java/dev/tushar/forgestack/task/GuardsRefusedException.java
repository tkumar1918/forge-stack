package dev.tushar.forgestack.task;

import java.util.List;
import java.util.Map;

/**
 * The transition was legal and its preconditions were not met.
 *
 * <p>Carries every guard's verdict rather than only the first failure, because "why was COMPLETE
 * refused" is asked by somebody looking at a stuck task, and answering it one guard at a time turns
 * one question into five.
 */
public class GuardsRefusedException extends RuntimeException {

    private final transient Map<TaskGuard, TaskGuard.Outcome> results;

    GuardsRefusedException(TaskState from, TaskEvent event, Map<TaskGuard, TaskGuard.Outcome> results) {
        super("%s refused from %s by %s".formatted(event, from, refusedIn(results)));
        this.results = Map.copyOf(results);
    }

    /** Every guard that ran, and what it concluded. */
    public Map<TaskGuard, TaskGuard.Outcome> results() {
        return results;
    }

    public List<TaskGuard> refused() {
        return refusedIn(results);
    }

    private static List<TaskGuard> refusedIn(Map<TaskGuard, TaskGuard.Outcome> results) {
        return results.entrySet().stream()
                .filter(entry -> entry.getValue() == TaskGuard.Outcome.REFUSED)
                .map(Map.Entry::getKey)
                .toList();
    }
}

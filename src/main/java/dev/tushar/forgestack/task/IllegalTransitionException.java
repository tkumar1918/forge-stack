package dev.tushar.forgestack.task;

/**
 * The event asked for is not something that can happen from where the task is.
 *
 * <p>Distinct from {@link GuardsRefusedException} and the distinction is the useful part: this means
 * the request made no sense, while a refusal means it made sense and the preconditions were not met.
 * The first is a bug in whatever asked; the second is the system working.
 */
public class IllegalTransitionException extends RuntimeException {

    private final TaskState from;
    private final TaskEvent event;

    IllegalTransitionException(TaskState from, TaskEvent event) {
        super("no transition is declared for %s + %s".formatted(from, event));
        this.from = from;
        this.event = event;
    }

    public TaskState from() {
        return from;
    }

    public TaskEvent event() {
        return event;
    }
}

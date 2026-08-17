package dev.tushar.forgestack.api.task;

import dev.tushar.forgestack.githublogin.ForgeStackPrincipal;
import dev.tushar.forgestack.task.Actor;
import dev.tushar.forgestack.task.GuardsRefusedException;
import dev.tushar.forgestack.task.IllegalTransitionException;
import dev.tushar.forgestack.task.NewTask;
import dev.tushar.forgestack.task.TaskEvent;
import dev.tushar.forgestack.task.TaskService;
import dev.tushar.forgestack.task.TaskStateService;
import dev.tushar.forgestack.task.TaskView;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Asking for work, watching it happen, and the two things a person can say about it.
 *
 * <p>Deliberately thin. Everything here either creates a row or names an event for the state
 * machine — there is no endpoint that sets a state, and none that could. A caller cannot ask for
 * {@code COMPLETED}; it can only report something that happened, and the FSM decides what that
 * means.
 */
@RestController
@RequestMapping("/api/tasks")
class TaskController {

    private final TaskService tasks;
    private final TaskStateService taskStates;

    TaskController(TaskService tasks, TaskStateService taskStates) {
        this.tasks = tasks;
        this.taskStates = taskStates;
    }

    /** Creates a task, admits it, and queues it. 202, because the work has not happened yet. */
    @PostMapping
    ResponseEntity<TaskView> create(
            @Valid @RequestBody NewTask request, @AuthenticationPrincipal ForgeStackPrincipal principal) {

        TaskView created = tasks.create(principal.activeWorkspaceId(), principal.userId(), request);
        return ResponseEntity.accepted().body(created);
    }

    @GetMapping
    List<TaskView> list(@AuthenticationPrincipal ForgeStackPrincipal principal) {
        return tasks.list(principal.activeWorkspaceId());
    }

    /**
     * One task with every attempt and every transition.
     *
     * <p>The replay view. Because transitions, attempts and steps are append-only and ordered, this
     * reconstructs exactly how a task reached where it is — including which guards agreed to let it.
     */
    @GetMapping("/{taskId}")
    ResponseEntity<TaskView.Detail> detail(
            @PathVariable UUID taskId, @AuthenticationPrincipal ForgeStackPrincipal principal) {

        return tasks.detail(principal.activeWorkspaceId(), taskId)
                .map(ResponseEntity::ok)
                // 404 rather than 403: a task in another workspace is invisible here, and "forbidden"
                // would confirm that the id exists somewhere.
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Stops a task. Legal from anywhere that is not already finished. */
    @PostMapping("/{taskId}/cancel")
    ResponseEntity<TaskView> cancel(
            @PathVariable UUID taskId,
            @RequestBody(required = false) Reason reason,
            @AuthenticationPrincipal ForgeStackPrincipal principal) {

        return applyHumanEvent(principal, taskId, TaskEvent.CANCEL, reasonOr(reason, "cancelled by a person"));
    }

    /**
     * Answering a task that asked for a person.
     *
     * <p>Two answers, and they do not lead to the same place: continuing puts the work back on the
     * queue, refusing ends the task. Nobody answering at all is a third outcome the scheduler will
     * eventually raise as {@code TIMEOUT}, and it is deliberately not something this endpoint can
     * express.
     */
    @PostMapping("/{taskId}/answer")
    ResponseEntity<TaskView> answer(
            @PathVariable UUID taskId,
            @Valid @RequestBody Answer answer,
            @AuthenticationPrincipal ForgeStackPrincipal principal) {

        TaskEvent event = answer.resume() ? TaskEvent.RESUME : TaskEvent.REJECT;
        return applyHumanEvent(principal, taskId, event, reasonOr(answer.reason(), "answered by a person"));
    }

    /** What a person said, and why. */
    record Answer(boolean resume, String reason) {}

    record Reason(String reason) {}

    // ---------------------------------------------------------------------------------------

    private ResponseEntity<TaskView> applyHumanEvent(
            ForgeStackPrincipal principal, UUID taskId, TaskEvent event, String reason) {

        UUID workspaceId = principal.activeWorkspaceId();
        if (tasks.find(workspaceId, taskId).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        taskStates.apply(workspaceId, taskId, event, Actor.human(principal.userId()), reason);
        return tasks.find(workspaceId, taskId).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound()
                .build());
    }

    private static String reasonOr(Reason reason, String fallback) {
        return reason == null || reason.reason() == null || reason.reason().isBlank() ? fallback : reason.reason();
    }

    private static String reasonOr(String reason, String fallback) {
        return reason == null || reason.isBlank() ? fallback : reason;
    }

    /**
     * A legal request the task's current state has no answer to.
     *
     * <p>409 rather than 400: the request was well formed and would have been fine a moment earlier
     * or later. Answering "bad request" would send the caller looking for a mistake in its own
     * payload.
     */
    @ExceptionHandler(IllegalTransitionException.class)
    ResponseEntity<Map<String, Object>> illegalTransition(IllegalTransitionException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of(
                        "error", "illegal_transition",
                        "message", "a task in %s cannot handle %s".formatted(e.from(), e.event()),
                        "state", e.from().name()));
    }

    /** Legal, and refused. The response names every guard, because "why not" is the whole question. */
    @ExceptionHandler(GuardsRefusedException.class)
    ResponseEntity<Map<String, Object>> refused(GuardsRefusedException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of(
                        "error", "guards_refused",
                        "refused", e.refused().stream().map(Enum::name).toList(),
                        "results",
                                e.results().entrySet().stream()
                                        .collect(java.util.stream.Collectors.toMap(
                                                entry -> entry.getKey().name(),
                                                entry -> entry.getValue().name()))));
    }
}

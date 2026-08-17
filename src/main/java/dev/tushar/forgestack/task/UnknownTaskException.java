package dev.tushar.forgestack.task;

import java.util.UUID;

/**
 * No such task — in this workspace.
 *
 * <p>The qualifier matters and the message keeps it. Row-level security makes another tenant's task
 * indistinguishable from one that does not exist, which is the intended behaviour and a confusing
 * thing to debug: the row is right there in the table and the query returns nothing.
 */
public class UnknownTaskException extends RuntimeException {

    UnknownTaskException(UUID workspaceId, UUID taskId) {
        super("no task %s in workspace %s — it may belong to another tenant, which looks identical from here"
                .formatted(taskId, workspaceId));
    }
}

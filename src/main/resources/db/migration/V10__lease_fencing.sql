-- The database refuses to let one worker write over another's task.
--
-- Fencing already worked, for the three statements that remembered to ask for it: TaskLeases puts
-- `AND lease_epoch = ?` in its own WHERE clauses, so a superseded worker's renew and release do
-- nothing. That is enforcement by discipline, and the discipline is invisible — the fourth statement
-- someone writes against `tasks` will not fail, will not warn, and will not be obviously wrong in
-- review. It will simply mean that two workers can both write to a task they both believe they hold.
--
-- So the rule moves to where it cannot be skipped, on the same terms as row-level security: the
-- session must carry the claim it is writing under, and a statement that carries nothing is refused
-- by Postgres rather than by whoever remembered. `app.workspace_id` proves which tenant you are;
-- `app.lease_task` and `app.lease_epoch` prove which claim you hold.
--
-- Two GUCs rather than one, deliberately. An epoch alone would let a scope opened for task A
-- authorise a write to task B that happens to sit at the same epoch — epochs are per-row counters
-- and small integers collide constantly.

CREATE FUNCTION reject_unfenced_task_write() RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    carried_task  text := nullif(current_setting('app.lease_task', true), '');
    carried_epoch text := nullif(current_setting('app.lease_epoch', true), '');
BEGIN
    -- Only a *live* claim is protected.
    --
    -- A lapsed one is precisely what the reconciler exists to take back, and it cannot carry an
    -- epoch because the whole point is that the holder is gone. Making expiry the boundary means the
    -- escape hatch and the recovery path are the same thing, so there is no bypass to add later and
    -- no bypass to reach for by mistake.
    IF OLD.lease_owner IS NULL
        OR OLD.lease_expires_at IS NULL
        OR OLD.lease_expires_at <= now() THEN
        RETURN NEW;
    END IF;

    IF carried_task IS NULL OR carried_epoch IS NULL THEN
        RAISE EXCEPTION 'task % is held by % at epoch %, and this write carried no lease',
            OLD.id, OLD.lease_owner, OLD.lease_epoch
            USING ERRCODE = '55006', -- object_in_use
                HINT = 'worker writes must run inside LeaseScope.runUnderLease';
    END IF;

    IF carried_task <> OLD.id::text THEN
        RAISE EXCEPTION 'this write carried a lease on task %, but is writing to task %',
            carried_task, OLD.id
            USING ERRCODE = '55006',
                HINT = 'a lease authorises writes to one task, not to whatever the transaction touches';
    END IF;

    IF carried_epoch::bigint <> OLD.lease_epoch THEN
        RAISE EXCEPTION 'task % is held at epoch %, and this write carried epoch %',
            OLD.id, OLD.lease_epoch, carried_epoch
            USING ERRCODE = '55006',
                HINT = 'the claim was taken over; stop rather than retry';
    END IF;

    RETURN NEW;
END
$$;

REVOKE EXECUTE ON FUNCTION reject_unfenced_task_write() FROM PUBLIC;

-- BEFORE UPDATE, and not DELETE.
--
-- Deleting a task is not a thing the runtime does; the paths that would reach DELETE are cascades
-- from workspaces, and a cascade that failed because a worker happened to hold a lease would turn
-- deleting a workspace into a race. Nothing has ever needed it, and adding it would create a
-- failure mode before creating a protection.
CREATE TRIGGER tasks_reject_unfenced_write
    BEFORE UPDATE
    ON tasks
    FOR EACH ROW
EXECUTE FUNCTION reject_unfenced_task_write();

COMMENT ON FUNCTION reject_unfenced_task_write() IS
    'Refuses any UPDATE to a task under a live lease unless the session carries that exact claim in app.lease_task and app.lease_epoch.';

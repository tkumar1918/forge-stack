-- When the reconciler last put a task back on the queue.
--
-- Without this the reconciler duplicates its own work. A task that is QUEUED for longer than the
-- grace period is presumed lost and re-enqueued — correct — but nothing recorded that it had just
-- been re-enqueued, so the next sweep presumed it lost again, and the one after that. A single task
-- nobody had capacity for produced a message every sweep, indefinitely.
--
-- That is not merely wasteful. Queue depth is the number an operator reads to answer "are we behind",
-- and a queue full of copies of one task answers it wrongly in the alarming direction. The plan's own
-- advice in §5 — alert on outbox age rather than queue depth alone — assumes the depth still means
-- something.
--
-- Separate from state_entered_at on purpose. Re-queueing is not a state change and must not look like
-- one: "how long has this been QUEUED" is the question a stuck task is diagnosed with, and folding a
-- retry into it would reset the clock on exactly the tasks worth noticing.

ALTER TABLE tasks ADD COLUMN requeued_at timestamptz;

COMMENT ON COLUMN tasks.requeued_at IS
    'When the lease reconciler last re-enqueued this task; bounds re-queue frequency to one per grace period.';

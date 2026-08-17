-- Scaffolding. Phase 2 only.
--
-- The exit criterion for step 2.4 is that a task runs end to end through the FSM with no model and
-- no sandbox, which means something has to decide how an attempt turns out. This column is that
-- decision, written down per task so a person can create a task that fails, or one that escalates,
-- and watch what the machinery does with it.
--
-- A column rather than a marker parsed out of the task's goal, and the difference matters more than
-- it looks. Deriving behaviour from prose would make prose load-bearing — and an architecture whose
-- entire point is that guards read committed rows rather than text should not start by reading text.
-- Keeping it as an explicit, constrained enum also means the fake cannot be triggered by accident.
--
-- REMOVAL TRIGGER: delete this column, and the code that reads it, when real phase handlers land in
-- Phase 4. Until then, see known-gaps.md — a NULL here means "behave as if the work succeeded", which
-- is the only default that lets the rest of the flow be exercised.

ALTER TABLE tasks ADD COLUMN simulated_outcome text;

ALTER TABLE tasks ADD CONSTRAINT tasks_simulated_outcome_ck
    CHECK (simulated_outcome IS NULL OR simulated_outcome IN (
        'SUCCEED',          -- the first attempt succeeds
        'FAIL_ONCE',        -- the first attempt fails, the next one succeeds
        'FAIL',             -- every attempt fails, until the cap is reached
        'ESCALATE'));       -- the first attempt asks for a human

COMMENT ON COLUMN tasks.simulated_outcome IS
    'Phase 2 scaffolding: tells the fake phase handler how an attempt should turn out. Remove with the fake.';

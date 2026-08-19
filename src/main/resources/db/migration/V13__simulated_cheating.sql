-- One more Phase 2 scaffolding outcome: an attempt that makes the tests pass by breaking them.
--
-- §17's diff guards are the part of this product that is hard to copy, and until now nothing could
-- drive them end to end -- the simulated harness only ever produced an honest diff, so the guards
-- were unit-tested against strings and never against a real attempt. A task created with CHEAT walks
-- the whole path: sandbox, edit, capture diff, guards refuse, escalate to a person.
--
-- Removed with the rest of the scaffolding in Phase 4, along with SUCCEED and its siblings.

ALTER TABLE tasks DROP CONSTRAINT tasks_simulated_outcome_ck;

ALTER TABLE tasks ADD CONSTRAINT tasks_simulated_outcome_ck
    CHECK (simulated_outcome IS NULL OR simulated_outcome IN (
        'SUCCEED',          -- the first attempt succeeds
        'FAIL_ONCE',        -- the first attempt fails, the next one succeeds
        'FAIL',             -- every attempt fails, until the cap is reached
        'ESCALATE',         -- the first attempt asks for a human
        'CHEAT'));          -- the tests pass because a test was disabled: the guards must refuse

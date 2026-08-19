-- The anti-cheat verdict, recorded per attempt (§17).
--
-- Separate columns rather than a key inside diff_stats, because that column is descriptive -- how
-- big was the change -- and this one is a decision. A security verdict living inside a statistics
-- blob is a verdict nobody queries and nobody notices going missing.
--
-- Nullable, and the null means something specific: the attempt never reached VERIFYING. The
-- completion guard requires an explicit PASSED rather than "not REFUSED", so an attempt that never
-- ran the guards cannot complete on the strength of never having been checked.

ALTER TABLE task_attempts
    ADD COLUMN diff_guard_verdict  text,
    ADD COLUMN diff_guard_findings text;

ALTER TABLE task_attempts
    ADD CONSTRAINT task_attempts_diff_guard_verdict_ck
        CHECK (diff_guard_verdict IS NULL OR diff_guard_verdict IN ('PASSED', 'REFUSED'));

-- Findings only exist for a verdict that refused. A REFUSED row with nothing recorded would leave a
-- person looking at "the agent did something wrong" with no way to see what.
ALTER TABLE task_attempts
    ADD CONSTRAINT task_attempts_diff_guard_findings_ck
        CHECK (diff_guard_verdict IS DISTINCT FROM 'REFUSED' OR diff_guard_findings IS NOT NULL);

COMMENT ON COLUMN task_attempts.diff_guard_verdict IS
    'PASSED, REFUSED, or null when the attempt never reached VERIFYING (§17).';

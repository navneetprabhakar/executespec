-- v2.5.12 — short-circuit PARTIAL retry loop when the same failure signature
-- repeats. SolutionExecutionService.onV2Partial used to retry up to
-- solution.maxRetries (default 3) with no dedup — a repo that PARTIALs for
-- the same reason every time (e.g. upstream broken build) cost 4 V2 runs
-- before giving up. Live eval on 2026-04-16 showed this happening on a
-- one-line README banner launch (run 9142233f-159, failureReason "V2 returned
-- PARTIAL after 3 retries").
--
-- With these columns, if two consecutive retries produce the same
-- statusReason (V107) — e.g. FINAL_BUILD_FAILED, TESTS_FAILED:5_OF_12 — the
-- retry loop aborts early. Different signatures still get the full retry
-- budget.
--
-- last_failure_signature: V107 statusReason from the most recent child V2 run
--   (max length 80 covers "TESTS_FAILED:{large-int}_OF_{large-int}").
-- consecutive_failure_count: number of consecutive retries that produced the
--   same last_failure_signature. Reset to 1 when the signature changes.

ALTER TABLE execution_solution
    ADD COLUMN last_failure_signature VARCHAR(80),
    ADD COLUMN consecutive_failure_count INTEGER NOT NULL DEFAULT 0;

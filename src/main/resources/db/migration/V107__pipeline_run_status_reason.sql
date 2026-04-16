-- v2.5.10 — honest PARTIAL status.
--
-- Today a single flaky smoke test flips the entire run to PARTIAL even when
-- the final build passes, all tests pass, and the reviewer verdict is CLEAN.
-- Users see "PARTIAL · 97/100" and cannot tell what's wrong. V2RunOrchestrator
-- will populate status_reason alongside status so every terminal state has an
-- explicit explanation surfaced to the UI. Values:
--   CLEAN_COMPLETION
--   CLEAN_WITH_INTERMEDIATE_WARNINGS
--   FINAL_BUILD_FAILED
--   TESTS_FAILED:<n>_OF_<m>
--   REVIEWER_UNRESOLVED
--   REVIEWER_FAILED
--   ALL_TASKS_FAILED
--   LEGACY_UNKNOWN   (backfilled on pre-v2.5.10 rows with insufficient data)

ALTER TABLE pipeline_run ADD COLUMN status_reason VARCHAR(80);

COMMENT ON COLUMN pipeline_run.status_reason IS
  'Explicit reason for the terminal status. Populated by V2RunOrchestrator when scoring.v2-verified is on; V108 backfills historical rows.';

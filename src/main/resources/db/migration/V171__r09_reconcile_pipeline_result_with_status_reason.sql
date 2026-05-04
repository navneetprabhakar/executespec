-- R-09 (UX audit, 2026-05-04) — backfill any historical validation_report
-- rows whose pipeline_result disagrees with the run's status_reason.
--
-- V108 (2026-04-15) did the first backfill pass for the
-- finalBuildPassed/CLEAN_COMPLETION drift. Since then a second class of
-- contradictions accumulated: runs where the reviewer's overall verdict
-- (CLEAN/FIXED) + final build pass produced status_reason=CLEAN_COMPLETION
-- but ValidationReportService.computeResult independently saw an unresolved
-- CRITICAL issue in the issues list and persisted pipelineResult=FAILED.
-- The runtime fix in ValidationReportService.reconcileWithRunStatus
-- (committed in the same change) prevents new drift; this migration cleans
-- up rows that landed before that fix.
--
-- Mapping (mirrors the runtime reconciliation):
--   CLEAN_COMPLETION / CLEAN_WITH_INTERMEDIATE_WARNINGS → PASSED
--     (or PASSED_WITH_FIXES if the row already had it)
--   FINAL_BUILD_FAILED / ALL_TASKS_FAILED / EXECUTION_ABORTED → FAILED
--   REVIEWER_UNRESOLVED / REVIEWER_FAILED / REVIEWER_INTERRUPTED_CODE_PRESERVED /
--     RE_REVIEW_FAILED / RE_REVIEW_NO_WORKSPACE → NEEDS_ATTENTION (unless
--     the row was already FAILED — keep the stricter signal)
--   TESTS_FAILED:N_OF_M → NEEDS_ATTENTION
--   LEGACY_UNKNOWN / null → leave as-is (heuristic was the only source)
--
-- Dry-run impact reporting: the SELECT below is for log-grep visibility; the
-- UPDATE is the actual fix. Both run inside the same transaction.

BEGIN;

-- Visibility: how many rows are about to flip in each direction.
DO $$
DECLARE
  to_passed INTEGER;
  to_failed INTEGER;
  to_needs_attention INTEGER;
BEGIN
  SELECT COUNT(*) INTO to_passed
  FROM validation_report vr
  JOIN pipeline_run pr ON pr.run_id = vr.run_id
  WHERE pr.status_reason IN ('CLEAN_COMPLETION', 'CLEAN_WITH_INTERMEDIATE_WARNINGS')
    AND vr.pipeline_result IN ('FAILED', 'NEEDS_ATTENTION');

  SELECT COUNT(*) INTO to_failed
  FROM validation_report vr
  JOIN pipeline_run pr ON pr.run_id = vr.run_id
  WHERE pr.status_reason IN ('FINAL_BUILD_FAILED', 'ALL_TASKS_FAILED', 'EXECUTION_ABORTED')
    AND vr.pipeline_result <> 'FAILED';

  SELECT COUNT(*) INTO to_needs_attention
  FROM validation_report vr
  JOIN pipeline_run pr ON pr.run_id = vr.run_id
  WHERE (pr.status_reason IN ('REVIEWER_UNRESOLVED', 'REVIEWER_FAILED',
                              'REVIEWER_INTERRUPTED_CODE_PRESERVED',
                              'RE_REVIEW_FAILED', 'RE_REVIEW_NO_WORKSPACE')
         OR pr.status_reason LIKE 'TESTS_FAILED:%')
    AND vr.pipeline_result NOT IN ('FAILED', 'NEEDS_ATTENTION');

  RAISE NOTICE 'V171 R-09 backfill plan: % → PASSED, % → FAILED, % → NEEDS_ATTENTION',
    to_passed, to_failed, to_needs_attention;
END $$;

-- 1. CLEAN_COMPLETION / CLEAN_WITH_INTERMEDIATE_WARNINGS rows that landed as
--    FAILED or NEEDS_ATTENTION. Promote to PASSED, but preserve PASSED_WITH_FIXES
--    when the heuristic spotted auto-fixes (the row would already be that value).
UPDATE validation_report vr
SET pipeline_result = 'PASSED'
FROM pipeline_run pr
WHERE pr.run_id = vr.run_id
  AND pr.status_reason IN ('CLEAN_COMPLETION', 'CLEAN_WITH_INTERMEDIATE_WARNINGS')
  AND vr.pipeline_result IN ('FAILED', 'NEEDS_ATTENTION');

-- 2. Hard-failure status_reasons that landed as anything other than FAILED.
UPDATE validation_report vr
SET pipeline_result = 'FAILED'
FROM pipeline_run pr
WHERE pr.run_id = vr.run_id
  AND pr.status_reason IN ('FINAL_BUILD_FAILED', 'ALL_TASKS_FAILED', 'EXECUTION_ABORTED')
  AND vr.pipeline_result <> 'FAILED';

-- 3. Reviewer-issue / test-failure status_reasons that landed as PASSED or
--    PASSED_WITH_FIXES. Demote to NEEDS_ATTENTION. Don't touch rows already
--    at FAILED — that's the stricter signal and the heuristic was right.
UPDATE validation_report vr
SET pipeline_result = 'NEEDS_ATTENTION'
FROM pipeline_run pr
WHERE pr.run_id = vr.run_id
  AND (pr.status_reason IN ('REVIEWER_UNRESOLVED', 'REVIEWER_FAILED',
                            'REVIEWER_INTERRUPTED_CODE_PRESERVED',
                            'RE_REVIEW_FAILED', 'RE_REVIEW_NO_WORKSPACE')
       OR pr.status_reason LIKE 'TESTS_FAILED:%')
  AND vr.pipeline_result NOT IN ('FAILED', 'NEEDS_ATTENTION');

COMMIT;

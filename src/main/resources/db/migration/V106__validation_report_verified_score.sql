-- v2.5.10 — trustworthy validation score.
--
-- Today the 6 scoring dimensions anchor to an LLM-generated spec-adherence score
-- that infers "requirement implemented" from the file tree alone. This often
-- hallucinates (e.g. sees __tests__/ directories and scores 97/100 on a run
-- whose smoke test never ran). These columns capture the objective signals
-- that already exist at report time (final build, test counts, reviewer
-- verdict) so ReviewScoringService can anchor on a deterministic number.
--
-- verified_score is computed in ValidationReportService and persisted here.
-- reviewer_verdict is the ReviewerResult.Overall enum name (CLEAN,
-- FIXED_ISSUES, UNRESOLVED_ISSUES, REVIEW_FAILED).

ALTER TABLE validation_report ADD COLUMN final_build_passed BOOLEAN;
ALTER TABLE validation_report ADD COLUMN tests_passed       INTEGER;
ALTER TABLE validation_report ADD COLUMN tests_total        INTEGER;
ALTER TABLE validation_report ADD COLUMN reviewer_verdict   VARCHAR(32);
ALTER TABLE validation_report ADD COLUMN verified_score     INTEGER;

COMMENT ON COLUMN validation_report.final_build_passed IS
  'True when ReviewerResult.finalBuildPassed at report time (authoritative build signal).';
COMMENT ON COLUMN validation_report.tests_passed IS
  'Count of TestResult rows with status=PASS for this run at report time.';
COMMENT ON COLUMN validation_report.tests_total IS
  'Count of all TestResult rows for this run at report time.';
COMMENT ON COLUMN validation_report.reviewer_verdict IS
  'ReviewerResult.Overall enum name: CLEAN | FIXED_ISSUES | UNRESOLVED_ISSUES | REVIEW_FAILED.';
COMMENT ON COLUMN validation_report.verified_score IS
  '0-100 deterministic score from build + tests + reviewer. Primary display when scoring.v2-verified flag is on.';

-- Feature flag gating the whole v2.5.10 verified-score + honest-partial rollout.
-- Default OFF at the DB level; admin flips via POST /api/feature-flags/scoring.v2-verified.
-- The V106+V107 columns are still populated regardless of flag state so that toggling
-- the flag on later is instant (no retroactive recompute needed for new runs).
INSERT INTO feature_flag (flag_key, flag_value, value_type, scope, description) VALUES
    ('scoring.v2-verified', 'false', 'BOOLEAN', 'PLATFORM',
     'v2.5.10 — anchor validation score on verified build+tests+verdict signal (not LLM accuracy) and derive run status + status_reason from the same signal. Off by default in prod; admin turns on per rollout.');

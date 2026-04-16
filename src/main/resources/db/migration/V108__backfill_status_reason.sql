-- v2.5.10 — reclassify historical PARTIAL runs using the reviewer/build
-- signals we can now read from validation_report (V106 columns), and label
-- every other PARTIAL row with an explicit status_reason so the UI never
-- shows a silent badge.
--
-- Order matters: the promotion-to-COMPLETE pass runs FIRST so the later
-- "mark remaining PARTIAL with a reason" passes don't overwrite rows that
-- have already been reclassified.

-- ── Pass 1 — promote obvious false-positives to COMPLETE ────────────────────
-- A run is a false-positive PARTIAL if its validation_report shows the final
-- build passed AND the reviewer verdict was clean-ish AND either no tests ran
-- or every test passed. These are the runs where a single flaky intermediate
-- smoke test poisoned the run-level status even though the final state was fine.
UPDATE pipeline_run pr
SET status = 'COMPLETE',
    status_reason = 'CLEAN_WITH_INTERMEDIATE_WARNINGS'
FROM validation_report vr
WHERE vr.run_id = pr.run_id
  AND pr.status = 'PARTIAL'
  AND vr.final_build_passed = TRUE
  AND vr.reviewer_verdict IN ('CLEAN', 'FIXED_ISSUES')
  AND (vr.tests_total IS NULL
       OR vr.tests_total = 0
       OR vr.tests_passed = vr.tests_total);

-- ── Pass 2 — runs where the final build definitively failed ────────────────
UPDATE pipeline_run pr
SET status_reason = 'FINAL_BUILD_FAILED'
WHERE pr.status = 'PARTIAL'
  AND pr.status_reason IS NULL
  AND EXISTS (
    SELECT 1 FROM validation_report vr
    WHERE vr.run_id = pr.run_id AND vr.final_build_passed = FALSE
  );

-- ── Pass 3 — tests failed (build may have passed) ──────────────────────────
UPDATE pipeline_run pr
SET status_reason = 'TESTS_FAILED:' || (vr.tests_total - vr.tests_passed) || '_OF_' || vr.tests_total
FROM validation_report vr
WHERE vr.run_id = pr.run_id
  AND pr.status = 'PARTIAL'
  AND pr.status_reason IS NULL
  AND vr.tests_total IS NOT NULL
  AND vr.tests_total > 0
  AND vr.tests_passed < vr.tests_total;

-- ── Pass 4 — reviewer verdict was unresolved / failed ──────────────────────
UPDATE pipeline_run pr
SET status_reason = CASE vr.reviewer_verdict
    WHEN 'UNRESOLVED_ISSUES' THEN 'REVIEWER_UNRESOLVED'
    WHEN 'REVIEW_FAILED'     THEN 'REVIEWER_FAILED'
    ELSE 'LEGACY_UNKNOWN'
END
FROM validation_report vr
WHERE vr.run_id = pr.run_id
  AND pr.status = 'PARTIAL'
  AND pr.status_reason IS NULL
  AND vr.reviewer_verdict IN ('UNRESOLVED_ISSUES', 'REVIEW_FAILED');

-- ── Pass 5 — everything else stays PARTIAL but gets the LEGACY_UNKNOWN tag ─
-- These are historical rows with no validation_report or with null V106
-- columns (pre-v2.5.10). At least the UI tooltip can now say "pre-v2.5.10
-- run, signals unavailable" instead of showing nothing.
UPDATE pipeline_run
SET status_reason = 'LEGACY_UNKNOWN'
WHERE status = 'PARTIAL' AND status_reason IS NULL;

-- ── Pass 6 — apply CLEAN_COMPLETION tag to recent CLEAN completes ──────────
-- A bare COMPLETE with no reason is fine for the UI (tooltip stays quiet),
-- but tagging the recent CLEAN ones makes dashboards prettier.
UPDATE pipeline_run pr
SET status_reason = 'CLEAN_COMPLETION'
FROM validation_report vr
WHERE vr.run_id = pr.run_id
  AND pr.status = 'COMPLETE'
  AND pr.status_reason IS NULL
  AND vr.final_build_passed = TRUE
  AND vr.reviewer_verdict IN ('CLEAN', 'FIXED_ISSUES');

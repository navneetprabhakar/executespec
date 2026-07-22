-- RR-1696 (persisted half of RR-1685) — record whether a scan's file/LOC counts are exact or a
-- lower bound. RR-1685 fixed the PROMPT (the model is told "LOWER BOUND — not the whole
-- repository") but code_review_scan.total_files_scanned / total_loc were persisted and exposed on
-- the CodeReviewController DTO with no coverage marker, so the human-facing scan record still stated
-- a possibly-truncated count as fact.
--
-- Additive, nullable column only. Values mirror CodeReviewAgent.ScanCoverage:
-- COMPLETE | TRUNCATED_FILE_LIMIT | TRUNCATED_READ_BUDGET | TRUNCATED_TIME_BUDGET |
-- TRUNCATED_WALK_FAILED. Legacy rows stay NULL and are read as UNKNOWN (a lower bound) — defaulting
-- a legacy row to COMPLETE would recreate the defect.
ALTER TABLE code_review_scan
  ADD COLUMN IF NOT EXISTS scan_coverage VARCHAR(32);

COMMENT ON COLUMN code_review_scan.scan_coverage IS
  'Why the file sampling walk ended, so total_files_scanned/total_loc are known exact vs a lower '
  'bound. One of CodeReviewAgent.ScanCoverage; NULL legacy rows are read as UNKNOWN (lower bound).';

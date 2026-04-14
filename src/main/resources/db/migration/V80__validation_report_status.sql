-- V80 — add `status` column to validation_report so consumers (UI, tests, report
-- controller) can distinguish PENDING (in-flight generation) from COMPLETE /
-- FAILED without inferring from accuracy_score nullability. Existing rows are
-- backfilled to COMPLETE because they predate the column and the fact that
-- they persisted at all means generation succeeded.

ALTER TABLE validation_report
    ADD COLUMN IF NOT EXISTS status VARCHAR(20) NOT NULL DEFAULT 'COMPLETE';

CREATE INDEX IF NOT EXISTS idx_validation_report_status ON validation_report(status);

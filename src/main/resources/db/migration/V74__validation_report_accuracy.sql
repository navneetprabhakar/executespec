-- V74: Add accuracy assessment columns to validation_report
-- These columns store the Phase 3 accuracy scoring output from AccuracyAssessmentService.
-- accuracy_score:     0-100 integer (spec adherence score)
-- accuracy_grade:     A/B/C/D/F letter grade
-- accuracy_json:      Full AccuracyReport JSON (requirements, endpoint coverage, schema accuracy, etc.)

ALTER TABLE validation_report
    ADD COLUMN IF NOT EXISTS accuracy_score      INTEGER,
    ADD COLUMN IF NOT EXISTS accuracy_grade      VARCHAR(2),
    ADD COLUMN IF NOT EXISTS accuracy_json       TEXT;

COMMENT ON COLUMN validation_report.accuracy_score IS
    'Spec adherence score 0-100 produced by AccuracyAssessmentService';
COMMENT ON COLUMN validation_report.accuracy_grade IS
    'Letter grade: A=90-100, B=80-89, C=70-79, D=60-69, F<60';
COMMENT ON COLUMN validation_report.accuracy_json IS
    'Full AccuracyReport JSON including implemented/missing requirements, endpoint coverage';

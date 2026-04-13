-- V85 — Pilot R4 (feature assessment) + R7 (Figma URL).
-- assessment_result holds the JSON output of FeatureAssessmentService:
--   {filesAffected, newFiles, complexity, risks, crossRepoDeps, summary, constraints}
-- figma_url is per-run; populated when the user pasted a Figma link in NewRunModal.

ALTER TABLE pipeline_run
    ADD COLUMN IF NOT EXISTS assessment_result JSONB;

ALTER TABLE pipeline_run
    ADD COLUMN IF NOT EXISTS figma_url VARCHAR(500);

-- V76: Tag each pipeline_run row with the harness version that produced it.
-- V1 runs through PipelineHarness; V2 runs through V2RunOrchestrator. Frontend polling
-- uses the same endpoints for both but the column lets us route per-run actions back
-- to the correct pipeline (see HarnessVersionResolver.resolveForSeed).
--
-- Default 'v1' for backward compat — every existing row is assumed V1.

ALTER TABLE pipeline_run
    ADD COLUMN harness_version VARCHAR(16) NOT NULL DEFAULT 'v1';

-- Index supports per-version analytics queries (V1 vs V2 accuracy/cost/time comparisons).
CREATE INDEX IF NOT EXISTS idx_pipeline_run_harness_version
    ON pipeline_run (harness_version);

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

-- Seed V2 rollout feature flags so FeatureFlagService.update() can find them without
-- a separate bootstrap. Values default to "v1" routing (safe: no behavior change).
INSERT INTO feature_flag (flag_key, flag_value, value_type, scope, description) VALUES
    ('harness.version',            'v1', 'STRING',  'PLATFORM',
     'Which harness produces new runs: v1 | v2 | v2-canary. Default v1 — V2 runs only when flipped.'),
    ('harness.v2.canary_percent',  '0',  'INTEGER', 'PLATFORM',
     'When harness.version=v2-canary, this percent (0..100) of runs route to V2; the rest stay on V1.')
ON CONFLICT (flag_key) DO NOTHING;

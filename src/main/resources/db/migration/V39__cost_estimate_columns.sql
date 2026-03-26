-- V39: Add pre-run cost estimate columns to pipeline_run
ALTER TABLE pipeline_run ADD COLUMN IF NOT EXISTS cost_estimate_low DOUBLE PRECISION;
ALTER TABLE pipeline_run ADD COLUMN IF NOT EXISTS cost_estimate_high DOUBLE PRECISION;

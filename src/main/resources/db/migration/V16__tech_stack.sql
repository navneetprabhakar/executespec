-- V16: Add tech_stack column for plan-derived technology detection.
-- Populated after planning from task titles, objectives, and boundaries.
ALTER TABLE pipeline_run ADD COLUMN IF NOT EXISTS tech_stack VARCHAR(500);

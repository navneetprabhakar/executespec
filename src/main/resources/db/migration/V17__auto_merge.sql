-- V17: Add auto_merge flag to pipeline_run (mirrors skip_approval for merge gate)
ALTER TABLE pipeline_run ADD COLUMN IF NOT EXISTS auto_merge BOOLEAN DEFAULT FALSE;

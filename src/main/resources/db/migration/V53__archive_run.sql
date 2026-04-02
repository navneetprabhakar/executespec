ALTER TABLE pipeline_run ADD COLUMN archived_at TIMESTAMPTZ;
CREATE INDEX idx_pipeline_run_archived ON pipeline_run (archived_at) WHERE archived_at IS NOT NULL;

-- Data retention: soft-delete columns + per-tier retention days
-- FREE=30d, PRO=90d, ENTERPRISE=365d (0 = inherit system default)

ALTER TABLE pipeline_run
    ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ;

ALTER TABLE agent_task
    ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ;

ALTER TABLE validation_report
    ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ;

-- Per-tier retention in days (null = no override, uses system default)
ALTER TABLE tier_config
    ADD COLUMN IF NOT EXISTS retention_days INT;

UPDATE tier_config SET retention_days = 30  WHERE tier = 'FREE';
UPDATE tier_config SET retention_days = 90  WHERE tier = 'PRO';
UPDATE tier_config SET retention_days = 365 WHERE tier = 'ENTERPRISE';

-- Partial indexes for efficient retention sweeps (skip already-deleted rows)
CREATE INDEX IF NOT EXISTS idx_pipeline_run_retention
    ON pipeline_run (user_tier, completed_at)
    WHERE deleted_at IS NULL AND completed_at IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_pipeline_run_deleted
    ON pipeline_run (deleted_at)
    WHERE deleted_at IS NOT NULL;

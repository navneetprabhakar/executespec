-- V29: Per-run preview start limits

-- Track how many previews have been started for each run
ALTER TABLE pipeline_run ADD COLUMN preview_start_count INTEGER NOT NULL DEFAULT 0;

-- Per-tier limit on preview starts per run
-- FREE=3, PRO=10, ENTERPRISE=-1 (unlimited)
ALTER TABLE tier_config ADD COLUMN max_preview_starts INTEGER NOT NULL DEFAULT 3;

UPDATE tier_config SET max_preview_starts = 3  WHERE tier = 'FREE';
UPDATE tier_config SET max_preview_starts = 10 WHERE tier = 'PRO';
UPDATE tier_config SET max_preview_starts = -1 WHERE tier = 'ENTERPRISE';

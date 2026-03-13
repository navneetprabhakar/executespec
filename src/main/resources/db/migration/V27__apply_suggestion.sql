-- V27: Apply suggestion support — allows chat copilot and review suggestions
-- to be applied to the same run (appended as new tasks).

ALTER TABLE pipeline_run ADD COLUMN IF NOT EXISTS apply_suggestion_count INT NOT NULL DEFAULT 0;

ALTER TABLE tier_config ADD COLUMN IF NOT EXISTS max_apply_suggestions INT NOT NULL DEFAULT 2;

UPDATE tier_config SET max_apply_suggestions = 2   WHERE tier = 'FREE';
UPDATE tier_config SET max_apply_suggestions = 10  WHERE tier = 'PRO';
UPDATE tier_config SET max_apply_suggestions = -1  WHERE tier = 'ENTERPRISE';

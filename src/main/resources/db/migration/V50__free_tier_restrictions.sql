-- V50: FREE tier restrictions + per-run cost budget
-- 1. Add user_tier to pipeline_run (stamped at run creation for async access)
-- 2. Add max_run_cost_usd to tier_config
-- 3. Update FREE tier: 3 lifetime runs, $3 cost budget
-- 4. Update PRO tier: $5 cost budget (no BYOK)

ALTER TABLE pipeline_run ADD COLUMN user_tier VARCHAR(20);

ALTER TABLE tier_config ADD COLUMN max_run_cost_usd DOUBLE PRECISION;

UPDATE tier_config SET monthly_run_limit = 3, max_run_cost_usd = 3.00 WHERE tier = 'FREE';
UPDATE tier_config SET max_run_cost_usd = 5.00 WHERE tier = 'PRO';

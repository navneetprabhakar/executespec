-- =====================================================================
-- V62: Billing plan restructure
--   - Add MAX tier to tier_config
--   - Update PRO tier_config (10 runs, 2 concurrent)
--   - Deactivate old paid plans
--   - Rename PLATFORM → MANAGED
--   - Insert 4 new plans (Pro/Max × INR/USD)
-- =====================================================================

-- 1. Add MAX tier
INSERT INTO tier_config (tier, display_name, monthly_run_limit, is_fixed, description,
    max_org_members, max_chat_messages, concurrent_run_limit, max_apply_suggestions,
    max_preview_starts, max_run_auto_retries, can_deploy, max_deployments, max_run_cost_usd)
VALUES ('MAX', 'Max', -1, FALSE, 'Unlimited BYOK tier with own API keys',
    -1, 10, 3, -1, -1, 3, TRUE, -1, NULL)
ON CONFLICT (tier) DO NOTHING;

-- 2a. Align FREE tier_config with billing_plan_limit (5 runs, not 3)
UPDATE tier_config SET monthly_run_limit = 5 WHERE tier = 'FREE';

-- 2b. Update PRO tier_config
UPDATE tier_config SET
    monthly_run_limit = 10,
    concurrent_run_limit = 2,
    max_run_cost_usd = 5.0,
    description = 'Managed keys tier with 10 runs/month'
WHERE tier = 'PRO';

-- 3. Deactivate all existing paid plans (soft-delete)
UPDATE billing_plan SET active = FALSE, updated_at = NOW()
WHERE tier IN ('STARTER', 'PRO') AND active = TRUE;

-- 4. Rename PLATFORM → MANAGED in all billing_plan rows
UPDATE billing_plan SET key_mode = 'MANAGED' WHERE key_mode = 'PLATFORM';

-- 5. Insert 4 new billing plans

-- Pro Monthly INR
INSERT INTO billing_plan (id, name, tier, key_mode, product_access, period,
    base_amount_inr, base_amount_usd, per_seat_inr, per_seat_usd,
    included_seats, run_limit, annual_discount_pct, active)
VALUES (gen_random_uuid(), 'Pro Monthly INR', 'PRO', 'MANAGED',
    '{"CODE_GEN","PIPELINE_GEN","PILOTSRE","IAC_GEN"}', 'MONTHLY',
    169900, 0, 0, 0, 1, 10, 0, TRUE);

-- Pro Monthly USD
INSERT INTO billing_plan (id, name, tier, key_mode, product_access, period,
    base_amount_inr, base_amount_usd, per_seat_inr, per_seat_usd,
    included_seats, run_limit, annual_discount_pct, active)
VALUES (gen_random_uuid(), 'Pro Monthly USD', 'PRO', 'MANAGED',
    '{"CODE_GEN","PIPELINE_GEN","PILOTSRE","IAC_GEN"}', 'MONTHLY',
    0, 2000, 0, 0, 1, 10, 0, TRUE);

-- Max Monthly INR
INSERT INTO billing_plan (id, name, tier, key_mode, product_access, period,
    base_amount_inr, base_amount_usd, per_seat_inr, per_seat_usd,
    included_seats, run_limit, annual_discount_pct, active)
VALUES (gen_random_uuid(), 'Max Monthly INR', 'MAX', 'BYOK',
    '{"CODE_GEN","PIPELINE_GEN","PILOTSRE","IAC_GEN"}', 'MONTHLY',
    249900, 0, 0, 0, 1, -1, 0, TRUE);

-- Max Monthly USD
INSERT INTO billing_plan (id, name, tier, key_mode, product_access, period,
    base_amount_inr, base_amount_usd, per_seat_inr, per_seat_usd,
    included_seats, run_limit, annual_discount_pct, active)
VALUES (gen_random_uuid(), 'Max Monthly USD', 'MAX', 'BYOK',
    '{"CODE_GEN","PIPELINE_GEN","PILOTSRE","IAC_GEN"}', 'MONTHLY',
    0, 2900, 0, 0, 1, -1, 0, TRUE);

-- 6. Insert billing_plan_limit rows for new plans

-- Pro Monthly INR limits
INSERT INTO billing_plan_limit (id, billing_plan_id, metric, max_value)
SELECT gen_random_uuid(), id, 'RUNS', 10 FROM billing_plan WHERE name = 'Pro Monthly INR' AND active = TRUE;
INSERT INTO billing_plan_limit (id, billing_plan_id, metric, max_value)
SELECT gen_random_uuid(), id, 'PROJECTS', 10 FROM billing_plan WHERE name = 'Pro Monthly INR' AND active = TRUE;
INSERT INTO billing_plan_limit (id, billing_plan_id, metric, max_value)
SELECT gen_random_uuid(), id, 'STORAGE_MB', 500 FROM billing_plan WHERE name = 'Pro Monthly INR' AND active = TRUE;

-- Pro Monthly USD limits
INSERT INTO billing_plan_limit (id, billing_plan_id, metric, max_value)
SELECT gen_random_uuid(), id, 'RUNS', 10 FROM billing_plan WHERE name = 'Pro Monthly USD' AND active = TRUE;
INSERT INTO billing_plan_limit (id, billing_plan_id, metric, max_value)
SELECT gen_random_uuid(), id, 'PROJECTS', 10 FROM billing_plan WHERE name = 'Pro Monthly USD' AND active = TRUE;
INSERT INTO billing_plan_limit (id, billing_plan_id, metric, max_value)
SELECT gen_random_uuid(), id, 'STORAGE_MB', 500 FROM billing_plan WHERE name = 'Pro Monthly USD' AND active = TRUE;

-- Max Monthly INR limits (-1 = unlimited)
INSERT INTO billing_plan_limit (id, billing_plan_id, metric, max_value)
SELECT gen_random_uuid(), id, 'RUNS', -1 FROM billing_plan WHERE name = 'Max Monthly INR' AND active = TRUE;
INSERT INTO billing_plan_limit (id, billing_plan_id, metric, max_value)
SELECT gen_random_uuid(), id, 'PROJECTS', 50 FROM billing_plan WHERE name = 'Max Monthly INR' AND active = TRUE;
INSERT INTO billing_plan_limit (id, billing_plan_id, metric, max_value)
SELECT gen_random_uuid(), id, 'STORAGE_MB', 10240 FROM billing_plan WHERE name = 'Max Monthly INR' AND active = TRUE;

-- Max Monthly USD limits (-1 = unlimited)
INSERT INTO billing_plan_limit (id, billing_plan_id, metric, max_value)
SELECT gen_random_uuid(), id, 'RUNS', -1 FROM billing_plan WHERE name = 'Max Monthly USD' AND active = TRUE;
INSERT INTO billing_plan_limit (id, billing_plan_id, metric, max_value)
SELECT gen_random_uuid(), id, 'PROJECTS', 50 FROM billing_plan WHERE name = 'Max Monthly USD' AND active = TRUE;
INSERT INTO billing_plan_limit (id, billing_plan_id, metric, max_value)
SELECT gen_random_uuid(), id, 'STORAGE_MB', 10240 FROM billing_plan WHERE name = 'Max Monthly USD' AND active = TRUE;

-- GA-readiness P2.1 (2026-05-06) — un-hide PRO as the Solo tier.
--
-- Audit §6: live `/api/billing/plans` returned only TEAM-INR + TEAM-USD + FREE
-- plans on dev. The cheapest paid tier was therefore $39/seat × 2 included
-- seats = $78/mo BYOK, and FREE is 5 runs/month — a yawning chasm. PRO has
-- been HIDDEN since V122 (2026-04-22 TEAM launch) but the tier_config row
-- and the existing grandfathered "Pro Monthly *" billing_plan rows are still
-- in the DB.
--
-- This migration:
--   1. Renames tier_config.PRO display_name "Pro" → "Solo" and sets
--      lifecycle_state back to LISTED so /api/billing/plans returns it.
--   2. Bumps tier_config.PRO.monthly_run_limit 20 → 30 to match the audit's
--      recommended ladder (§6: "30 LOW or 10 MEDIUM or 4 HIGH credits").
--   3. Inserts NEW billing_plan rows "Solo Monthly USD" ($29) + "Solo Monthly
--      INR" (₹2,499). The old "Pro Monthly *" rows stay HIDDEN — existing
--      PRO subscribers continue renewing on those rows; only new buyers
--      see the Solo branding + price.
--   4. Inserts billing_plan_limit rows mirroring the PRO cap shape but with
--      RUNS=30 instead of 20.
--
-- Rollback: V_{next} setting tier_config.PRO display_name back to "Pro" +
-- lifecycle_state=HIDDEN, plus marking the new "Solo Monthly *" rows
-- inactive. Grandfathered PRO subscribers are unaffected throughout.

BEGIN;

-- 1. Un-hide PRO + rename to Solo + bump run limit.
UPDATE tier_config
   SET display_name = 'Solo',
       monthly_run_limit = 30,
       description = 'For solo developers — 30 credits/month (≈30 LOW, 10 MEDIUM, or 7 HIGH runs) on managed Anthropic/OpenAI/Gemini keys. Step up from FREE.',
       lifecycle_state = 'LISTED',
       lifecycle_changed_at = now(),
       lifecycle_changed_by = 'V176',
       lifecycle_reason = 'GA-readiness P2.1 — un-hide as Solo at $29/mo to close FREE-to-$78 conversion chasm'
 WHERE tier = 'PRO';

-- 2. Insert NEW Solo Monthly USD billing_plan row.
--    base_amount_usd is in cents → $29 = 2900.
INSERT INTO billing_plan (
    id, name, tier, key_mode, product_access, period,
    base_amount_inr, base_amount_usd, per_seat_inr, per_seat_usd,
    included_seats, run_limit, annual_discount_pct, is_founder_pricing, active,
    lifecycle_state, lifecycle_changed_at, lifecycle_changed_by, lifecycle_reason,
    created_at, updated_at
) VALUES (
    gen_random_uuid(), 'Solo Monthly USD', 'PRO', 'MANAGED',
    '{"CODE_GEN","PIPELINE_GEN","PILOTSRE","IAC_GEN"}', 'MONTHLY',
    0, 2900, 0, 0,
    1, 30, 0, FALSE, TRUE,
    'LISTED', now(), 'V176', 'GA-readiness P2.1 — Solo tier launch ($29/mo managed)',
    now(), now()
)
ON CONFLICT DO NOTHING;

-- 3. Insert NEW Solo Monthly INR billing_plan row.
--    base_amount_inr is in paise → ₹2,499 = 249900.
INSERT INTO billing_plan (
    id, name, tier, key_mode, product_access, period,
    base_amount_inr, base_amount_usd, per_seat_inr, per_seat_usd,
    included_seats, run_limit, annual_discount_pct, is_founder_pricing, active,
    lifecycle_state, lifecycle_changed_at, lifecycle_changed_by, lifecycle_reason,
    created_at, updated_at
) VALUES (
    gen_random_uuid(), 'Solo Monthly INR', 'PRO', 'MANAGED',
    '{"CODE_GEN","PIPELINE_GEN","PILOTSRE","IAC_GEN"}', 'MONTHLY',
    249900, 0, 0, 0,
    1, 30, 0, FALSE, TRUE,
    'LISTED', now(), 'V176', 'GA-readiness P2.1 — Solo tier launch (₹2,499/mo managed)',
    now(), now()
)
ON CONFLICT DO NOTHING;

-- 4. Per-plan limit rows — mirror the PRO cap shape with RUNS bumped to 30.
INSERT INTO billing_plan_limit (id, billing_plan_id, metric, max_value)
SELECT gen_random_uuid(), id, 'RUNS', 30
  FROM billing_plan
 WHERE name = 'Solo Monthly USD' AND active = TRUE
   AND NOT EXISTS (
     SELECT 1 FROM billing_plan_limit
      WHERE billing_plan_id = billing_plan.id AND metric = 'RUNS'
   );

INSERT INTO billing_plan_limit (id, billing_plan_id, metric, max_value)
SELECT gen_random_uuid(), id, 'PROJECTS', 2
  FROM billing_plan
 WHERE name = 'Solo Monthly USD' AND active = TRUE
   AND NOT EXISTS (
     SELECT 1 FROM billing_plan_limit
      WHERE billing_plan_id = billing_plan.id AND metric = 'PROJECTS'
   );

INSERT INTO billing_plan_limit (id, billing_plan_id, metric, max_value)
SELECT gen_random_uuid(), id, 'STORAGE_MB', 1024
  FROM billing_plan
 WHERE name = 'Solo Monthly USD' AND active = TRUE
   AND NOT EXISTS (
     SELECT 1 FROM billing_plan_limit
      WHERE billing_plan_id = billing_plan.id AND metric = 'STORAGE_MB'
   );

INSERT INTO billing_plan_limit (id, billing_plan_id, metric, max_value)
SELECT gen_random_uuid(), id, 'RUNS', 30
  FROM billing_plan
 WHERE name = 'Solo Monthly INR' AND active = TRUE
   AND NOT EXISTS (
     SELECT 1 FROM billing_plan_limit
      WHERE billing_plan_id = billing_plan.id AND metric = 'RUNS'
   );

INSERT INTO billing_plan_limit (id, billing_plan_id, metric, max_value)
SELECT gen_random_uuid(), id, 'PROJECTS', 2
  FROM billing_plan
 WHERE name = 'Solo Monthly INR' AND active = TRUE
   AND NOT EXISTS (
     SELECT 1 FROM billing_plan_limit
      WHERE billing_plan_id = billing_plan.id AND metric = 'PROJECTS'
   );

INSERT INTO billing_plan_limit (id, billing_plan_id, metric, max_value)
SELECT gen_random_uuid(), id, 'STORAGE_MB', 1024
  FROM billing_plan
 WHERE name = 'Solo Monthly INR' AND active = TRUE
   AND NOT EXISTS (
     SELECT 1 FROM billing_plan_limit
      WHERE billing_plan_id = billing_plan.id AND metric = 'STORAGE_MB'
   );

COMMIT;

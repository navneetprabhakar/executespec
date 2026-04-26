-- =====================================================================
-- V144: Reinstate the v2.6.1-Wave-1 per-seat TEAM tier.
--
-- Reverses V141 (2026-04-26): rename TEAM → MAX (un-renames V141 step 4-6),
-- then re-introduces the per-seat TEAM tier_config + billing_plan + limit
-- rows that V141 step 1-3 deleted.
--
-- Decision (2026-04-26): the consumer/B2B product-split UI plan
-- (`feature/product-split-prep`) creates two distinct surfaces:
--   * Build (build.executespec.ai) → multi-seat business product → TEAM
--   * Vibe (app.executespec.ai)   → power individual          → MAX
-- Net post-V144 ladder restored to v2.6.1: FREE / PRO (HIDDEN) /
-- TEAM (per-seat $49/mo, 2-20 seats) / MAX ($29/mo BYOK unlimited) /
-- ENTERPRISE.
--
-- Idempotent BEGIN/COMMIT. Safe to re-run.
--
-- Migration number: V144 picked as the next free slot after V143
-- (product-split). User has the resequence call when this batch lands.
--
-- NOT covered (out of scope, env-driven):
--   * Stripe / Razorpay plan IDs — ops will re-introduce
--     STRIPE_TEAM_MONTHLY_PRICE_ID + Razorpay equivalents alongside
--     the existing STRIPE_MAX_PRICE_ID, out-of-band.
--   * org.seat_count column was NOT dropped by V141 (V123 created it,
--     V141 left it intact); no schema change needed here.
-- =====================================================================

BEGIN;

-- ------------------------------------------------------------------
-- 1. UN-RENAME tier_config rows: V141 had renamed MAX → TEAM in-place.
--    Reverse it so MAX exists again. (DELETE the new TEAM tier_config
--    happens AFTER this if any orphan, but we'll use a different name
--    on the per-seat re-insert to avoid PK collision.)
-- ------------------------------------------------------------------

UPDATE tier_config
   SET tier             = 'MAX',
       display_name     = 'Max',
       description      = 'For power individuals — $29/mo + BYOK, unlimited runs against your own provider keys.'
 WHERE tier = 'TEAM'
   AND description LIKE '%renamed from MAX 2026-04-26%';

-- ------------------------------------------------------------------
-- 2. UN-RENAME billing_plan rows. V141 step 5 turned "Max …" into
--    "Team …" via name prefix swap; reverse with the same surgical
--    prefix swap.
-- ------------------------------------------------------------------

UPDATE billing_plan
   SET tier = 'MAX',
       name = CASE
                WHEN name LIKE 'Team %'    THEN 'Max ' || substring(name FROM 6)
                WHEN name = 'Team'         THEN 'Max'
                ELSE name
              END
 WHERE tier = 'TEAM';

-- ------------------------------------------------------------------
-- 3. UN-RENAME customer-facing tier columns. V141 rewrote MAX → TEAM
--    on app_user / org / pipeline_run / app_user.trial_tier. Reverse.
--
--    NB: rows that were ORIGINALLY on the deleted v2.6.1-Wave-1 TEAM
--    were defensively rehomed to ENTERPRISE by V141 step 1, then
--    can't be distinguished from "always-ENTERPRISE" rows. Those stay
--    on ENTERPRISE — the rename only affects rows that were on MAX
--    pre-V141 and got renamed to TEAM.
-- ------------------------------------------------------------------

UPDATE app_user
   SET tier = 'MAX'
 WHERE tier = 'TEAM';

UPDATE org
   SET tier = 'MAX'
 WHERE tier = 'TEAM';

UPDATE pipeline_run
   SET user_tier = 'MAX'
 WHERE user_tier = 'TEAM';

UPDATE app_user
   SET trial_tier = 'MAX'
 WHERE trial_tier = 'TEAM';

-- ------------------------------------------------------------------
-- 4. RE-INSERT the per-seat TEAM tier_config row (matches V122 shape:
--    50 credits/seat, max 20 members, no deploy, 180-day retention,
--    LISTED).
-- ------------------------------------------------------------------

INSERT INTO tier_config (
    tier, display_name, monthly_run_limit, is_fixed, description,
    max_org_members, max_chat_messages, concurrent_run_limit, max_apply_suggestions,
    max_preview_starts, max_run_auto_retries, can_deploy, max_deployments,
    max_run_cost_usd, retention_days, execution_view_enabled,
    lifecycle_state, lifecycle_changed_at, lifecycle_changed_by, lifecycle_reason
) VALUES (
    'TEAM', 'Team', 50, FALSE,
    'For engineering teams of 2-20 with shared credit pool (50 credits/seat/mo). monthly_run_limit is per-seat; effective limit = seats × 50, enforced in TierEnforcementService.',
    20, 10, 5, -1,
    -1, 2, FALSE, 0,
    10.0, 180, FALSE,
    'LISTED', now(), 'system-migration', 'TEAM tier reinstated (V144) — product-split surface for Build (build.executespec.ai)'
)
ON CONFLICT (tier) DO NOTHING;

-- ------------------------------------------------------------------
-- 5. RE-INSERT the per-seat TEAM billing_plan rows (matches V123:
--    Team Monthly INR ₹3,999/seat + Team Monthly USD $49/seat).
--    Yearly deferred (was deferred in v2.6.1 too).
-- ------------------------------------------------------------------

INSERT INTO billing_plan (
    id, name, tier, key_mode, product_access, period,
    base_amount_inr, base_amount_usd, per_seat_inr, per_seat_usd,
    included_seats, run_limit, annual_discount_pct, is_founder_pricing, active,
    lifecycle_state, lifecycle_changed_at, lifecycle_changed_by, lifecycle_reason,
    created_at, updated_at
)
SELECT
    gen_random_uuid(), 'Team Monthly INR', 'TEAM', 'MANAGED',
    '{"CODE_GEN","PIPELINE_GEN","PILOTSRE","IAC_GEN"}', 'MONTHLY',
    0, 0, 399900, 0,
    2, -1, 0, FALSE, TRUE,
    'LISTED', now(), 'system-migration', 'TEAM tier reinstated (V144)',
    now(), now()
WHERE NOT EXISTS (
    SELECT 1 FROM billing_plan WHERE name = 'Team Monthly INR' AND tier = 'TEAM' AND active = TRUE
);

INSERT INTO billing_plan (
    id, name, tier, key_mode, product_access, period,
    base_amount_inr, base_amount_usd, per_seat_inr, per_seat_usd,
    included_seats, run_limit, annual_discount_pct, is_founder_pricing, active,
    lifecycle_state, lifecycle_changed_at, lifecycle_changed_by, lifecycle_reason,
    created_at, updated_at
)
SELECT
    gen_random_uuid(), 'Team Monthly USD', 'TEAM', 'MANAGED',
    '{"CODE_GEN","PIPELINE_GEN","PILOTSRE","IAC_GEN"}', 'MONTHLY',
    0, 0, 0, 4900,
    2, -1, 0, FALSE, TRUE,
    'LISTED', now(), 'system-migration', 'TEAM tier reinstated (V144)',
    now(), now()
WHERE NOT EXISTS (
    SELECT 1 FROM billing_plan WHERE name = 'Team Monthly USD' AND tier = 'TEAM' AND active = TRUE
);

-- ------------------------------------------------------------------
-- 6. RE-INSERT billing_plan_limit rows for both TEAM plans (V123).
--    run_limit at plan level is -1 (unlimited); the actual per-month
--    cap is seats × 50 computed at runtime by TierEnforcementService.
-- ------------------------------------------------------------------

INSERT INTO billing_plan_limit (id, billing_plan_id, metric, max_value)
SELECT gen_random_uuid(), bp.id, 'RUNS', -1
  FROM billing_plan bp
 WHERE bp.name = 'Team Monthly INR' AND bp.tier = 'TEAM' AND bp.active = TRUE
   AND NOT EXISTS (
        SELECT 1 FROM billing_plan_limit l WHERE l.billing_plan_id = bp.id AND l.metric = 'RUNS'
   );
INSERT INTO billing_plan_limit (id, billing_plan_id, metric, max_value)
SELECT gen_random_uuid(), bp.id, 'PROJECTS', 5
  FROM billing_plan bp
 WHERE bp.name = 'Team Monthly INR' AND bp.tier = 'TEAM' AND bp.active = TRUE
   AND NOT EXISTS (
        SELECT 1 FROM billing_plan_limit l WHERE l.billing_plan_id = bp.id AND l.metric = 'PROJECTS'
   );
INSERT INTO billing_plan_limit (id, billing_plan_id, metric, max_value)
SELECT gen_random_uuid(), bp.id, 'STORAGE_MB', 5120
  FROM billing_plan bp
 WHERE bp.name = 'Team Monthly INR' AND bp.tier = 'TEAM' AND bp.active = TRUE
   AND NOT EXISTS (
        SELECT 1 FROM billing_plan_limit l WHERE l.billing_plan_id = bp.id AND l.metric = 'STORAGE_MB'
   );

INSERT INTO billing_plan_limit (id, billing_plan_id, metric, max_value)
SELECT gen_random_uuid(), bp.id, 'RUNS', -1
  FROM billing_plan bp
 WHERE bp.name = 'Team Monthly USD' AND bp.tier = 'TEAM' AND bp.active = TRUE
   AND NOT EXISTS (
        SELECT 1 FROM billing_plan_limit l WHERE l.billing_plan_id = bp.id AND l.metric = 'RUNS'
   );
INSERT INTO billing_plan_limit (id, billing_plan_id, metric, max_value)
SELECT gen_random_uuid(), bp.id, 'PROJECTS', 5
  FROM billing_plan bp
 WHERE bp.name = 'Team Monthly USD' AND bp.tier = 'TEAM' AND bp.active = TRUE
   AND NOT EXISTS (
        SELECT 1 FROM billing_plan_limit l WHERE l.billing_plan_id = bp.id AND l.metric = 'PROJECTS'
   );
INSERT INTO billing_plan_limit (id, billing_plan_id, metric, max_value)
SELECT gen_random_uuid(), bp.id, 'STORAGE_MB', 5120
  FROM billing_plan bp
 WHERE bp.name = 'Team Monthly USD' AND bp.tier = 'TEAM' AND bp.active = TRUE
   AND NOT EXISTS (
        SELECT 1 FROM billing_plan_limit l WHERE l.billing_plan_id = bp.id AND l.metric = 'STORAGE_MB'
   );

-- ------------------------------------------------------------------
-- 7. tier_config caps for TEAM (V140 columns max_projects_per_org +
--    max_repos_per_project). v2.6.1 + V140 had TEAM at 5 / 5.
-- ------------------------------------------------------------------

UPDATE tier_config
   SET max_projects_per_org   = 5,
       max_repos_per_project  = 5
 WHERE tier = 'TEAM';

COMMIT;

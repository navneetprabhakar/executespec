-- =====================================================================
-- V141: Tier consolidation — drop the old TEAM tier, rename MAX → TEAM.
--
-- Decision (2026-04-26): the 5-tier ladder (FREE / PRO / TEAM / MAX /
-- ENTERPRISE) was confusing customers. Consolidating:
--
--   * DELETE the existing TEAM tier ($49/seat, 50 credits/seat
--     shared pool, introduced in v2.6.1 Wave 1 via V122 + V123).
--   * RENAME the existing MAX tier ($29/mo + BYOK, unlimited runs) → TEAM.
--
-- Net post-rename: FREE / PRO / TEAM ($29 BYOK unlimited) / ENTERPRISE.
-- The new "TEAM" is what was called MAX 5 minutes ago. BYOK semantics
-- preserved. PRO grandfathering (HIDDEN) untouched.
--
-- Verified 0 active TEAM subscriptions on the local DB before running
-- this. Defensive UPDATE-before-DELETE pattern still applies — any
-- orphaned TEAM rows on app_user/org get bumped to ENTERPRISE rather
-- than orphaned.
--
-- Idempotent / safe-to-rerun: every statement uses WHERE filters that
-- become no-ops after the first apply.
--
-- NOT covered (out of scope, env-driven):
--   * Stripe / Razorpay plan IDs in production environment variables —
--     ops will refresh STRIPE_MAX_PRICE_ID + Razorpay equivalents
--     out-of-band when the rename hits prod.
-- =====================================================================

BEGIN;

-- ------------------------------------------------------------------
-- 1. DEFENSIVE — rehome any orphan TEAM rows on user/org tables before
--    we drop the tier_config row. Local DB has zero rows but the app
--    runs against shared dev where this could matter.
-- ------------------------------------------------------------------

UPDATE app_user
   SET tier = 'ENTERPRISE'
 WHERE tier = 'TEAM';

UPDATE org
   SET tier = 'ENTERPRISE'
 WHERE tier = 'TEAM';

-- pipeline_run.user_tier (V50) is a snapshot of the user's tier at
-- run time; rewrite for consistency on historical rows.
UPDATE pipeline_run
   SET user_tier = 'ENTERPRISE'
 WHERE user_tier = 'TEAM';

-- agent_task.tier (V15) is the agent-tier rank (LIGHTWEIGHT/WORKHORSE/
-- PLANNING) — NOT the customer tier. Skip.

-- audit_event historical rows (V4 old_tier/new_tier) — leave intact;
-- they're a permanent record of past transitions.

-- ------------------------------------------------------------------
-- 2. DROP the old TEAM billing artefacts (introduced in V123). Order
--    matters — billing_plan_limit FK -> billing_plan, so limits first.
-- ------------------------------------------------------------------

DELETE FROM billing_plan_limit
 WHERE billing_plan_id IN (
        SELECT id FROM billing_plan WHERE tier = 'TEAM'
 );

-- billing_plan_feature is referenced by some installs; defensive
-- delete covers schemas that have it without erroring on schemas that
-- don't.
DO $$
BEGIN
  IF EXISTS (
    SELECT 1 FROM information_schema.tables
     WHERE table_schema = current_schema()
       AND table_name   = 'billing_plan_feature'
  ) THEN
    EXECUTE '
      DELETE FROM billing_plan_feature
       WHERE billing_plan_id IN (
              SELECT id FROM billing_plan WHERE tier = ''TEAM''
       )';
  END IF;
END $$;

DELETE FROM billing_plan
 WHERE tier = 'TEAM';

-- ------------------------------------------------------------------
-- 3. DROP the old TEAM tier_config row.
-- ------------------------------------------------------------------

DELETE FROM tier_config
 WHERE tier = 'TEAM';

-- ------------------------------------------------------------------
-- 4. RENAME MAX → TEAM on tier_config. tier is the PK, so this is a
--    direct UPDATE; FK rows on app_user/org are updated below.
-- ------------------------------------------------------------------

UPDATE tier_config
   SET tier             = 'TEAM',
       display_name     = 'Team',
       description      = COALESCE(description, '') ||
                          CASE
                            WHEN description IS NULL OR description = '' THEN ''
                            ELSE ' '
                          END ||
                          '(renamed from MAX 2026-04-26 — same $29/mo + BYOK + unlimited shape)'
 WHERE tier = 'MAX';

-- ------------------------------------------------------------------
-- 5. RENAME MAX → TEAM on billing_plan rows. Surgical name prefix swap
--    so we don't break unrelated names like "Max Trial".
-- ------------------------------------------------------------------

UPDATE billing_plan
   SET tier = 'TEAM',
       name = CASE
                WHEN name LIKE 'Max %'    THEN 'Team ' || substring(name FROM 5)
                WHEN name = 'Max'         THEN 'Team'
                ELSE name
              END
 WHERE tier = 'MAX';

-- ------------------------------------------------------------------
-- 6. RENAME MAX → TEAM on customer-facing tier columns.
-- ------------------------------------------------------------------

UPDATE app_user
   SET tier = 'TEAM'
 WHERE tier = 'MAX';

UPDATE org
   SET tier = 'TEAM'
 WHERE tier = 'MAX';

UPDATE pipeline_run
   SET user_tier = 'TEAM'
 WHERE user_tier = 'MAX';

-- V129 — startup-program 14-day trial uses trial_tier='MAX'. Rewrite.
UPDATE app_user
   SET trial_tier = 'TEAM'
 WHERE trial_tier = 'MAX';

COMMIT;

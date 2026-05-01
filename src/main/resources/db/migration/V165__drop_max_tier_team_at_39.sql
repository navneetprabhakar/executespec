-- =====================================================================
-- V165: Drop the MAX tier; flatten TEAM to a single $39/seat offering.
--
-- Decision (audit Sprint 4, 2026-05-01):
--   * MAX has been deprecated as a distinct tier — its only differentiator
--     vs TEAM was BYOK + flat price. With BYOK now wired correctly into
--     the V2 router (see commit 2 of this batch), TEAM at $39/seat covers
--     both audiences:
--       - Solo BYOK power users who used to be on MAX
--       - Multi-seat teams who used to be on TEAM ($49 managed)
--   * The $49 managed TEAM plans (V144) are deactivated, not deleted —
--     existing subscribers retain billing continuity. The $39 BYOK TEAM
--     plans (V149) are renamed to be the canonical TEAM monthly offering
--     ("Team Monthly USD" / "Team Monthly INR"). Both managed-key and
--     BYOK customers run at the same $39/seat now; HYBRID keySourceMode
--     remains the default so TEAM customers without their own keys
--     continue to hit platform keys (no breakage).
--   * V146 yearly Team plans at $49 also flip to inactive; new yearly
--     pricing lands in a follow-up if/when product confirms the rate.
--
-- Net post-V165 ladder:
--   FREE / PRO (HIDDEN, grandfathered) / TEAM ($39/seat, BYOK-or-managed)
--   / ENTERPRISE.
--
-- Migration is idempotent (BEGIN/COMMIT, NOT EXISTS / WHERE filters,
-- defensive UPDATEs). Safe to re-run if a partial apply happens.
--
-- Reverse migration sketch (paste into a follow-up V_NNN if ever needed):
--   * Re-INSERT MAX tier_config row (V62 shape) + Max Monthly billing_plans
--   * UPDATE app_user / org / pipeline_run / app_user.trial_tier WHERE
--     tier = 'TEAM' AND was_originally_max = TRUE — but we did NOT
--     persist a was_originally_max marker, so a perfect reverse is not
--     possible. The forward migration is therefore one-way for
--     migration-pinned TEAM rows; new MAX-buyers post-revert would
--     simply pick the new tier.
-- =====================================================================

BEGIN;

-- ------------------------------------------------------------------
-- 1. Remap any user/org/pipeline_run currently on MAX → TEAM. Same
--    semantics as V141 step 4-6 (which originally renamed MAX → TEAM
--    until V144 reversed it). Idempotent: if no MAX rows exist (fresh
--    install), updates are no-ops.
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

-- V129 (startup-program 14-day trial) — MAX trials become TEAM trials.
UPDATE app_user
   SET trial_tier = 'TEAM'
 WHERE trial_tier = 'MAX';

-- ------------------------------------------------------------------
-- 2. Mark the $49 V144 managed-key TEAM monthly plans inactive so
--    they're hidden from /plans listings + can't be picked at
--    checkout. We DO NOT DELETE the rows — existing subscribers'
--    invoice history references them via billing_plan_id FK chains.
-- ------------------------------------------------------------------

UPDATE billing_plan
   SET active = FALSE,
       lifecycle_state = 'SUNSETTING',
       lifecycle_changed_at = now(),
       lifecycle_changed_by = 'V165',
       lifecycle_reason = 'TEAM consolidated to single $39/seat plan; V144 $49 managed-key rows deactivated'
 WHERE active = TRUE
   AND tier = 'TEAM'
   AND key_mode = 'MANAGED'
   AND name IN ('Team Monthly INR', 'Team Monthly USD');

-- V146 yearly Team plans at $49 (also MANAGED key mode) — same fate.
UPDATE billing_plan
   SET active = FALSE,
       lifecycle_state = 'SUNSETTING',
       lifecycle_changed_at = now(),
       lifecycle_changed_by = 'V165',
       lifecycle_reason = 'TEAM yearly $49 deactivated alongside monthly $49; new yearly pricing lands in follow-up'
 WHERE active = TRUE
   AND tier = 'TEAM'
   AND key_mode = 'MANAGED'
   AND period = 'YEARLY';

-- ------------------------------------------------------------------
-- 3. Rename the V149 BYOK TEAM monthly plans so they're the canonical
--    TEAM monthly offering. After this rename, listPlans() returning
--    "Team Monthly USD" gets the $39 BYOK plan (was: $49 managed plan
--    pre-V165). key_mode stays 'BYOK' on the renamed plan; HYBRID-mode
--    orgs with no keys still get platform fallback because that's an
--    org-level setting, not a plan-level enforcement.
--
--    Idempotency: only renames rows where the (BYOK) suffix is still
--    present. Re-running after rename is a no-op.
-- ------------------------------------------------------------------

UPDATE billing_plan
   SET name = REPLACE(name, ' (BYOK)', ''),
       lifecycle_changed_at = now(),
       lifecycle_changed_by = 'V165',
       lifecycle_reason = 'TEAM consolidated — BYOK plan promoted to canonical TEAM monthly'
 WHERE active = TRUE
   AND tier = 'TEAM'
   AND key_mode = 'BYOK'
   AND name LIKE 'Team Monthly % (BYOK)';

-- ------------------------------------------------------------------
-- 4. DELETE all MAX billing_plan_limit rows (FK to billing_plan), then
--    DELETE billing_plan rows where tier='MAX', then DELETE the
--    tier_config.MAX row. Order matters for FK integrity.
-- ------------------------------------------------------------------

DELETE FROM billing_plan_limit
 WHERE billing_plan_id IN (SELECT id FROM billing_plan WHERE tier = 'MAX');

-- billing_plan_feature is referenced by some installs but is not created by
-- any migration in this repo (see V141 for the same pattern). Defensive
-- IF EXISTS check skips the DELETE on schemas that don't have the table.
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
              SELECT id FROM billing_plan WHERE tier = ''MAX''
       )';
  END IF;
END $$;

DELETE FROM billing_plan
 WHERE tier = 'MAX';

DELETE FROM tier_config
 WHERE tier = 'MAX';

-- ------------------------------------------------------------------
-- 5. Set TEAM tier_config.monthly_run_limit = -1 (BYOK-or-managed
--    customers run unlimited at the $39/seat price; per-seat × 50
--    multiplication in TierEnforcementService.effectiveOrgLimit
--    becomes redundant for TEAM but is harmless because effectiveOrgLimit
--    short-circuits on -1 before the multiply. Code cleanup follows
--    in the same commit.).
--
--    Bumped max_org_members 20 → 50 to match the higher seat ceiling
--    customers requested post-V144. Larger orgs can still upsell to
--    ENTERPRISE.
-- ------------------------------------------------------------------

UPDATE tier_config
   SET monthly_run_limit = -1,
       max_org_members = 50,
       description = 'For teams of 2-50 with shared workspace ($39/seat). BYOK or managed keys, unlimited runs against your provider quota.'
 WHERE tier = 'TEAM';

COMMIT;

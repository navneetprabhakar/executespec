-- Wave 1 Day 2 (2026-04-22) — introduce TEAM tier + hide PRO from new buyers.
--
-- TEAM shape (decided 2026-04-22, applied inline defaults):
--   * $49/seat/mo (USD) · ₹3,999/seat/mo (INR)
--   * 50 credits/seat/mo, shared org-level pool
--   * Min 2 seats, max 20 (runtime-enforced in BillingService)
--   * Monthly only for v1 (Yearly deferred per team-tier-plan.md §11)
--   * Google OIDC login available (enforcement in Wave 3)
--   * 5 projects · 5 repos per project (enforced via tier_config)
--   * 180-day retention
--   * No deploy (deploy stays at MAX+)
--
-- PRO moves to HIDDEN in the same migration so the TEAM launch transition
-- is atomic — a fresh PlansPage load on deploy day shows FREE / TEAM / MAX /
-- ENTERPRISE with no PRO. Existing PRO subscribers continue renewing
-- untouched (gateway has no state change here).
--
-- See docs/plans/team-tier-plan.md §2a + §4.5 for the shipping context and
-- docs/plans/tier-lifecycle-plan.md for the lifecycle rationale.

-- 1. Add TEAM tier_config row
INSERT INTO tier_config (
    tier, display_name, monthly_run_limit, is_fixed, description,
    max_org_members, max_chat_messages, concurrent_run_limit, max_apply_suggestions,
    max_preview_starts, max_run_auto_retries, can_deploy, max_deployments,
    max_run_cost_usd, retention_days, execution_view_enabled,
    lifecycle_state, lifecycle_changed_at, lifecycle_changed_by, lifecycle_reason
) VALUES (
    'TEAM', 'Team', 50, FALSE,
    'For engineering teams of 2-20 with shared credit pool (50 credits/seat/mo). TEAM tier_config.monthly_run_limit is per-seat; the effective limit is seats × 50 enforced in TierEnforcementService.',
    20, 10, 5, -1,
    -1, 2, FALSE, 0,
    10.0, 180, FALSE,
    'LISTED', now(), 'system-migration', 'TEAM tier launch (v2.6.0 Wave 1)'
)
ON CONFLICT (tier) DO NOTHING;

-- 2. Hide PRO tier from new buyers (grandfathering — existing subscribers
--    unaffected, as enforced by TierLifecycleService + billing filter).
UPDATE tier_config
   SET lifecycle_state = 'HIDDEN',
       lifecycle_changed_at = now(),
       lifecycle_changed_by = 'system-migration',
       lifecycle_reason = 'TEAM launch (v2.6.0) — PRO grandfathered, existing subscribers continue renewing. Flip to LISTED via /admin/tiers if needed.'
 WHERE tier = 'PRO';

-- 3. Hide every PRO billing_plan row so /api/billing/plans no longer returns
--    them for new buyers. Existing BillingSubscription rows referencing these
--    plans continue to function — lifecycle_state is read only on list /
--    subscribe paths, not on renewal paths.
UPDATE billing_plan
   SET lifecycle_state = 'HIDDEN',
       lifecycle_changed_at = now(),
       lifecycle_changed_by = 'system-migration',
       lifecycle_reason = 'TEAM launch (v2.6.0) — PRO grandfathered'
 WHERE tier = 'PRO'
   AND active = TRUE;

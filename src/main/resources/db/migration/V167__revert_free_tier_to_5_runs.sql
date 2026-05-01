-- =====================================================================
-- V167: Hold FREE tier at 5 runs / month — revert V145 + V166 alignment.
--
-- 2026-05-04 positioning decision: "free tier will always remain 5".
-- V145 bumped tier_config.FREE.monthly_run_limit 5 → 10 (matching
-- emergent.sh) and V166 mirrored that into billing_plan + billing_plan_limit.
-- Both are reverted here so the FREE allowance is held at 5 runs / month
-- across every authoritative table — `tier_config` (read by
-- `EntitlementResolver` on the v3 surface), `billing_plan.run_limit`
-- (read by `BillingService` for the legacy plans surface), and
-- `billing_plan_limit.max_value WHERE metric='RUNS'` (cross-referenced
-- by the V2 UsageBadge / BillingPage hero / pricing card).
--
-- Customer-facing copy in `website/index.html`, `website/llms.txt`,
-- and `frontend/src/v3/pages/V3EmptyProject.tsx` already says "5 runs
-- / month, no credit card" — this migration brings the DB into agreement
-- with that copy.
--
-- Idempotent: only updates rows whose limit still matches the
-- post-V145 "10". Re-running after the first apply is a no-op. Schema
-- mirrors V166 (which set the 10 in the first place); the `metric`
-- column + `billing_plan_id` join are the canonical shape on
-- `billing_plan_limit`.
-- =====================================================================

UPDATE tier_config
   SET monthly_run_limit = 5,
       description       = 'Try ExecuteSpec free — 5 runs/month, no credit card required. Verified 0-100 score on every PR.'
 WHERE tier = 'FREE'
   AND monthly_run_limit = 10;

UPDATE billing_plan
   SET run_limit = 5
 WHERE tier = 'FREE'
   AND run_limit = 10;

UPDATE billing_plan_limit
   SET max_value = 5
  FROM billing_plan
 WHERE billing_plan_limit.billing_plan_id = billing_plan.id
   AND billing_plan.tier = 'FREE'
   AND billing_plan_limit.metric = 'RUNS'
   AND billing_plan_limit.max_value = 10;

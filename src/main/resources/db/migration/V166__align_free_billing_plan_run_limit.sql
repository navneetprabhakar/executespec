-- =====================================================================
-- V166: Align billing_plan.run_limit for FREE with the canonical
--       tier_config.monthly_run_limit set by V145.
--
-- Background: Task 3 of the 2026-05-01 fix list. V145 ("loosen FREE
-- tier") raised tier_config.FREE.monthly_run_limit from 5 → 10 but
-- did not touch the corresponding billing_plan.FREE.run_limit row.
-- The frontend's UsageBadge / BillingPage hero / pricing card all
-- read `usage[].max` which is computed from billing_plan.run_limit,
-- so users saw "0/5 runs" in three places while the backend
-- permission check used "10". V145's intent was 10/month — this
-- migration finishes the job.
--
-- Going forward, tier_config.monthly_run_limit is the single source
-- of truth (see frontend/src/v3/lib/tierDisplay.ts and the audit
-- doc). billing_plan.run_limit stays in sync as a denormalised
-- mirror so subscription-picker UIs that already read it keep
-- working without a bigger refactor.
--
-- Idempotent: only updates rows whose run_limit still matches the
-- legacy "5". Re-running after the first apply is a no-op.
-- =====================================================================

UPDATE billing_plan
   SET run_limit = 10
 WHERE tier = 'FREE'
   AND run_limit = 5;

-- billing_plan_limit is what /api/billing/status reads to populate
-- usage[].max — the V2 UsageBadge in the top-bar pulls from there.
-- Update the RUNS metric for the FREE plan in lockstep so the badge,
-- billing hero, and pricing card all show the same number (10).
UPDATE billing_plan_limit
   SET max_value = 10
  FROM billing_plan
 WHERE billing_plan_limit.billing_plan_id = billing_plan.id
   AND billing_plan.tier = 'FREE'
   AND billing_plan_limit.metric = 'RUNS'
   AND billing_plan_limit.max_value = 5;

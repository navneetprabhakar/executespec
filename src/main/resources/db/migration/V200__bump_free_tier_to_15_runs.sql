-- =====================================================================
-- V200: Bump FREE tier 5 → 15 runs / month.
--
-- 2026-05-19 positioning revisit: 5-runs-lifetime was so thin that
-- evaluation users hit the wall before forming an opinion. 15 runs gives
-- a developer 2-3 honest sessions in a month — enough to discover the
-- plan-gate, ship a real PR, and decide whether the verified-score story
-- is worth $29/mo on PRO. Cost cap stays at $3/run so the worst case
-- platform exposure on a heavy month is 15 × $3 = $45/user.
--
-- Reverses V167. Updates all three authoritative tables in one shot:
--   - tier_config.monthly_run_limit (canonical, read by EntitlementResolver
--     since the 2026-05-19 quota fix in UsageTrackingService)
--   - billing_plan.run_limit (legacy plans surface)
--   - billing_plan_limit.max_value WHERE metric='RUNS' (legacy V2 path)
--
-- Idempotent: only rewrites rows still at 5. Re-running is a no-op.
-- =====================================================================

UPDATE tier_config
   SET monthly_run_limit = 15,
       description       = 'Try ExecuteSpec free — 15 runs/month, no credit card required. Verified 0-100 score on every PR.'
 WHERE tier = 'FREE'
   AND monthly_run_limit = 5;

UPDATE billing_plan
   SET run_limit = 15
 WHERE tier = 'FREE'
   AND run_limit = 5;

UPDATE billing_plan_limit
   SET max_value = 15
  FROM billing_plan
 WHERE billing_plan_limit.billing_plan_id = billing_plan.id
   AND billing_plan.tier = 'FREE'
   AND billing_plan_limit.metric = 'RUNS'
   AND billing_plan_limit.max_value = 5;

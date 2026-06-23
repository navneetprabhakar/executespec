-- =====================================================================
-- V248: Force FREE tier run limit to 5 unconditionally (sentinel fix).
--
-- Bug (found via live CLI QA, 2026-06-22): the v3 entitlements endpoint
-- surfaced the FREE tier as UNLIMITED. `EntitlementResolver` computes
-- `isUnlimited = (monthly_run_limit == -1)`, and this DB's
-- `tier_config.FREE.monthly_run_limit` was -1 (the "unlimited sentinel"),
-- not the intended 5.
--
-- Root cause: V145 bumped FREE 5 -> 10 and V167 reverted FREE 10 -> 5,
-- but BOTH guard on the prior value (`WHERE monthly_run_limit = 10`,
-- resp. `= 5`). A DB whose FREE row sat at any OTHER value (here: -1,
-- left by an early local seed) was therefore never corrected by either
-- migration and kept leaking "unlimited" on the v3 surface.
--
-- The 2026-05-04 positioning decision is "free tier will always remain
-- 5 runs/month" (see V167). This migration brings the authoritative
-- tables into agreement UNCONDITIONALLY so the sentinel cannot persist.
--
-- TEAM.monthly_run_limit = -1 is intentional and left untouched (V165:
-- per-seat shared pool, effective limit = seats x 50 enforced in
-- TierEnforcementService). ENTERPRISE = -1 (V140) is correct unlimited.
--
-- Idempotent: each statement is a no-op once the FREE rows are at 5.
-- =====================================================================

UPDATE tier_config
   SET monthly_run_limit = 5
 WHERE tier = 'FREE'
   AND monthly_run_limit <> 5;

UPDATE billing_plan
   SET run_limit = 5
 WHERE tier = 'FREE'
   AND run_limit <> 5;

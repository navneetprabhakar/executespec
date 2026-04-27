-- =====================================================================
-- V145: Loosen FREE tier from 5 → 10 credits/month (Phase 1.3 #33).
--
-- Plan rationale: matches emergent.sh's free tier — biggest top-of-funnel
-- competitor. 10 credits/month with no card required removes the friction
-- of "I have to put my credit card down to try" and lets us measure
-- activation honestly (do users come back after a week of free use?).
--
-- The credit cost-per-run model means 10 credits ≈ 10 LOW runs, 5 MEDIUM
-- runs, or 2-3 HIGH runs — enough for a meaningful trial without hitting
-- the limit on day one.
--
-- Also tweaks the description to remove the "1-credit specs only" copy
-- since the looser allowance no longer needs that gate.
--
-- Migration number: V145, picked as next free slot after V144 — user
-- has the resequence call when this batch lands.
-- =====================================================================

BEGIN;

UPDATE tier_config
   SET monthly_run_limit = 10,
       description       = 'Try ExecuteSpec free — 10 credits/month, no credit card required. About 10 low-complexity runs or 5 medium runs.'
 WHERE tier = 'FREE';

COMMIT;

-- =====================================================================
-- V214: Seed scoring.gaming-guards = true (DEFAULT ON).
--
-- RL-2026-05-041 (audit risk #7 — "verified-score vouches for AI"). The
-- additive verifiedScore could be gamed: an agent that writes trivial
-- always-passing tests + a clean build scored high without delivering the
-- spec's intent. Founder-approved hardening A+B+E, applied in
-- ValidationReportService.computeVerifiedScoreAdditive when this flag is ON:
--
--   (A) CODE_DELIVERED is linked to DETERMINISTIC endpoint-coverage intent —
--       files-written alone no longer ⇒ 100 when the spec's endpoints aren't
--       actually exposed (blend: half "code exists", half "intent delivered").
--   (B+E) the TESTS slot blends the real pass-rate with the coverage-QUALITY
--       judgment — NONE coverage caps the slot at 30, PARTIAL at 70 — so
--       trivial always-passing tests can't score the slot at 100.
--
-- The guards only ever TIGHTEN a gameable score, never inflate, so they are
-- safe-direction: a clean run (endpoints covered + GOOD/COMPREHENSIVE tests)
-- is unaffected. Default ON per founder approval; reversible via:
--   PUT /api/feature-flags/scoring.gaming-guards
-- Existing rows are NOT recomputed (score is set at generate-and-persist time).
-- =====================================================================

INSERT INTO feature_flag (flag_key, flag_value, value_type, scope, description)
VALUES (
    'scoring.gaming-guards',
    'true',
    'BOOLEAN',
    'GLOBAL',
    'Anti-gaming guards on the additive verifiedScore (RL-2026-05-041): link CODE_DELIVERED to deterministic endpoint-coverage intent + blend the TESTS slot with coverage quality so trivial passing tests cannot score 100. Tighten-only, safe-direction. Default ON.'
)
ON CONFLICT (flag_key) DO UPDATE
    SET flag_value = EXCLUDED.flag_value,
        description = EXCLUDED.description;

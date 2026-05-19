-- =====================================================================
-- V202: Seed reviewer.third-pass.enabled = true.
--
-- 2026-05-19 — Phase 2 of the auto-fix work. When ReviewerAgent finishes
-- general + wiring + post-edit-formatter but the final buildProject smoke
-- is still red, run ONE more targeted pass with the build error verbatim
-- as the user message. The pass uses the reviewer-general prompt, 25-iter
-- budget, 360s wall-clock cap. Rescues the class of run that's one typo
-- away from CLEAN (e.g. stray import, wrong type, missing decorator).
--
-- Empirical motivation: audit on 2026-05-19 — 4 of admin's 30 "complete"
-- runs had passing reviewer pass but red final build, landing in
-- UNRESOLVED_ISSUES. With the new additive scorer:
--   UNRESOLVED_ISSUES → REVIEWER slot 40/100  (8 headline pts)
--   FIXED_ISSUES      → REVIEWER slot 85/100  (17 headline pts)
-- So a successful third pass adds ~9 pts to the headline on this class
-- of run.
--
-- Default ON because the worst case is "spend an extra ~5 min of LLM
-- time without improving the verdict" — the third pass cannot make the
-- final build redder than it already is (we always re-run buildProject
-- after and only commit the post-third-pass state if it's no worse).
-- Flip off via FeatureFlagService at runtime to A/B per provider.
-- =====================================================================

INSERT INTO feature_flag (flag_key, flag_value, value_type, scope, description)
VALUES (
    'reviewer.third-pass.enabled',
    'true',
    'BOOLEAN',
    'GLOBAL',
    'After general+wiring, if buildProject is still red, run one targeted "fix the build" pass with the build error as input (25-iter cap, 360s timeout). Rescues runs that are one typo away from CLEAN.'
)
ON CONFLICT (flag_key) DO UPDATE
    SET flag_value = EXCLUDED.flag_value,
        description = EXCLUDED.description;

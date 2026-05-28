-- =====================================================================
-- V220: Seed worker.cross-provider-fallback.enabled = false (DEFAULT OFF).
--
-- RL-2026-05-052 (T3.9) — multi-provider fallback on persistent task fail.
-- When the existing same-provider retry (build-fail injection) still fails,
-- the executor tries ONE more time on the next provider in the YAML
-- failover-order chain. Hard ceilings to bound cost + accuracy hit:
--
--   - Per task: max ONE cross-provider retry (this triggers after the
--     existing same-provider retry has failed, so a task already burned
--     primary + same-provider-retry budget; one more on a different
--     provider is the cap).
--   - Per run: max 3 tasks may trigger cross-provider retry
--     (MAX_CROSS_PROVIDER_PER_RUN constant in SequentialExecutor) —
--     prevents a pathologically bad run from cascading cross-provider
--     retries on every task and 3×-ing run cost.
--   - The next provider must be DIFFERENT from the primary AND must
--     resolve to a real model. If adaptive routing or strict-provider
--     re-resolves back to the primary, the retry is abandoned.
--
-- Empirical motivation: the 2026-05-28 mixed10 5-arm eval showed
-- different providers solve DIFFERENT cases (only Qwen solved
-- matplotlib-23563; only OpenAI solved django-11742). A
-- failure-driven cross-provider retry would have netted +2 RESOLVED
-- on the existing 50 trials with no model improvements.
--
-- Concern (founder, 2026-05-28): accuracy may take a hit if the
-- alternate provider produces a wrong but plausible patch that passes
-- the smoke gate. The hard ceiling + smoke-test gate are the bound.
-- Default OFF until eval data justifies flipping.
-- =====================================================================

INSERT INTO feature_flag (flag_key, flag_value, value_type, scope, description)
VALUES (
    'worker.cross-provider-fallback.enabled',
    'false',
    'BOOLEAN',
    'GLOBAL',
    'Multi-provider fallback on persistent task fail (RL-2026-05-052, T3.9). Max 1 per task, max 3 per run, must hit a different provider. Default OFF.'
)
ON CONFLICT (flag_key) DO UPDATE
    SET flag_value = EXCLUDED.flag_value,
        description = EXCLUDED.description;

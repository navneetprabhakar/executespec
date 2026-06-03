-- =====================================================================
-- V226: Seed router.failover.enabled = false (DEFAULT OFF).
--
-- RL-2026-05-062 — closes audit M #68 (the long-standing LlmRouter gap:
-- a single transient 5xx that survived the same-provider bounded retry
-- hard-failed the V2 pipeline call, with no failover — only the legacy
-- FallbackLlmRouter had multi-provider failover).
--
-- When enabled, LlmRouter.maybeFailover() runs AFTER the same-provider
-- transient retry is exhausted: it walks the primary model's YAML
-- failover chain (ModelRegistry.fallbackChainFor) and tries each
-- DIFFERENT provider once (each with its own same-provider transient
-- retry). First success returns; all-exhausted rethrows the primary error.
--
-- BYOK-safe (the discussed mechanism):
--   - A run already using a CUSTOMER key for the primary provider only
--     fails over to a provider the customer ALSO keyed — it never
--     silently crosses onto a platform key for a BYOK run.
--   - Platform-key runs (FREE/PRO on system keys) may fail over freely.
--   - deepseek / openrouter are NEVER failover targets (enterprise BYOK,
--     chosen on purpose for cost / data-residency; silent failover would
--     defeat that — same rationale they're excluded from failover-order).
--
-- Default OFF so production is byte-identical until validated; this is a
-- platform-wide reliability lever (no per-org knob today).
-- =====================================================================

INSERT INTO feature_flag (flag_key, flag_value, value_type, scope, description)
VALUES (
    'router.failover.enabled',
    'false',
    'BOOLEAN',
    'GLOBAL',
    'LlmRouter multi-provider failover after same-provider transient retry is exhausted (RL-2026-05-062, closes audit M#68). BYOK-safe; deepseek/openrouter excluded. Default OFF.'
)
ON CONFLICT (flag_key) DO UPDATE
    SET flag_value = EXCLUDED.flag_value,
        description = EXCLUDED.description;

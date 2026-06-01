-- =====================================================================
-- V225: Seed gemini.explicit-cache.enabled = false (DEFAULT OFF).
--
-- RL-2026-05-059 — the 2026-05-31 50-instance eval measured Gemini's per-call
-- prompt-cache hit-rate at 45.9%, versus 88-97% for Anthropic / OpenAI /
-- DeepSeek. Gemini is the only provider on IMPLICIT (best-effort) caching;
-- the others use explicit cache_control or near-deterministic automatic prefix
-- caching. The 54% that misses re-bills at the full Flash input rate, driving
-- 668K input-tokens/run and $33.98 total (the most expensive provider, 28x
-- DeepSeek's $1.21 for the same 50 tasks).
--
-- This flag turns on EXPLICIT context caching in GeminiNativeToolClient: the
-- worker's [systemInstruction + tools] prefix (byte-identical across every turn
-- of a task, and across tasks sharing the same model + tool set) is minted once
-- as a /v1beta/cachedContents resource and referenced by name on each
-- generateContent call, with the cached parts omitted from the request body so
-- they bill at the cache rate. This is the "Phase 2" the 2026-05-04 billing-
-- audit comment in GeminiNativeToolClient flagged as unimplemented.
--
-- Fail-safe by construction: minting failure (e.g. a prefix below the per-model
-- minimum cacheable token count) OR a stale-cache 4xx on generateContent both
-- fall back to today's inline body — so flipping this ON can only reduce cost,
-- never break a Gemini run. When OFF, behaviour is byte-identical to the
-- pre-existing implicit-only path.
--
-- Default OFF (convention; same additive/flag-gated rollout as V216/V217/V219-
-- V222/V224); enabled on dev 2026-05-31 per founder approval, pending the
-- post-flip cache-hit re-measurement before promotion.
-- =====================================================================

INSERT INTO feature_flag (flag_key, flag_value, value_type, scope, description)
VALUES (
    'gemini.explicit-cache.enabled',
    'false',
    'BOOLEAN',
    'GLOBAL',
    'GeminiNativeToolClient mints an explicit /v1beta/cachedContents resource for the stable system+tools prefix and references it per call, lifting Gemini cache-hit from ~46% toward ~90% (RL-2026-05-059). Fail-safe: any mint/stale-cache error falls back inline. Default OFF.'
)
ON CONFLICT (flag_key) DO NOTHING;

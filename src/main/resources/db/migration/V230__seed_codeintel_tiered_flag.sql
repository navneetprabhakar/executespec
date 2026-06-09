-- =====================================================================
-- V229: Seed codeintel.tiered.enabled = false (DEFAULT OFF).
--
-- Phase 1 of tiered codemap injection (RL-2026-06-003). When ON,
-- RunContextComposer.buildCodeIntelContext replaces the HEAD truncation of
-- the per-repo codemap with TieredCodemapSelector: a T0 skeleton (title +
-- top-level layout + routes/models) plus the per-file ## Functions symbol
-- blocks SELECTED by lexical relevance to the run's spec, dropping the flat
-- file listing. On large repos the old head-truncate cut the symbol map
-- (the localization signal) 100%; tiered selection injects the relevant
-- symbols within the same budget.
--
-- Pure + deterministic (no extra LLM). Fail-safe: the selector returns null
-- (caller head-truncates) when the codemap is unparseable or the spec is
-- blank, so flag-OFF and fail paths are byte-identical to today.
--
-- DEFAULT OFF: ship dark, A/B against the current paths-only injection
-- before flipping. Flip via:
--   PUT /api/admin/feature-flags/codeintel.tiered.enabled
-- =====================================================================

INSERT INTO feature_flag (flag_key, flag_value, value_type, scope, description)
VALUES (
    'codeintel.tiered.enabled',
    'false',
    'BOOLEAN',
    'GLOBAL',
    'Tiered codemap injection (Phase 1): T0 skeleton + spec-relevant per-file symbol blocks instead of a head truncation that drops the ## Functions symbol map on large repos. Deterministic, fail-safe to head-truncate. Default OFF pending A/B.'
)
ON CONFLICT (flag_key) DO UPDATE
    SET flag_value = EXCLUDED.flag_value,
        description = EXCLUDED.description;

-- =====================================================================
-- V210: Seed codeintel.inject.enabled = false (DEFAULT OFF).
--
-- RL-2026-05-035 — "consumption is the liability" fix. The codemap /
-- architecture pipeline generated a per-repo symbol map but never fed it
-- to the agents that write code. When this flag is ON, RunOrchestrator
-- injects a HARD-BOUNDED code-intel block (per-repo codemap symbol maps,
-- capped at ~12k chars total / 6k per repo) into:
--   (a) the PLANNER context (project BUILD runs), and
--   (b) the PATCH agent's user prompt (project autofix surfaces) — the
--       PATCH-learning localization win.
-- Greenfield runs and blind bench runs (no projectId / no codemap) are a
-- no-op (fail-safe). Language-neutral (the codemap is built per repo
-- regardless of stack).
--
-- DEFAULT OFF: injection adds prompt tokens (cost) and the codemap is
-- name-level (not line-level), so it must clear scripts/v2-eval.sh
-- (no-regression) + a project-autofix cost check before being flipped ON.
-- Flip via: PUT /api/feature-flags/codeintel.inject.enabled
-- =====================================================================

INSERT INTO feature_flag (flag_key, flag_value, value_type, scope, description)
VALUES (
    'codeintel.inject.enabled',
    'false',
    'BOOLEAN',
    'GLOBAL',
    'Inject the generated per-repo codemap symbol map into the planner (project BUILD) + PATCH agent (project autofix) for localization. Bounded ~12k chars, fail-safe on greenfield/bench. Default OFF pending v2-eval + cost check (RL-2026-05-035).'
)
ON CONFLICT (flag_key) DO UPDATE
    SET flag_value = EXCLUDED.flag_value,
        description = EXCLUDED.description;

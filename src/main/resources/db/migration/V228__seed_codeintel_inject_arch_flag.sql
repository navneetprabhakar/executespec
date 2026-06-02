-- =====================================================================
-- V228: Seed codeintel.inject-arch.enabled = false (DEFAULT OFF).
--
-- Companion to V210 (`codeintel.inject.enabled`, the codemap symbol map).
-- When ON, RunOrchestrator injects the project's combined cross-repo
-- ARCHITECTURE doc (high-level HLD/LLD narrative) into the PLANNER context
-- alongside the codemap — so the planner understands HOW components relate
-- (arch) in addition to WHERE symbols live (codemap). Bounded ~8k chars,
-- best-effort. Greenfield runs and blind bench runs (no projectId / no
-- arch doc) are a no-op (fail-safe).
--
-- Separate from V210 so the codemap and the architecture narrative can be
-- toggled — and their lift attributed — independently.
--
-- DEFAULT OFF: injection adds planner prompt tokens (cost) and must clear a
-- no-regression + cost check before being flipped ON in prod.
-- Flip via: PUT /api/admin/feature-flags/codeintel.inject-arch.enabled
-- =====================================================================

INSERT INTO feature_flag (flag_key, flag_value, value_type, scope, description)
VALUES (
    'codeintel.inject-arch.enabled',
    'false',
    'BOOLEAN',
    'GLOBAL',
    'Inject the project combined architecture doc (cross-repo HLD/LLD) into the planner alongside the codemap (companion to codeintel.inject.enabled). Bounded ~8k chars, fail-safe on greenfield/bench. Default OFF pending no-regression + cost check.'
)
ON CONFLICT (flag_key) DO UPDATE
    SET flag_value = EXCLUDED.flag_value,
        description = EXCLUDED.description;

-- =====================================================================
-- V213: Seed contracts.breaking-change.enabled = false (DEFAULT OFF).
--
-- RL-2026-05-037 — completes the "enterprise features are decorative" fix.
-- The breaking-change classifier (contracts/gate/PlanBreakingChangeReport +
-- contracts/breaking/BreakingChangeService) was fully built + unit-tested
-- but had ZERO production callers, so a run could break a cross-repo contract
-- consumed by another repo and nothing surfaced it.
--
-- When this flag is ON, RunOrchestrator (post-reviewer, project runs only)
-- detects contracts the run touched — previous content from the latest
-- ContractVersion.inline_content, new content read from the workspace disk
-- at the contract's detected path (per-repo subdir for multi-repo runs) —
-- classifies each via the deterministic breaking-change engine, and surfaces
-- any breaking change as a report-only (MINOR) issue in the run's validation
-- report Issues panel (contracts WITH declared consumers also carry a
-- [REQUIRES ACK] marker). Report-only by design: it does not trigger auto-fix
-- or penalise the score. Non-fatal; greenfield / no-contract runs are a no-op.
--
-- DEFAULT OFF: flip ON after a no-regression check. Flip via:
--   PUT /api/feature-flags/contracts.breaking-change.enabled
-- =====================================================================

INSERT INTO feature_flag (flag_key, flag_value, value_type, scope, description)
VALUES (
    'contracts.breaking-change.enabled',
    'false',
    'BOOLEAN',
    'GLOBAL',
    'Classify breaking changes to touched contracts on project runs (prev=ContractVersion.inline_content, new=workspace disk) and surface them as report-only run issues (RL-2026-05-037). Project-gated, non-fatal. Default OFF.'
)
ON CONFLICT (flag_key) DO UPDATE
    SET flag_value = EXCLUDED.flag_value,
        description = EXCLUDED.description;

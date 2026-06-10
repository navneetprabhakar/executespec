-- =====================================================================
-- V211: Seed enterprise.inject.enabled = false (DEFAULT OFF).
--
-- RL-2026-05-035 — "enterprise features are decorative" fix. The contract
-- and ADR injection services (contracts/injection/ContractInjectionService,
-- adr/AdrInjectionService) were fully built + unit-tested but had ZERO
-- production callers, so cross-repo contracts and architecture decisions
-- never reached the planner — defining them changed nothing the AI did.
--
-- When this flag is ON, RunOrchestrator builds the {{CONTRACTS}} and
-- {{PROJECT_ADRS}} blocks for the project and feeds them into the planner
-- prompt (OutlinePlannerAgent), each bounded to ~8k chars and best-effort
-- (a failure in one is swallowed to ""). Greenfield / no-project runs are
-- a no-op (fail-safe).
--
-- NOTE: this wires the *planner injection* only. The standards enforcement
-- engine (StandardsRunner) and the breaking-change plan gate remain
-- unwired — a separate, larger change (see docs/reviews/project-features-
-- audit-2026-05-25.md).
--
-- DEFAULT OFF: injection adds planner prompt tokens (cost) and is only
-- useful once a project actually has contracts/ADRs defined; flip ON after
-- a no-regression check. Flip via: PUT /api/feature-flags/enterprise.inject.enabled
-- =====================================================================

INSERT INTO feature_flag (flag_key, flag_value, value_type, scope, description)
VALUES (
    'enterprise.inject.enabled',
    'false',
    'BOOLEAN',
    'GLOBAL',
    'Inject {{CONTRACTS}} + {{PROJECT_ADRS}} into the planner prompt so cross-repo contracts and ADRs actually steer the AI (RL-2026-05-035). Bounded ~8k each, fail-safe on greenfield. Default OFF. Standards-runner + breaking-change gate remain unwired (separate change).'
)
ON CONFLICT (flag_key) DO UPDATE
    SET flag_value = EXCLUDED.flag_value,
        description = EXCLUDED.description;

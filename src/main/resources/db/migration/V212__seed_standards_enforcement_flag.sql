-- =====================================================================
-- V212: Seed standards.enforcement.enabled = false (DEFAULT OFF).
--
-- RL-2026-05-037 — completes the "enterprise features are decorative" fix
-- (V211 wired contract/ADR planner injection; this wires the standards
-- enforcement engine). StandardsRunner (standards/StandardsRunner.java) was
-- fully built + unit-tested but had ZERO production callers, so the
-- /standards/report endpoint always returned an empty violation list —
-- defining org/project coding standards changed nothing and blocked nothing.
--
-- When this flag is ON, RunOrchestrator resolves the project's effective
-- standards (EffectiveStandardsResolver: org + project merge) after the
-- reviewer pass, runs the deterministic lint / file-pattern / AST checks
-- against the final workspace, and persists each violation to
-- standards_violation so the compliance report reflects reality. Bounded
-- (<=6k files walked, <=200 rows/run), project-gated, non-fatal. Greenfield /
-- no-project runs and orgs with no standards defined are a no-op (fail-safe).
--
-- DEFAULT OFF: flip ON after a no-regression check. Flip via:
--   PUT /api/feature-flags/standards.enforcement.enabled
-- =====================================================================

INSERT INTO feature_flag (flag_key, flag_value, value_type, scope, description)
VALUES (
    'standards.enforcement.enabled',
    'false',
    'BOOLEAN',
    'GLOBAL',
    'Run deterministic standards (lint/file-pattern/AST) against the final workspace on project runs and persist violations to standards_violation so /standards/report is real, not empty (RL-2026-05-037). Bounded, project-gated, non-fatal. Default OFF.'
)
ON CONFLICT (flag_key) DO UPDATE
    SET flag_value = EXCLUDED.flag_value,
        description = EXCLUDED.description;

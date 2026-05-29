-- =====================================================================
-- V217: Seed worker.build-tdd.enabled = false (DEFAULT OFF).
--
-- RL-2026-05-052 — BUILD-mode test-driven-iteration lever (mixed10 follow-up).
-- Exposes a new worker tool {@code runWorkspaceTests} that runs the project's
-- test suite using the framework {@code TestCommandResolver} detected (pytest /
-- mvn / gradle / npm / cargo / go). Wraps the existing WorkspaceTestRunner
-- — same resolver-derived command, same ShellSandboxPolicy validation, same
-- cleanroom-container routing (SWE-bench / ProgramBench dispatch when one
-- exists). Differs from the pytest-only {@code runTests} (used in PATCH mode
-- with known failing-test ids) by running the whole suite, no test-id arg.
--
-- When OFF (this seed row): runWorkspaceTests is still in the registry but
-- returns an informative envelope {available: false} so the worker can learn
-- the tool isn't usable on this run rather than getting a hard error. The
-- existing post-completion scoring-phase test execution
-- ({@code scoring.real-test-exec}, V203 default ON) is unchanged.
--
-- When ON: BUILD-mode worker can call runWorkspaceTests after writing tests
-- to validate them before {@code completeTask}, iterating on failures.
-- Expected lift on SWE-bench-like sets: +8-12pp (see TRANCHE 3 of the
-- 2026-05-28 quality-lever roadmap). Flip per-run during eval:
--   PUT /api/feature-flags/worker.build-tdd.enabled
--
-- Default OFF so production runs are byte-identical until eval data
-- justifies the change. Same rollout discipline as V216 (compactor).
-- =====================================================================

INSERT INTO feature_flag (flag_key, flag_value, value_type, scope, description)
VALUES (
    'worker.build-tdd.enabled',
    'false',
    'BOOLEAN',
    'GLOBAL',
    'BUILD-mode TDD-iteration (RL-2026-05-052): exposes the runWorkspaceTests tool so the worker can run its own tests in-loop before completeTask. Default OFF.'
)
ON CONFLICT (flag_key) DO UPDATE
    SET flag_value = EXCLUDED.flag_value,
        description = EXCLUDED.description;

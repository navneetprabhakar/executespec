-- =====================================================================
-- V209: Seed build.test-exec-gate.enabled = false (DEFAULT OFF).
--
-- RL-2026-05-034 — Transfer #1 (PATCH→BUILD). Today the BUILD per-task
-- smoke gate only COMPILES (BuildProjectTool); SYSTEM_PROMPT_BASE makes
-- tests mandatory to WRITE, but nothing RUNS them in-loop, so a worker can
-- ship tests that don't actually pass. When this flag is ON,
-- SequentialExecutor.runSmokeTest runs the worker's tests after a clean
-- compile (smoketest.WorkspaceTestRunner, language-neutral via
-- TestCommandResolver); a real test failure flips the smoke result to
-- FAILED and reuses the existing build-fail retry path (re-invokes the
-- worker with the failures injected). This is the in-loop analog of the
-- PATCH reproduce-first loop that lifted the model 5-17x on SWE-bench.
--
-- Fail-safe by construction: no recognized runner / empty summary / any
-- runner error -> the gate is skipped and behavior is identical to today
-- (compile-only). So worst case = "no change."
--
-- DEFAULT OFF: it adds a per-final-task test run (latency) and can turn a
-- previously-"green" run PARTIAL when its tests genuinely fail, so it must
-- clear scripts/v2-eval.sh (greenfield, multi-language) no-regression
-- before being flipped ON. Flip via: PUT /api/feature-flags/build.test-exec-gate.enabled
-- =====================================================================

INSERT INTO feature_flag (flag_key, flag_value, value_type, scope, description)
VALUES (
    'build.test-exec-gate.enabled',
    'false',
    'BOOLEAN',
    'GLOBAL',
    'BUILD per-task smoke: after a clean compile, RUN the worker tests in-loop (WorkspaceTestRunner); a real failure reuses the build-fail retry. Verify-before-done, language-neutral, fail-safe. Default OFF pending v2-eval no-regression (RL-2026-05-034).'
)
ON CONFLICT (flag_key) DO UPDATE
    SET flag_value = EXCLUDED.flag_value,
        description = EXCLUDED.description;

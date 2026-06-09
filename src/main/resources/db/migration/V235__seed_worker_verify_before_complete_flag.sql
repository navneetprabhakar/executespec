-- =====================================================================
-- V235: Seed worker.verify-before-complete.enabled = false (DEFAULT OFF).
--
-- VBC verify-before-complete (RL-2026-06-…) — the generic, server-enforced
-- "definition of done" gate. At the worker's FIRST completeTask, the platform
-- RUNS the project's own test suite (via smoketest/WorkspaceTestRunner) and,
-- if it is RED, refuses completion and feeds the failure back so the worker
-- fixes it before the run ends. Bounded (WorkerAgent.VBC_GATE_MAX_REFUSALS=3,
-- then YIELD) + fail-open (no resolvable runner / a remote run / any error =>
-- accept) so it can nudge a fix but can NEVER convert a legitimately-complete
-- run into a false FAIL.
--
-- Distinct from the existing requireTestVerification / requireBuildVerification
-- SOFT gates: those only check whether the MODEL recently called runTests /
-- buildProject. VBC RUNS the tests itself, server-side — so a worker that calls
-- completeTask without ever verifying is still caught.
--
-- The keystone: it is a SERVER gate, NOT a model tool. It calls the test runner
-- DIRECTLY (not the flag-gated runWorkspaceTests TOOL), so it is decoupled from
-- worker.build-tdd.enabled (V217) and carries NO model-visible schema change —
-- structurally immune to the RL-2026-05-061 schema-stall class that reverted the
-- in-loop test TOOL on native DeepSeek.
--
-- Consumers (set the WorkerTaskRequest#verifyBeforeComplete flag from this row):
--   * PatchAgent (PATCH — SWE-bench + the four autofix surfaces): every run.
--   * SequentialExecutor (BUILD): the FINAL task only (mirrors the smoke gate),
--     so an intermediate task isn't blocked by a later task's tests.
-- Remote Terminal-Bench runs fail-open (the host-side runner can't reach the
-- container — generic remote VBC is a follow-up; TB has its own ES_TB_* gates).
--
-- When OFF (this seed row): both consumers pass verifyBeforeComplete=false →
-- the completeTask path is byte-identical to today. Production is unaffected
-- until an eval (the owed RL-2026-05-061 cross-provider no-regression gate)
-- justifies flipping it.
-- =====================================================================
INSERT INTO feature_flag (flag_key, flag_value, value_type, scope, description)
VALUES (
    'worker.verify-before-complete.enabled',
    'false',
    'BOOLEAN',
    'GLOBAL',
    'VBC verify-before-complete (RL-2026-06) — server-RUNS the project test suite at the first completeTask and refuses if red (bounded/fail-open). A server gate, not a model tool (calls WorkspaceTestRunner directly, decoupled from V217, no schema change). PATCH every run; BUILD final-task only. Default OFF; production byte-identical until flipped.'
)
ON CONFLICT (flag_key) DO UPDATE
    SET flag_value = EXCLUDED.flag_value,
        description = EXCLUDED.description;

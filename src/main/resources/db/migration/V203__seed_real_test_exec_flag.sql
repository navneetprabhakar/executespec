-- =====================================================================
-- V203: Seed scoring.real-test-exec = true.
--
-- RL-2026-05-001 — BUILD mode never actually ran the worker's tests; the
-- verifiedScore TESTS slot was an LLM's *opinion* of coverage. That made
-- the headline both inflatable (re-calibrating the judge moved 72->90+
-- with zero code change) and non-reproducible (the grade is a guess that
-- swings run-to-run). When this flag is on, RunOrchestrator runs the
-- worker-written tests in the workspace (smoketest.WorkspaceTestRunner)
-- after the run completes and feeds the real passed/total into the TESTS
-- slot of ValidationReportService.computeVerifiedScoreAdditive.
--
-- Fail-safe by construction: no recognized runner, a sandbox-blocked
-- command, a timeout, or unparseable output all yield an empty summary,
-- and the scorer falls back to the (damped) LLM coverage path. So the
-- worst case is "no change vs today"; the best case is a TESTS slot that
-- reflects whether the code actually runs.
--
-- Default ON because it can only make the slot more honest. Flip OFF
-- (admin: PUT /api/feature-flags/scoring.real-test-exec) to revert to
-- pure LLM-coverage scoring for the next batch of runs — e.g. if a slow
-- suite is hurting the run-terminal-in-15min SLO on a given provider.
-- =====================================================================

INSERT INTO feature_flag (flag_key, flag_value, value_type, scope, description)
VALUES (
    'scoring.real-test-exec',
    'true',
    'BOOLEAN',
    'GLOBAL',
    'BUILD mode: run the worker-written tests in the workspace and feed real passed/total into the verifiedScore TESTS slot (RL-2026-05-001). Falls back to damped LLM coverage when no runner resolves or output is unparseable.'
)
ON CONFLICT (flag_key) DO UPDATE
    SET flag_value = EXCLUDED.flag_value,
        description = EXCLUDED.description;

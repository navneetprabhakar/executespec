-- =====================================================================
-- V231: Seed worker.critic-pass.enabled = false (DEFAULT OFF).
--
-- RL-2026-06 — critic/reviewer pass (harness stage #1) promoted to COMMON
-- space. The critic is a fresh-context acceptance review run before the FIRST
-- completeTask: a no-tool LlmRouter call that sees ONLY the task + the
-- candidate's deliverable and lists every requirement it fails, independent of
-- the worker's own (anchored) reasoning. It lives in the shared engine/
-- WorkerAgent (NOT a Terminal-Bench fork) and reads the deliverable
-- workspace-agnostically: a `git diff` for a local product run (PATCH/BUILD),
-- or the /app files for a remote Terminal-Bench run.
--
-- This flag gates the critic for PATCH runs (SWE-bench + the four customer
-- autofix surfaces, via PatchAgent). Terminal-Bench enables it via its own
-- ES_TB_CRITIC env (operator-only). BUILD already has ReviewerAgent and does
-- not opt in here.
--
-- When OFF (this seed row): PatchAgent sets criticPass=false → the worker's
-- completeTask path is byte-identical to today; no extra LLM call, no behaviour
-- change. Production is unaffected until an eval justifies flipping it.
--
-- When ON: the FIRST completeTask in a PATCH run triggers one bounded,
-- one-shot, fail-open acceptance review over the worker's git diff; concrete
-- gaps are fed back once before completion is allowed.
--
-- Validated on Terminal-Bench (DeepSeek): the combined deliverable-gate +
-- critic harness took a 13-task near-miss slice from 4/13 -> 8/13 (pass@2),
-- recovering previously-failing targets with 0 regressions.
-- =====================================================================
INSERT INTO feature_flag (flag_key, flag_value, value_type, scope, description)
VALUES (
    'worker.critic-pass.enabled',
    'false',
    'BOOLEAN',
    'GLOBAL',
    'Common critic/reviewer pass (#1) for PATCH runs — one fresh-context acceptance review over the worker git diff before the first completeTask, bounded/one-shot/fail-open (RL-2026-06). Default OFF; production byte-identical until flipped.'
)
ON CONFLICT (flag_key) DO UPDATE
    SET flag_value = EXCLUDED.flag_value,
        description = EXCLUDED.description;

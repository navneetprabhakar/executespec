-- =====================================================================
-- V221: Seed worker.retry-context-retention.enabled = false (DEFAULT OFF).
--
-- RL-2026-05-052 (T3.14) — context retention across task retries.
--
-- Today's flow on a task whose primary worker invocation produces a red
-- build:
--   1. Primary worker runs, exhausts iter budget or builds red.
--   2. SequentialExecutor.execute does ONE retry, calling worker.run(req)
--      with the build error injected as the user prompt.
--   3. The retry worker starts FRESH — empty history. It re-reads the
--      same files the primary already read, re-runs the same searches,
--      and only after burning a chunk of its iter budget gets back to
--      the point where the primary failed.
--
-- This flag turns step 2 into:
--   2'. Load the prior worker's context-snapshot from Redis
--       (v2:agent-ctx:{runId}:{taskId}, saved every 5 iters by WorkerAgent)
--       and pass it as resumeFrom — so the retry continues from the
--       failure point rather than the start. The build error is appended
--       as a new user message after the prior history.
--
-- Why default OFF: the retry path's "fresh start with the build error"
-- has been calibrated against the existing PARTIAL/COMPLETED_WITH_WARNING
-- aggregation; flipping it changes how many iters the retry consumes
-- and could mask iter-cap regressions in the primary. Evaluate first.
--
-- Cleanup (T3.14 part B, ALWAYS ON — not behind this flag):
--   SequentialExecutor.execute now calls ContextStore.deleteAll(runId)
--   at run-end so per-task snapshots don't squat for the 24h TTL after
--   the run has already finished. Pure hygiene; safe regardless of
--   whether this flag is flipped.
--
-- Same rollout as V216 / V217 / V219 / V220.
-- =====================================================================

INSERT INTO feature_flag (flag_key, flag_value, value_type, scope, description)
VALUES (
    'worker.retry-context-retention.enabled',
    'false',
    'BOOLEAN',
    'GLOBAL',
    'Context retention across task retries (RL-2026-05-052, T3.14). Retry loads the prior worker snapshot instead of starting fresh. Default OFF.'
)
ON CONFLICT (flag_key) DO UPDATE
    SET flag_value = EXCLUDED.flag_value,
        description = EXCLUDED.description;

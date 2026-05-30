-- =====================================================================
-- V222: Seed worker.history-compaction.enabled = false (DEFAULT OFF).
--
-- RL-2026-05-052 (follow-up) — worker conversation-history compaction, the
-- second half of the context-discipline work (V216's worker.tool-compactor
-- shrinks individual tool OUTPUTS; this shrinks the accumulated HISTORY).
--
-- Problem: the V2 WorkerAgent keeps its FULL conversation history every turn
-- (no sliding window). On a long MEDIUM/HIGH task (75-120 iters) the input the
-- worker reasons over balloons -> reasoning quality degrades and per-call input
-- cost climbs (the implicit cache softens cost but not window pressure).
--
-- This flag turns on WorkerHistoryCompactor: once a call's INPUT tokens cross
-- ~60k, the OLDEST turns are summarised (LIGHTWEIGHT tier, the run's OWN
-- provider) into one "progress so far" note, keeping the system prompt + the
-- task + the most-recent turns verbatim. The cut is aligned to an
-- AssistantMessage boundary so a tool_use/tool_result pair is never split
-- (Anthropic + OpenAI 400 on a dangling pair). Fail-safe: any error -> full
-- history unchanged.
--
-- Why default OFF: rewriting the history prefix forces a one-time prompt-cache
-- miss, and summarisation could drop a detail the worker needed; both must be
-- measured on the v2-eval / blind-benchmark harness before flip. Same rollout
-- discipline as V216 / V217 / V219 / V220 / V221.
-- =====================================================================

INSERT INTO feature_flag (flag_key, flag_value, value_type, scope, description)
VALUES (
    'worker.history-compaction.enabled',
    'false',
    'BOOLEAN',
    'GLOBAL',
    'Worker conversation-history compaction (RL-2026-05-052 follow-up). Summarises the oldest turns once input tokens cross ~60k, keeping system + task + recent turns. Default OFF.'
)
ON CONFLICT (flag_key) DO UPDATE
    SET flag_value = EXCLUDED.flag_value,
        description = EXCLUDED.description;

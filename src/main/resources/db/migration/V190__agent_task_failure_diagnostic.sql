-- V190 (2026-05-13, PR-A): structured failure diagnostic on every non-COMPLETE agent_task.
--
-- Today's failure forensics: SequentialExecutor.persistTaskStatus writes a single TEXT
-- failureReason string. The Worker.Outcome enum (STOPPED_WITHOUT_COMPLETE, ITERATION_LIMIT_EXCEEDED,
-- BUDGET_EXCEEDED, ERROR, COMPLETED_BY_STAGNATION), the last tool-call attempted, the last LLM
-- error, and the worker's last assistant text all get squashed into that one string or lost
-- entirely. Operators can't debug a stuck/FAILED task without SSHing the GCE VM and grepping the
-- app log.
--
-- Live repro: task aee47879 (exec 85778e63's S5) had status=FAILED, statusReason=ALL_TASKS_FAILED,
-- last_llm_failure=NULL, errorMessage=NULL. The actual exception was only in the app log.
--
-- This column holds a small structured JSON object the executor populates at terminal time.
-- Schema (all keys optional):
--   {
--     "outcome":             "STOPPED_WITHOUT_COMPLETE",      -- Worker.Outcome enum value
--     "iterations":          47,                              -- iters consumed
--     "iterBudget":          75,                              -- iters allowed (for context)
--     "tokensIn":            12450, "tokensOut": 3120,        -- attempt token totals
--     "lastToolCall":        {"name":"writeFile","args":"...","exitOk":false,"error":"..."},
--     "lastLlmError":        "Anthropic 529: overloaded_error",
--     "lastAssistantText":   "Let me also check..."           -- 240-char tail for context
--   }
--
-- Nullable: only populated on non-COMPLETE outcomes. COMPLETE tasks leave it NULL.
ALTER TABLE agent_task ADD COLUMN failure_diagnostic_json TEXT;
COMMENT ON COLUMN agent_task.failure_diagnostic_json IS
  '2026-05-13 PR-A — structured worker-outcome diagnostic for non-COMPLETE tasks. JSON shape: '
  '{outcome, iterations, iterBudget, tokensIn, tokensOut, lastToolCall, lastLlmError, lastAssistantText}. '
  'Populated by SequentialExecutor on terminal. NULL on COMPLETE tasks.';

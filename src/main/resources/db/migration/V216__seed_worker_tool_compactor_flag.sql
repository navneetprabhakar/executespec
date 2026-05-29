-- =====================================================================
-- V216: Seed worker.tool-compactor.enabled = false (DEFAULT OFF).
--
-- RL-2026-05-052 (engine extraction, Phase 5+6). Bulk worker tool output
-- (readFile / searchFiles / listFiles / shell / buildProject / runTests)
-- larger than 3 KB is routed through the LIGHTWEIGHT tier
-- (ToolOutputCompactor agent → Haiku / Flash) before being appended to the
-- WORKHORSE worker's context. Only the summary lands in the worker's
-- prompt — same pattern Anthropic's Claude Code CLI uses internally to
-- keep the decision context lean and the prompt-cache prefix stable.
--
-- Skip rules (compactor returns Optional.empty(), caller uses raw):
--   - This flag is OFF (this seed row).
--   - The output came from the most recent tool call (preserve verbatim).
--   - Tool in {writeFile,editFile,gitCommit,completeTask,gitDiff,gitStatus}
--     — already short / already actionable.
--   - Raw output < 3 KB.
--   - Any LLM call exception (fail-safe: keep raw).
--
-- Default OFF so production runs are byte-identical until we have eval
-- data showing the compactor helps. Reversible via:
--   PUT /api/feature-flags/worker.tool-compactor.enabled
-- =====================================================================

INSERT INTO feature_flag (flag_key, flag_value, value_type, scope, description)
VALUES (
    'worker.tool-compactor.enabled',
    'false',
    'BOOLEAN',
    'GLOBAL',
    'Phase 5+6 engine extraction (RL-2026-05-052): summarise bulk worker tool output (>3 KB) through the LIGHTWEIGHT tier (ToolOutputCompactor → Haiku/Flash) before appending to the WORKHORSE worker context. Mirrors Claude Code CLI prompt-cache discipline. Default OFF.'
)
ON CONFLICT (flag_key) DO UPDATE
    SET flag_value = EXCLUDED.flag_value,
        description = EXCLUDED.description;

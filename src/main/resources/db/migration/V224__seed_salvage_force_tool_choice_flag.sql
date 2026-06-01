-- =====================================================================
-- V224: Seed worker.salvage-force-tool-choice.enabled = false (DEFAULT OFF).
--
-- RL-2026-05-059 — deep-dive on the 2026-05-30 native-DeepSeek mixed10 eval
-- (the "dive deeper, see if more fixes are possible" pass). Root-causing the
-- 1/10 result against the actual generated diffs + git-apply logs + test
-- output + per-call token traces found the DOMINANT failure was NOT a prompt
-- problem:
--
--   4/10 runs produced ZERO edits across all 18 worker iterations
--   (totalWrites=0 at PROGRESS_FORCE), and the Lever-B salvage NEVER ONCE
--   rescued them — the salvage LLM call returned a tool-less response
--   (reasoning-only text; e.g. seaborn-3190 call#19 emitted 109 output
--   tokens but no tool call) and fell through to an empty patch.
--
-- The failure is cross-provider (verified: even Sonnet went totalWrites=0 on
-- matplotlib-23563), so it's the harness's handling of the "model won't
-- commit an edit" state, not raw model capability. Across the whole eval log
-- "SALVAGE produced an edit" fired only twice and never rescued a zero-write
-- run.
--
-- This flag turns the salvage call into a FORCED tool call:
--   * restrict the tool set to writeFile / editFile / completeTask, AND
--   * set tool_choice="required"
-- so the model MUST emit a productive action instead of reasoning-only text.
-- Honoured by the OpenAI-wire clients (OpenAI / DeepSeek / OpenRouter via
-- OpenAiNativeToolClient.tool_choice); Anthropic + Gemini ignore the
-- tool_choice today but still benefit from the restricted tool set. The
-- worker falls back to today's auto behaviour (full tools, tool_choice=auto)
-- when the forced attempt yields nothing — so a provider that rejects
-- "required" can never REGRESS the salvage that occasionally works today.
--
-- Why default OFF: the salvage fires only on an already-failing
-- (zero-write terminal) path, but flipping it changes the produced-patch
-- rate and must be measured against the mixed10 baseline (1/10 native
-- DeepSeek) before flipping. Same additive/flag-gated rollout as
-- V216 / V217 / V219 / V220 / V221 / V222.
-- =====================================================================

INSERT INTO feature_flag (flag_key, flag_value, value_type, scope, description)
VALUES (
    'worker.salvage-force-tool-choice.enabled',
    'false',
    'BOOLEAN',
    'GLOBAL',
    'Lever-B salvage forces a tool call (restricted to writeFile/editFile/completeTask + tool_choice=required) so a model that returns reasoning-only text cannot silently fail the salvage (RL-2026-05-059). Default OFF.'
)
ON CONFLICT (flag_key) DO UPDATE
    SET flag_value = EXCLUDED.flag_value,
        description = EXCLUDED.description;

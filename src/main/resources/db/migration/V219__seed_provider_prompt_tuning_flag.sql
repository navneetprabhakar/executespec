-- =====================================================================
-- V219: Seed worker.provider-prompt-tuning.enabled = false (DEFAULT OFF).
--
-- RL-2026-05-052 (T3.10) — provider-specific prompt fragments. Each LLM has
-- characteristic failure modes the base SYSTEM_PROMPT can't address without
-- bloating: Anthropic narrates ("I will now read X" before tool calls),
-- OpenAI re-reads files, Gemini cache-thrashes on re-reads, DeepSeek invents
-- tool names ("bash" instead of "shell"). The fragments at
--   src/main/resources/prompts/fragments/provider-{anthropic,openai,gemini,deepseek,openrouter}.md
-- carry tight (5-8 line) per-provider corrections that get appended to the
-- base worker system prompt at composeSystemPrompt time.
--
-- When OFF (this seed row): base prompt only — production runs byte-identical.
-- When ON: per-provider fragment appended based on the run's provider.
--
-- Default OFF so we can flip the flag during eval and measure the lift
-- before any default change. Same rollout discipline as V216 (compactor) +
-- V217 (BUILD TDD).
-- =====================================================================

INSERT INTO feature_flag (flag_key, flag_value, value_type, scope, description)
VALUES (
    'worker.provider-prompt-tuning.enabled',
    'false',
    'BOOLEAN',
    'GLOBAL',
    'Provider-specific prompt fragments (RL-2026-05-052, T3.10). Default OFF.'
)
ON CONFLICT (flag_key) DO UPDATE
    SET flag_value = EXCLUDED.flag_value,
        description = EXCLUDED.description;

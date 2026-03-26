# agent/ — LLM Agents & Routing

All LLM-calling agents and the routing infrastructure.

## Key Files
- `LlmRouter.java` — Central LLM call dispatcher. Multi-provider fallback (Anthropic → Google → OpenAI). All LLM calls MUST go through this.
- `WorkerAgent.java` — Executes individual tasks. XML `<tool_call>` format, manual tool dispatch, 15-min wall-clock limit.
- `PlannerAgent.java` — Generates execution plans from user specs. Uses CAPABLE tier (Sonnet).
- `ConversationAgent.java` — Chat copilot. Uses CAPABLE tier via ModelRouter. Compact mode for large projects (>50 files).
- `CostEstimator.java` — Estimates run cost from plan. Per-provider pricing lookup.
- `ApiKeyResolver.java` — Resolves API keys: user → org → system fallback.
- `RunKeyStore.java` — Caches resolved keys per run.
- `LlmOutputParser.java` — Parses XML tool calls from LLM responses.

## Patterns
- Worker completion = response starts with `{` (strict JSON check)
- Sliding window context: first 2 + last 18 messages
- Truncation limits: readFile 8k, shell 6k, diff 5k chars
- Never call LLM providers directly — always through LlmRouter

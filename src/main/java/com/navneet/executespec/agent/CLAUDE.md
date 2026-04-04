# agent/ — LLM Agents & Routing

All LLM-calling agents and the routing infrastructure.

## Key Files
- `LlmRouter.java` — Central LLM call dispatcher. Multi-provider fallback (Anthropic → Google → OpenAI). Integrates with `ProviderHealthTracker` (Redis-backed circuit breaker) to skip unhealthy providers. Uses Caffeine caches (6h TTL, 1000 max) for per-run state. All LLM calls MUST go through this.
- `WorkerAgent.java` — Executes individual tasks. XML `<tool_call>` format, manual tool dispatch, 25-min wall-clock limit. Mid-task cost enforcement: checks `isOverBudget()` every 5 LLM iterations.
- `PlannerAgent.java` — Generates execution plans from user specs. Uses PLANNING tier (Opus). Outputs `project_type` field in plan JSON for downstream preview/deploy detection.
- `ConversationAgent.java` — Chat copilot. Uses LIGHTWEIGHT tier via ModelResolver. Compact mode for large projects (>50 files).
- `CostEstimator.java` — Estimates run cost from plan. Per-provider pricing lookup.
- `ApiKeyResolver.java` — Resolves API keys: user → org → system fallback.
- `RunKeyStore.java` — Caches resolved keys per run.
- `LlmOutputParser.java` — Parses XML tool calls from LLM responses.

## Key Models
- `TaskPlan.java` — Parsed planner output: `projectType`, `planTitle`, `planSummary`, `tasks` (snake_case JSON)
- `TaskDefinition.java` — Individual task: `taskId`, `complexity`, `assignedModel`, `ownedFiles`, `dependsOn`
- `WorkerReport.java` — Worker output: status, files written, provider/model used

## Patterns
- Worker completion = response starts with `{` (strict JSON check)
- Sliding window context: first 2 + last 18 messages
- Truncation limits: readFile 8k, shell 6k, diff 5k chars
- Never call LLM providers directly — always through LlmRouter
- Worker stall detection: no file writes in 5min → escalate model (WORKHORSE → PLANNING tier)
- Worker prompts: `worker.txt` (Anthropic), `worker-openai.txt` (OpenAI), `worker-gemini.txt` (Google)

# agent/ — LLM Agents & Routing

All LLM-calling agents and the routing infrastructure.

## Key Files
- `LlmRouter.java` — Central LLM call dispatcher. Multi-provider fallback (Anthropic → Google → OpenAI). Integrates with `ProviderHealthTracker` (Redis-backed circuit breaker) to skip unhealthy providers. Uses Caffeine caches (6h TTL, 1000 max) for per-run state. All LLM calls MUST go through this.
- `WorkerAgent.java` — Executes individual tasks. XML `<tool_call>` format, manual tool dispatch, 25-min wall-clock limit. Silent hard cap enforcement: checks `isOverBudget()` every 5 LLM iterations (no progressive warnings — user approved cost upfront). **Self-play prevention:** strips fabricated `<tool_result>` blocks via `FABRICATED_TOOL_RESULT_PATTERN` regex before parsing. **XML-first parsing** for PATH B (Anthropic/Google): `parseXmlToolCalls()` first, `parseJsonToolCalls()` fallback — prevents JSON short-circuit from losing multi-tool responses.
- `PlannerAgent.java` — Generates execution plans from user specs. Uses PLANNING tier (Opus). Outputs `project_type` field in plan JSON for downstream preview/deploy detection.
- `ConversationAgent.java` — Chat copilot. Uses LIGHTWEIGHT tier via ModelResolver. Compact mode for large projects (>50 files).
- `CostEstimator.java` — Estimates run cost from plan. Per-provider pricing lookup (with cached input pricing from DB). `estimateWithCredits()` computes cost-proportional credits: `max(1, ceil(bufferedEstimate / $5.0))` with 10% retry buffer. Returns `RunCostEstimate` with per-task breakdown.
- `RunCostEstimate.java` — DTO: `totalEstimatedCost`, `runCredits`, `hardCap`, per-task `TaskCostEstimate` breakdown. Dual pricing: includes `batchCredits`, `batchAvailable`, `creditSavings` for batch execution path (50% discount).
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
- **Self-play prevention:** Anthropic models may output `<tool_call>` + fabricated `<tool_result>` + more `<tool_call>` in one response. `FABRICATED_TOOL_RESULT_PATTERN` strips fake results before parsing. Applied to both main response and truncation salvage paths.
- **Tool call parsing order (PATH B):** XML first (`parseXmlToolCalls`), JSON fallback (`parseJsonToolCalls`). Prevents `parseJsonToolCalls` short-circuit from losing 2nd/3rd tool calls in multi-tool XML responses.
- **Per-response tool call cap:** `MAX_TOOL_CALLS_PER_RESPONSE = 15` — prevents Gemini's 100+ call batches from exhausting the iteration budget in one response. Excess calls skipped with a `<system_note>` telling the model to re-request. Applied to PATH A, PATH B, and truncation salvage paths. Salvage path uses `min(15, remainingBudget - 1)` to reserve a slot for the model's next writeFile call.
- **Read-only tool dedup:** `readOnlyToolCache` in `TaskExecutionState` caches results of read-only tools (readFile, listFiles, searchFiles, git_status, git_diff) keyed by `"toolName|path"`. Cache invalidated on file writes. **Dedup cache hits are FREE — they do NOT count toward `toolCallCount`**, preventing Gemini's redundant reads from exhausting subtask budgets.
- **Subtask decomposition strategies:** (1) File-based — multi-file tasks split into per-file subtasks. (2) Phase-based — complex single-file tasks (MEDIUM/HIGH) split into method-level phases (MEDIUM → 2, HIGH → 3). Phase 1 creates skeleton; subsequent phases read+extend the file. Both strategies use `SUBTASK_ITERATION_LIMIT = 15`.
- **Subtask promotion:** Subtasks that hit iteration/time/context limits but have `totalFileWrites > 0` are auto-promoted from PARTIAL → COMPLETE. A subtask owns exactly one file — if it wrote it, the task succeeded. Logged as `SUBTASK_PROMOTED_TO_COMPLETE`.

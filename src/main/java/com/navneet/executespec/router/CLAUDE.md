# `router/` — Model selection + provider routing

> Last reviewed: 2026-06-10 (1.0.0-M1).

User-selected provider routing with YAML as the single source of truth and a Redis-backed circuit breaker.

## Key files

| File | Purpose |
|------|---------|
| `LlmProvider.java` | Enum: ANTHROPIC, OPENAI, GEMINI, OPENROUTER (Enterprise BYOK aggregator), DEEPSEEK (native `api.deepseek.com` route), and FIREWORKS (OpenAI-wire aggregator route for Chinese open models). OpenRouter / DeepSeek / Fireworks are deliberate BYOK routes and are NOT in the failover chain. `GOOGLE` is accepted as a back-compat alias on `RunRequest.aiProvider` / `CreateProjectRequest.aiProvider` input but is rewritten to `GEMINI` before persistence (V120). |
| `ModelRegistry.java` | **Single source of truth** for model metadata. Reads `application.yml model-matrix.*` — `resolve(agentName, provider)` returns tier-routed model + max-output-tokens; `workhouseModelFor(provider)` returns the provider's default workhorse; `contextLimitForOrPrefixFallback(model)` returns the context window; `displayLabelFor(model)` returns the YAML `display-label`. `ModelContextWindowMap` was deleted in the 2026-05-06 consolidation. |
| `ModelMatrixProperties.java` | `@ConfigurationProperties` binding for `model-matrix:` (providers + agent-tier-map + failover-order + model-pricing + display-label + chainTier + free-tier-locked). |
| `AgentTier.java` | Enum: PLANNING, WORKHORSE, LIGHTWEIGHT. |
| `ResolvedModel.java` | DTO: `requestedProvider`, `actualProvider`, `model`, `agentTier`, `fallback`. |
| `ProviderHealthTracker.java` | Redis-backed circuit breaker. **10** consecutive failures → OPEN; **180s** TTL auto-recovery. Fail-open on Redis errors. (Constants `UNHEALTHY_THRESHOLD=10` + `RECOVERY_WINDOW=Duration.ofSeconds(180)` in the source.) |
| `AvailableProviderResolver.java` | Resolves available providers per run based on org policy + API key availability. |
| `ProviderRecommendationService.java` | Heuristic project-type → provider routing. Gated by `provider.smart-routing.enabled` flag (default OFF). |
| `OrgModelOverrideService.java` | **F3** — per-org `(provider:tier)→model` overrides stored as JSON on `Org.model_overrides_json` (V205). Validates each cell against the registered matrix (OpenRouter via `OpenRouterModelMapper` allow-list; Fireworks via YAML rows + `FireworksModelMapper` picker allow-list), ENTERPRISE-gated, lenient on read; short-TTL Caffeine cache. Backs `GET/PUT /api/org/me/model-matrix`. |
| `OpenRouterModelMapper.java` | Matrix-id → OpenRouter slug allow-list (explicit; unmapped id throws). `LlmRouter.dispatch` `case "openrouter"` routes via `OpenAiNativeToolClient` (OpenAI-wire-compatible). `OrgController.selectableModelsFor("openrouter")` returns the allow-list keys, so adding a model here makes it picker-selectable. **DeepSeek V4 (`deepseek-v4-pro`/`-flash`) + Kimi K2.6 (`kimi-k2.6`) added 2026-05-26 (RL-2026-05-048); Qwen3-Coder (`qwen3-coder` → `qwen/qwen3-coder`) added 2026-05-28 (RL-2026-05-052)** — OpenRouter-only routes; ENTERPRISE BYOK; also registered in `application.yml model-matrix.models` with `provider: openrouter`. **DeepSeek-V4-Pro is also reachable natively via the new `deepseek` provider (see `LlmRouter` `callDeepSeek`) — same model, ~3-4× faster, eliminates the routing hop.** |
| `FireworksModelMapper.java` | Matrix-id → Fireworks slug allow-list (`deepseek-v4-pro-fw`, `deepseek-v4-flash-fw`, `kimi-k2p6-fw`, `glm-5.1-fw`). `LlmRouter.dispatch` `case "fireworks"` routes via `OpenAiNativeToolClient` against `spring.ai.fireworks.base-url`. YAML rows make Fireworks tier-routable; live catalog/latency smoke is still blocked until a real Fireworks key is configured. |
| `RunModelOverrides.java` | **F3** — `ThreadLocal` holder for the run's override map. `RunOrchestrator` binds it at run start (cleared in `finally`); `ModelResolver.lookupModel` consults it so worker/planner/reviewer honour the override with no per-call-site plumbing. **Per-run provider+model pin (Beta, Enterprise-only — 2026-05-22):** static `withRunPin(orgOverrides, provider, model)` builds the bound map by overlaying the run's chosen model onto the provider's three tier cells of a mutable copy of the org map (pin out-ranks org + YAML). `RunOrchestrator` calls it at the bind site ENTERPRISE-gated (owner-tier lookup) from the raw `RunRequest.modelName`, validated provider-scoped via `ModelRegistry`. This overlay is the authoritative routing mechanism for every role — the worker no longer ignores the pin by re-resolving the tier default. UI: the ENTERPRISE-only "Model · Beta" selector on the IDE Compose surface (`frontend/src/ide/IdeSurfaces.tsx`). |
| `OrgModelOverrideService.java` migration | **V205** `org_model_overrides` — adds `org.model_overrides_json TEXT`. |

## Agent tier mapping (from `application.yml`)

| Tier | Anthropic | OpenAI | Gemini | Agents |
|------|-----------|--------|--------|--------|
| PLANNING | `claude-sonnet-4-6` | `gpt-5.4` | `gemini-3.5-flash` | OutlinePlannerAgent, ReviewerAgent (Anthropic + OpenAI only — Gemini reviewer routes to WORKHORSE since `e9d0b6e8`), RepoArchitectureGenerator, ArchitectureDocGenerator, ProjectChatCompiler, PrReviewAgent, CodeReviewAgent |
| WORKHORSE | `claude-sonnet-4-6` | `gpt-5.4` | `gemini-3.5-flash` | WorkerAgent, TaskExpanderAgent, TestPlannerAgent, TestWorkerAgent, ApiTestSuiteAgent, ProjectDocGeneratorService, ValidationReportGenerator, PromptOptimiserAgent |
| LIGHTWEIGHT | `claude-haiku-4-5-20251001` | `gpt-5.4-mini` | `gemini-3-flash-preview` | SmartClassifierAgent, CoherencePlannerAgent, ContextAgent, RunLedgerAgent, RunTitleSummarizer, ProjectChatCompactor, ProjectBriefService, SpecChatService, ConversationAgent, TestReportGenerator, **ToolOutputCompactor** (in `tool/`, RL-2026-05-052) |

> **Literals are illustrative — `application.yml model-matrix` is authoritative.** They drift; this table wrongly showed WORKHORSE = haiku/mini for months. WORKHORSE is the **flagship** tier ("keep flagship — worker is reasoning-heavy, +17pp on p30"), NOT a cheap model. **2026-06-02 (eval rebuild):** the matrix was set all-flagship per provider (openai/anthropic/gemini on their flagship; deepseek on flash-native).

> ⚠️ **The WorkerAgent's ACTUAL model is COMPLEXITY-routed, not its `WORKHORSE` tier-map row.** `SequentialExecutor.tierConfigFor` re-resolves the worker by task complexity: **LOW→LIGHTWEIGHT, MEDIUM→WORKHORSE, HIGH→PLANNING**. So a `SINGLE_SHOT`/LOW task (most SWE-bench fixes) runs the worker on the **LIGHTWEIGHT** model regardless of the `WorkerAgent: WORKHORSE` row. **`pipeline_run.worker_model` is the run-level DEFAULT and is MISLEADING** — the real per-task model lives only in the backend log (`WorkerAgent START ... model=X`). A whole eval campaign silently ran on the cheap tier this way (2026-06-02). To control the worker model, **pin `RunRequest.modelName`** (overrides all three tiers via `RunModelOverrides.withRunPin`); always capture the logged model when measuring.

Default provider: **Anthropic** (changed from OpenAI in 1.0.0-M1). Users can still set a project default, but cross-surface client defaults now live on `app_user.preferred_ai_provider` (V242). Web, CLI, and VS Code write `GET/PUT /api/auth/me/provider-preference`; explicit request input wins and is remembered, omitted input resolves through the account preference before falling back to project/YAML defaults.

## Provider failover

Per `application.yml failover-order:`, when a provider fails the router walks the chain: `anthropic → openai → gemini`, `gemini → openai → anthropic`, `openai → gemini → anthropic`. `FailoverOrderEnumAlignmentTest` pins every failover-order entry and every `model-matrix.providers` key to a valid `LlmProvider` enum constant. `OPENROUTER`, `DEEPSEEK`, and `FIREWORKS` are intentionally NOT in any failover chain — they are explicit BYOK routes that the operator/admin opted into, and silently switching off them would defeat the cost / data-residency / latency reason they picked them.

`FallbackLlmRouter` (**deprecated — RL-2026-05-041, no new callers; migrate to `LlmRouter`**) honours `FallbackMode.NO_FALLBACK` for callers that need strict provider isolation (used when `-Dexecutespec.strict-provider=true`).

## Circuit breaker

- **Storage:** Redis (`StringRedisTemplate`). Keys: `provider:health:{name}`, `provider:failures:{name}`.
- **Threshold:** 10 consecutive failures → OPEN. (Was documented as 5 — corrected by audit 2026-05-12 L #64.)
- **Recovery:** unhealthy key has 180s TTL → auto-expires → provider assumed healthy again.
- **Fail-open:** Redis errors degrade to "assume healthy" (never blocks LLM calls due to Redis issues).
- **Metrics:** `executespec.provider.circuit_opened`, `executespec.provider.circuit_closed` (tagged by provider).
- **Integration:** `FallbackLlmRouter` checks health before each provider attempt; records success/failure after each call. `LlmRouter` (V2 native-tool path) is direct-dispatch — no auto-failover; relies on the YAML failover-order plus per-call cost-cap protection.

## Known tech debt

- ~~**Bug #1c**~~ — **mostly mitigated** (corrected 2026-05-24). `ModelResolver` now gates adaptive routing behind two layers (`lookupModel`, lines 78-92): the `-Dexecutespec.strict-provider` JVM flag AND the `routing.adaptive-routing.enabled` feature flag (default OFF). With neither saying "let adaptive run" it always uses the user's provider — so a strict-locked caller is no longer silently switched. Residual debt: the switch is still silent (no log/event) when an operator flips the flag ON, and there's no per-org opt-out.
- ~~**Planner / reviewer model sourced from run model, not tier**~~ — FIXED / stale note (corrected 2026-05-22). `OutlinePlannerAgent.planOnce` resolves via `modelResolver.resolve(...).getModel()` (line 290); `ReviewerAgent` and the worker (through `SequentialExecutor.tierConfigFor`) likewise resolve via `ModelResolver`. The only `req.model()` left in the planner is a metrics tag. So YAML `planning:` swaps DO take effect — and the F3 per-org overrides ride the same `lookupModel` path.

## YAML consolidation (2026-05-06)

All 8 hardcoded model-mapping sources were collapsed into YAML in 5 commits. `ModelContextWindowMap` deleted. `ModelController.MODEL_LABELS` reads `display-label`. Frontend `PricingTable` reads `chainTier` via new DTO fields on `PricingRow`. See [`docs/reports/model-mapping-audit-2026-05-06.md`](../../../../../../docs/reports/model-mapping-audit-2026-05-06.md) for the audit trail.

# `router/` — Model selection + provider routing

> Last reviewed: 2026-05-12 (1.0.0-M1).

User-selected provider routing with YAML as the single source of truth and a Redis-backed circuit breaker.

## Key files

| File | Purpose |
|------|---------|
| `LlmProvider.java` | Enum: ANTHROPIC, OPENAI, GEMINI. `GOOGLE` is accepted as a back-compat alias on `RunRequest.aiProvider` / `CreateProjectRequest.aiProvider` input but is rewritten to `GEMINI` before persistence (V120). |
| `ModelRegistry.java` | **Single source of truth** for model metadata. Reads `application.yml model-matrix.*` — `resolve(agentName, provider)` returns tier-routed model + max-output-tokens; `workhouseModelFor(provider)` returns the provider's default workhorse; `contextLimitForOrPrefixFallback(model)` returns the context window; `displayLabelFor(model)` returns the YAML `display-label`. `ModelContextWindowMap` was deleted in the 2026-05-06 consolidation. |
| `ModelMatrixProperties.java` | `@ConfigurationProperties` binding for `model-matrix:` (providers + agent-tier-map + failover-order + model-pricing + display-label + chainTier + free-tier-locked). |
| `AgentTier.java` | Enum: PLANNING, WORKHORSE, LIGHTWEIGHT. |
| `ResolvedModel.java` | DTO: `requestedProvider`, `actualProvider`, `model`, `agentTier`, `fallback`. |
| `ProviderHealthTracker.java` | Redis-backed circuit breaker. **10** consecutive failures → OPEN; **180s** TTL auto-recovery. Fail-open on Redis errors. (Constants `UNHEALTHY_THRESHOLD=10` + `RECOVERY_WINDOW=Duration.ofSeconds(180)` in the source.) |
| `AvailableProviderResolver.java` | Resolves available providers per run based on org policy + API key availability. |
| `ProviderRecommendationService.java` | Heuristic project-type → provider routing. Gated by `provider.smart-routing.enabled` flag (default OFF). |

## Agent tier mapping (from `application.yml`)

| Tier | Anthropic | OpenAI | Gemini | Agents |
|------|-----------|--------|--------|--------|
| PLANNING | `claude-sonnet-4-6` | `gpt-5.4` | `gemini-3.1-pro-preview` | OutlinePlannerAgent, ReviewerAgent (Anthropic + OpenAI only — Gemini reviewer routes to WORKHORSE since `e9d0b6e8`), RepoArchitectureGenerator, ArchitectureDocGenerator, ProjectChatCompiler, PrReviewAgent, CodeReviewAgent |
| WORKHORSE | `claude-haiku-4-5-20251001` | `gpt-5.4-mini` | `gemini-3-pro-preview` | WorkerAgent, TaskExpanderAgent, TestPlannerAgent, TestWorkerAgent, ApiTestSuiteAgent, ProjectDocGeneratorService, ValidationReportGenerator, PromptOptimiserAgent |
| LIGHTWEIGHT | `claude-haiku-4-5-20251001` | `gpt-5.4-mini` | `gemini-3-flash-preview` | SmartClassifierAgent, CoherencePlannerAgent, ContextAgent, RunLedgerAgent, RunTitleSummarizer, ProjectChatCompactor, ProjectBriefService, ConversationAgent, TestReportGenerator |

Default provider: **Anthropic** (changed from OpenAI in 1.0.0-M1). User-selectable per project.

## Provider failover

Per `application.yml failover-order:`, when a provider fails the router walks the chain: `anthropic → openai → gemini`, `gemini → openai → anthropic`, `openai → gemini → anthropic`. `FailoverOrderEnumAlignmentTest` (ArchUnit) pins every failover-order entry to a valid `LlmProvider` enum constant.

`FallbackLlmRouter` honours `FallbackMode.NO_FALLBACK` for callers that need strict provider isolation (used when `-Dexecutespec.strict-provider=true`).

## Circuit breaker

- **Storage:** Redis (`StringRedisTemplate`). Keys: `provider:health:{name}`, `provider:failures:{name}`.
- **Threshold:** 10 consecutive failures → OPEN. (Was documented as 5 — corrected by audit 2026-05-12 L #64.)
- **Recovery:** unhealthy key has 180s TTL → auto-expires → provider assumed healthy again.
- **Fail-open:** Redis errors degrade to "assume healthy" (never blocks LLM calls due to Redis issues).
- **Metrics:** `executespec.provider.circuit_opened`, `executespec.provider.circuit_closed` (tagged by provider).
- **Integration:** `FallbackLlmRouter` checks health before each provider attempt; records success/failure after each call. `LlmRouter` (V2 native-tool path) is direct-dispatch — no auto-failover; relies on the YAML failover-order plus per-call cost-cap protection.

## Known tech debt

- **Bug #1c** — `ModelResolver.selectAdaptiveProvider` silently switches providers based on historical success rate even when the caller has explicitly locked the provider. Fix deferred — needs either a strict-mode guard or opt-out flag per org.
- **Planner / reviewer model sourced from run model, not tier** — `OutlinePlannerAgent` uses `req.model()` (the RUN-level `defaultModelFor(provider)`) instead of `ModelRegistry.resolve("OutlinePlannerAgent", provider).getModel()`. Swapping `anthropic: planning:` in YAML has no effect today. Fix: have the planner resolve its own tier model.

## YAML consolidation (2026-05-06)

All 8 hardcoded model-mapping sources were collapsed into YAML in 5 commits. `ModelContextWindowMap` deleted. `ModelController.MODEL_LABELS` reads `display-label`. Frontend `PricingTable` reads `chainTier` via new DTO fields on `PricingRow`. See [`docs/reports/model-mapping-audit-2026-05-06.md`](../../../../../../docs/reports/model-mapping-audit-2026-05-06.md) for the audit trail.

# router/ — Model Selection & Provider Routing

User-selected provider architecture with YAML-configured model matrix and Redis-backed circuit breaker.

## Key Files
- `ModelResolver.java` — Resolves `agentTier × provider → model` from the model matrix. Handles health-based failover via ProviderHealthTracker.
- `ModelMatrixProperties.java` — `@ConfigurationProperties` for the YAML model matrix (providers, agent-tier-map, failover-order).
- `AgentTier.java` — Enum: PLANNING, WORKHORSE, LIGHTWEIGHT
- `ResolvedModel.java` — DTO: requestedProvider, actualProvider, model, agentTier, fallback
- `ProviderHealthTracker.java` — Redis-backed circuit breaker. Marks provider unhealthy after 5 consecutive failures. Auto-recovers via 60s Redis TTL.
- `LlmProvider.java` — Enum: ANTHROPIC, OPENAI, GEMINI (was GOOGLE until v2.5.13 — see V120 migration; back-compat alias on `RunRequest` / `CreateProjectRequest` accepts `GOOGLE` and rewrites to `GEMINI` before persistence)
- `AvailableProviderResolver.java` — Resolves available providers per run based on org policy + API key availability

## Agent Tiers (from application.yml model-matrix)
- PLANNING: Sonnet 4.6 / GPT-5.4 / Gemini 3 Flash Preview (planner, reviewer, worker stall escalation, **v2.6.1: `RepoArchitectureGenerator` + `ArchitectureDocGenerator` + `ProjectChatCompiler` — bumped from LIGHTWEIGHT/WORKHORSE because Anthropic Haiku missed cross-repo wiring + mangled spec compilation**, **v2.6.2-track: `OutlinePlannerAgent` — hierarchical-planner phase 1, 32k cap (FULL_PLAN) / 8k cap (SKELETON_ONLY)**)
- WORKHORSE: Haiku 4.5 / GPT-5.4 / Gemini 3 Flash Preview (workers, compiler, test planner, test worker, conflict resolver, prompt optimiser, report generator, **v2.6.2-track: `TaskExpanderAgent` — hierarchical-planner phase 2, runs in parallel for every LEAF skeleton, 3k cap per task**)
- LIGHTWEIGHT: Haiku 4.5 / GPT-5.4 / Gemini 3 Flash Preview (chat, context, test report, triage, **v2.6.1: `ProjectChatCompactor` — chat history compaction**, **v2.6.2-track: `SmartClassifierAgent` — hierarchical-planner phase 0 LLM verifier**, **v2.6.2-track: `CoherencePlannerAgent` — hierarchical-planner phase 3, applies minimal patches to assembled plan, 1.5k cap**)

## Provider Selection
Each project selects its AI provider (ANTHROPIC/GEMINI/OPENAI). ModelResolver maps `agentTier × provider → model` from YAML config. Failover to next healthy provider if selected provider is down.

### Known tech debt — Bug #1c (v2.5.9 eval finding, not yet fixed)
`ModelResolver.selectAdaptiveProvider` (invoked inside `resolve()`) silently switches providers based on historical success rate even when the caller — e.g. `RunOrchestrator` under `-Dexecutespec.strict-provider=true` — has explicitly locked the provider. Evidence: Proj-OpenAI (74270ac6) logged `provider=anthropic model=claude-sonnet-4-6` despite `project.aiProvider=OPENAI`. Fix deferred — needs either a strict-mode guard inside `ModelResolver.resolve()` OR an opt-out flag per org. Until then, adaptive routing remains authoritative over user-selected provider on the planner/reviewer path.

### Known tech debt — planner/reviewer don't use tier resolution (v2.5.11 finding)
`PlannerAgent.plannerViaLlm` uses `req.model()` (the RUN-level default via `RunOrchestrator.defaultModelFor(provider)`) instead of `ModelResolver.resolve("PlannerAgent", provider).getModel()`. Consequence: swapping `anthropic: planning:` in the model matrix has no effect — the planner always runs on the Haiku-tier worker default. Same pattern likely holds for `ReviewerAgentV3`. Fix: thread a resolved planner model into `PlannerRequest` (similar for reviewer). Tracked for post-Pillar-1.

## Circuit Breaker (ProviderHealthTracker)
- **Storage:** Redis (`StringRedisTemplate`). Keys: `provider:health:{name}`, `provider:failures:{name}`
- **Threshold:** 5 consecutive failures → circuit OPEN
- **Recovery:** Unhealthy key has 60s TTL → auto-expires → provider assumed healthy again
- **Fail-open:** Redis errors degrade to "assume healthy" (never blocks LLM calls due to Redis issues)
- **Metrics:** `executespec.provider.circuit_opened`, `executespec.provider.circuit_closed` (tagged by provider)
- **Integration:** LlmRouter checks health before each provider attempt; records success/failure after each call

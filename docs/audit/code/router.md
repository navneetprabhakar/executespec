# Audit: `router/` — model selection + provider routing

Status: **DONE** · Updated 2026-05-29 · 17 files / 1,944 lines · all read in full

## Files
- `ModelResolver.java` (277) — agent→tier→model resolution + failover + adaptive + F3 overrides ✓
- `ModelRegistry.java` (332) — YAML single-source-of-truth lookups + boot validation ✓
- `ModelMatrixProperties.java` (97) — `@ConfigurationProperties` for `model-matrix.*` ✓
- `OrgModelOverrideService.java` (207) — F3 per-org `(provider:tier)→model` overrides ✓
- `RunModelOverrides.java` (88) — ThreadLocal override holder + per-run pin ✓
- `ProviderHealthTracker.java` (133) — Redis circuit breaker (10 fails→open, 180s TTL) ✓
- `ModelPerformanceTracker.java` (137) — Redis success-rate/latency per model (adaptive) ✓
- `ProviderStatusService.java` (141) — external status-page poll + outage gauge ✓
- `AvailableProviderResolver.java` (125) — per-run provider availability from keys+policy ✓
- `ProviderRecommendationService.java` (92) — AUTO provider heuristic (flag OFF) ✓
- `OpenRouterModelMapper.java` (69) — matrix-id→slug allow-list ✓
- `RunProviderPinStore.java` (78) — Redis first-provider pin per run ✓
- `ModelTaskMetric.java` + repo, `ResolvedModel.java`, `AgentTier.java`, `LlmProvider.java` ✓

## Findings

### Correctness / reliability (the big one)
- 🔴 **The V2 `LlmRouter` feeds NEITHER `ProviderHealthTracker` NOR `ModelPerformanceTracker`.** Neither has a caller on the V2 path ([agent.md](agent.md) `LlmRouter` never references them; only the deprecated `FallbackLlmRouter` records success/failure). Consequences on every production run:
    - `ModelResolver`'s resolution-time failover keys on `healthTracker.isHealthy()` → which is **always healthy** on V2 (failures never recorded) → the circuit breaker is **effectively dead** for the main pipeline. Combined with [agent.md](agent.md)'s finding that `LlmRouter` has no in-call failover, **the V2 path has no working provider failover at all** (audit M#68, now fully characterised).
    - `ModelPerformanceTracker` has no V2 data → adaptive routing (already flag-OFF) would be blind even if enabled.
- 🟡 `ProviderStatusService` hardcodes its own `FAILOVER_ORDER` map (anthropic/openai/gemini) — a **second source of truth** that can drift from `application.yml failover-order`. Its `STATUS_PAGES` also omit openrouter/deepseek.
- 🟡 `ModelTaskMetric` ("collected from day 1, used for routing in Phase 2") — verify it has a V2 writer; if only legacy writes it, it's dead data collection.

### Accuracy / Efficiency / Cost
- 🔵 `ModelResolver` is the chokepoint: `resolve(agent, provider[, tierOverride])` → tier from YAML → `lookupModel` which **first checks `RunModelOverrides.lookup` (F3 per-org / per-run pin) then YAML**. Clean, single path; worker/planner/reviewer all funnel through it.
- 🟢 Adaptive routing is correctly OPT-IN (Fix G): gated by BOTH `-Dexecutespec.strict-provider` AND `routing.adaptive-routing.enabled` (default OFF) — so a strict caller is never silently switched. (Residual: switch is silent when flag is ON.)
- 🟢 `ModelRegistry` boot-validates every matrix/alias/chain/free-tier reference against the models map (`@PostConstruct`) — catches a mistyped model id at startup.
- 🟢 `OrgModelOverrideService` (F3): ENTERPRISE-gated, validates each cell against the registry (OpenRouter via allow-list), writes an immutable `ORG_MODEL_MATRIX_CHANGED` audit row, lenient on read (drops retired cells). `OpenRouterModelMapper` fail-fasts on unmapped ids. Solid.

### Stale-docs / drift
- 📝 **`router/CLAUDE.md` still shows WORKHORSE = `claude-haiku-4-5` / `gpt-5.4-mini`** — same stale table fixed in root CLAUDE.md (actual: Sonnet / GPT-5.4). The per-package CLAUDE.md needs the same correction.
- 📝 `ResolvedModel.providerName()` javadoc references `LlmProvider.GOOGLE` — that enum value no longer exists (it's `GEMINI`). Code is correct; the comment is stale.
- 📝 `AgentTier` javadoc references removed agents (`ReviewerAgentV2`, `CompilerAgent`).

## Cross-refs
Dispatch + the no-failover gap → [agent.md](agent.md). Tier rates for cost → [cost.md](cost.md). Worker tier selection → [harness.md](harness.md) (`tierConfigFor`). Per-run override binding → integration (`RunOrchestrator`, Tier 3).

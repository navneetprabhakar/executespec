# Audit: `cost/` — token + dollar accounting

Status: **DONE** · Updated 2026-05-29 · 5 files / 1,286 lines · all read in full

## Files
- `RunCostTracker.java` (795) — live per-run/per-task accumulator + cache-aware pricing + pre-run estimator ✓
- `CostHistoryService.java` (202) — k-NN historical cost estimate ✓
- `RunTokenAttributor.java` (142) — per-phase / per-task token attribution to DB ✓
- `RunCostHistory.java` (entity) + `RunCostHistoryRepository.java` ✓

## What it does
`LlmRouter` calls `RunCostTracker.record()` after every dispatch → in-memory `Accumulator` per run (and per `run|task`) with input/output/cacheWrite/cacheRead tokens + USD. `estimateRunTierAware()` produces the pre-run estimate that drives credits + the silent hard cap. `CostHistoryService` layers a k-NN-over-past-runs estimate on top. `RunTokenAttributor` persists the real per-task tier-resolved model + tokens.

## Findings

### Correctness / reliability (cost-accuracy risk — directly relevant to the cost priority)
- 🔴 **Cache-token accounting depends on REFLECTION into Spring AI internals.** Anthropic cache counts aren't exposed via the generic `Usage` interface, so `record()` reflects `DefaultUsage.getNativeUsage()` → `AnthropicApi.Usage.cacheReadInputTokens()` ([lines 129–144](../../../src/main/java/com/navneet/executespec/cost/RunCostTracker.java)). On any reflection failure it silently returns 0 → cache reads/writes vanish from cost + the cache-hit signal. A Spring AI upgrade could zero this without a test catching it. **Recommend a guard test** asserting `cacheRead>0` on a known-cached multi-iter call (also the instrument for the "audit cache ratio" action item).
- 🟡 **`CostHistoryService.extractTaskCount()` is hardcoded to `1`** and `extractProjectType()` returns `null` ([lines 176–200](../../../src/main/java/com/navneet/executespec/cost/CostHistoryService.java)). The "strict" similarity query keys on `taskCount`, so it ~never matches beyond 1-task runs → the estimator almost always falls through to the loose (strategy+provider only) query. The k-NN is coarser than its design until task count is plumbed through.

### Accuracy / Efficiency / Cost (the model)
- 🔵 **Pricing is genuinely cache-aware + provider-correct** (`priceCall`): Anthropic `input_tokens` excludes cache (no subtraction); OpenAI/Gemini bundle cache into prompt tokens (subtract `cacheRead`). Cache-read rate read from YAML `cached-input-per-1m`; cache-write 125× input, Anthropic-only. Gemini thinking tokens folded into output. This is the right model — cost numbers are trustworthy *if* the reflection above keeps working.
- 🔵 **`estimateRunTierAware` is a real cost model**, not a guess: worker cost (per-task tier rates) + planning-phase cost (per-strategy: classifier/outline/expander/coherence) + post-planning (reviewer general+wiring+**probabilistic fix-build third pass**+accuracy+validation+codemap) + Anthropic cache-write overhead. Heavily calibrated against named runs (e.g. 6024f510 12× miss → re-tuned). `HIGH_MULTIPLIER=3.0`, `BUFFERED=1.20`, `USD_PER_CREDIT=5.0`, `credits=ceil(buffered/5)`.
- 🔵 **`Snapshot` carries `cacheReadTokens`** → cache-hit rate is measurable per run from the tracker logs (`RunCostTracker record ... cacheRead=N`). This is the instrument for tuning context/caching levers.
- 🔵 Token estimate bands were 3× under real on code-dense tasks → recalibrated to P75 (LOW 30k/4k, MEDIUM 200k/15k, HIGH 600k/40k input/output). HIGH worker tasks at 600k input confirm the no-compaction context-bloat concern from [engine.md](engine.md).

### Tech-debt
- 🟡 `RunCostTracker` is 795 LOC mixing live accounting + a large static estimation model + reflection helpers — three concerns; the estimator half could be its own `CostEstimator` (one already exists in `agent/`, possible overlap to reconcile).
- 🟢 `RunTokenAttributor` correctly writes the **actual** per-task tier-resolved model (fixes the "every task shows Haiku" DB bug) — good.

## Cross-refs
Recorder caller → [agent.md](agent.md) (`LlmRouter.call`). Tier rates → [router.md](router.md) (`ModelResolver`/`ModelPricing`). Estimate consumer → integration (`RunOrchestrator.persistEstimate`, Tier 3). Context-bloat tie-in → [engine.md](engine.md).

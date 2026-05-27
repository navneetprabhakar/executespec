# `engine/` — the code-gen engine seam

> Added 2026-05-27 (RL-2026-05-052). Design + migration plan:
> [`docs/plans/codegen-engine-extraction.md`](../../../../../../../docs/plans/codegen-engine-extraction.md).

Isolates **code generation** as a provider-agnostic core engine, with the pipeline
(classifier / planner / reviewer / scoring / governance) as **upstream / downstream** layers — so the
engine can be improved + measured in isolation while the product moat stays intact. Strangler-fig
rollout, each phase shippable + green:

| Phase | What | Status |
|-------|------|--------|
| **1** | **The contract** — `CodeGenEngine.solve(EngineRequest) → EngineResult` + supporting records. **No behaviour change.** | **done (this package)** |
| **2** | **Wrap facade — `CodeGenEngineImpl`** delegating to today's `PatchAgent.run` (→ `WorkerAgent` → tools → `LlmRouter`); maps in/out of the contract. Registered as a bean but **not injected anywhere yet** (parallel + unused). | **done** |
| **3** | **Route the benchmark through the engine** — `BenchmarkGenerationService.generate()` now calls `CodeGenEngine.solve(...)` (the engine's first real consumer). Agent call byte-identical (solve wraps the same `PatchAgent.run`); benchmark keeps its container diff-capture for the scored patch. | **routing done** (parity re-run pending) |
| **4** | **Relocated `WorkerAgent` + `WorkerResult` + `WorkerTaskRequest`** from `agent/` → `engine/` (the worker loop now lives in the engine; worker tools stay in `tool/`). Behaviour-neutral package move — ~30 files re-pointed, `mvn compile` + `test-compile` green. | **worker relocated** |
| 5 | Lift side effects out — SSE/cost/DB become an `EngineObserver` callback so the engine is pure. | |
| 6 | Land the pending harness internals IN the engine: #5 cache discipline · #7 compaction · #8 fork-sub-agents · #9 permission ruleset + hooks. | |

## Files

| File | Purpose |
|------|---------|
| `CodeGenEngine.java` | The seam — `EngineResult solve(EngineRequest)`. Pure code-gen: no credits/PRs/scoring/governance; no clone/checkout (the caller owns the workspace); no side effects beyond the workspace. |
| `CodeGenEngineImpl.java` | **Phase 2 wrap facade** (`@Service`). `solve()` builds an `ExecutionContext` over the prepared workspace → `PatchAgent.run(provider, model, taskSpec, ctx)` → host-side `git diff` (infra-excluded) → maps `WorkerResult` + `RunCostTracker.snapshot` into `EngineResult`. Byte-identical to a direct `PatchAgent.run`; **not injected anywhere yet**. Phase-2 caveat: `budget`/`verifyMode` honoured via the existing path's mechanisms (run-row `hardCapUsd`, per-provider iter caps, `sourceType` gate); explicit pass-through lands phase 4+. |
| `EngineRequest.java` | `taskSpec · workspace(Path) · provider · model · budget · verifyMode · runId · taskId`. |
| `EngineResult.java` | `diff · verification · transcript · cost · outcome · summary`. |
| `EngineOutcome.java` | COMPLETED / STAGNATED / ITER_LIMIT / BUDGET / WALL_CLOCK / STOPPED / ERROR (maps from `agent.WorkerResult.Outcome`). |
| `VerifyMode.java` | NONE / SOFT_NUDGE / HARD_GATE — the verify lever as **data**, not a code branch. |
| `EngineBudget.java` | iterCap / wallClockSeconds / dollarCap (null = engine/tier default). |
| `EngineCost.java` | inputTokens / outputTokens / cacheReadTokens / usd (cache-aware). |
| `EngineVerification.java` | buildOk / testsPassed / testsTotal / detail (null field = **not measured**, distinct from failed). |
| `WorkerAgent.java` | **(Phase 4, moved from `agent/`)** the sequential multi-turn worker loop — stream → tools → `completeTask`; progress/verify nudges; Lever-B salvage. Still imports `agent.LlmRouter` + `agent.LlmRequest`. |
| `WorkerResult.java` | **(Phase 4, moved from `agent/`)** the worker's terminal result + `Outcome` enum. |
| `WorkerTaskRequest.java` | **(Phase 4, moved from `agent/`)** the worker's input (provider / model / prompts / context / budget / verify flags). |

## Invariants

- **Nothing implements or calls `CodeGenEngine` yet** — Phase 1 is contract-only, so every production
  run is byte-identical. Phase 2 adds the facade (parallel + unused first).
- Keep this a **crisp contract, not a sprawling framework** (the COMPARISON caveat — clear seam, accept
  some coupling).
- The engine has **no knowledge** of credits / PRs / scoring / governance / SSE / DB — those are the
  caller's (upstream/downstream) concern.

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
| **5+6** | **`ToolOutputCompactor` + prompt-cache discipline** — bulk worker tool output (> 3 KB; `readFile`/`searchFiles`/`listFiles`/`shell`/`buildProject`/`runTests`) is summarised through the LIGHTWEIGHT tier (Haiku / Flash) before being appended to the WORKHORSE worker's history. Only the compacted summary lands in the worker's context. Skip list: writeFile/editFile/gitCommit/completeTask/gitDiff/gitStatus (already short / load-bearing); most-recent-tool-call (preserve verbatim); raw < 3 KB. Fail-safe: any LLM exception → raw output. Default OFF behind `worker.tool-compactor.enabled` (V216, default false). Mirrors Anthropic's Claude Code CLI internal pattern — bulk through Haiku, decision-context through Sonnet. | **landed default-OFF** (RL-2026-05-052, this branch) |
| 6 | Land the pending harness internals IN the engine: #5 cache discipline · #7 compaction · #8 fork-sub-agents · #9 permission ruleset + hooks. (Static/dynamic system-prompt boundary for true prefix-cache stability is still future scope; the compactor above is the first half of #7.) | partial |

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
| `WorkerAgent.java` | **(Phase 4, moved from `agent/`)** the sequential multi-turn worker loop — stream → tools → `completeTask`; progress/verify nudges; Lever-B salvage. **Forced-tool-choice salvage (RL-2026-05-059, flag `worker.salvage-force-tool-choice.enabled`, V224, default OFF):** when on, `runSalvageCall` does a `salvageAttempt` with the tool set restricted to `writeFile`/`editFile`/`completeTask` (`salvageTools(restrict=true)`) + `tool_choice="required"`, then **falls back** to today's full-tools/auto attempt if the forced one yields no tool call — so a model that returns reasoning-only text (the verified 4/10 native-DeepSeek zero-write empty-patch failure) is forced to emit an edit, with no regression for providers that reject `"required"`. Still imports `agent.LlmRouter` + `agent.LlmRequest`. |
| `WorkerResult.java` | **(Phase 4, moved from `agent/`)** the worker's terminal result + `Outcome` enum. |
| `WorkerTaskRequest.java` | **(Phase 4, moved from `agent/`)** the worker's input (provider / model / prompts / context / budget / verify flags). |

## Common harness stages on the worker (RL-2026-06)

Three completeTask-time stages live IN the shared `WorkerAgent` as **flag/data-activated capabilities** — NOT a Terminal-Bench fork. Each is **one-shot + fail-open** (can never loop or false-block) and reads the deliverable via `dispatchToolDirect` (workspace-agnostic: local `tool.execute()` on the VM workspace, or remote container dispatch). Inert for any consumer that doesn't opt in, so BUILD/PATCH are byte-identical until they do.

| Stage | Field | What it does | Consumers |
|-------|-------|--------------|-----------|
| **#1 Critic pass** | `criticPass` (Boolean) | Before the FIRST completeTask, ONE fresh-context, no-tool `LlmRouter` acceptance review over the task + the deliverable — **git diff** for a local product run, **/app** files for a remote TB run; concrete gaps fed back once (`runCriticPass`/`criticMessage`/`CRITIC_SYSTEM_PROMPT`). Subsumes the verify nudge when on. | TB (`ES_TB_CRITIC`), PATCH (`worker.critic-pass.enabled`, V231 — **default ON since V239**). BUILD has `ReviewerAgent`. |
| **#2 Deliverable gate** | `requiredDeliverablePaths` (List) | At completeTask, ONE deterministic `shell` existence check for the required paths; missing ⇒ refuse (bounded to `DELIVERABLE_GATE_MAX_REFUSALS=4` then YIELD — raised 2→4 after the TB 2.0 full-89 autopsy found runs yielding after 2 refusals and completing artifact-less). **Also enforced in the TERMINAL_FINALIZER since RL-2026-06**: the finalizer previously accepted `completeTask` unchecked (one of the four confirmed NO_DELIVERABLE routes); it now re-runs the existence check, rejects once with a write-the-artifact-NOW forced turn, then accepts regardless (fail-open, bounded by a flag). Activates on a non-empty list — only TB supplies one today (`TerminalBenchRunService.extractDeliverablePaths`). | TB (`ES_TB_DELIVERABLE_GATE`). |
| **#3 Verify gate** | `terminalVerifyGate` (Boolean) | Terminal refinement of the generic `!gateOn` verify nudge: fires even after a shallow shell smoke (which defeats the generic `lastEditIter > lastVerifyIter` trigger). | TB (`ES_TB_VERIFY_GATE`). |
| **#VBC verify-before-complete** | `verifyBeforeComplete` (Boolean) | At the FIRST completeTask, **SERVER-RUNS the project's own test suite** (`smoketest/WorkspaceTestRunner` called DIRECTLY — NOT the flag-gated `runWorkspaceTests` tool, so decoupled from V217) and refuses if red (`verifyBeforeCompleteFailure`/`verifyBeforeCompleteMessage`; bounded `VBC_GATE_MAX_REFUSALS=3` then YIELD; fail-open on unwired/remote/no-runner/zero-tests/any error). Unlike the model-dependent test/build SOFT gates above, it RUNS the tests itself — a worker that never verifies is still caught. A *server gate, not a model tool* (no schema change → immune to the RL-2026-05-061 stall). Suppresses the soft `!gateOn` verify nudge when on. | PATCH (`worker.verify-before-complete.enabled`, V235 — **default ON since V239** — every run), BUILD (`SequentialExecutor`, **final task only**). Remote/TB fail-open. |

Validated on Terminal-Bench (DeepSeek): #2+#1 took a 13-task near-miss slice **4/13 → 8/13 (pass@2)**, 0 regressions; the critic demonstrably refuses premature completions then permits the fixed candidate.

## Dispatch-layer robustness + parallelism (vix-leverage harness borrows)

Three provider-neutral borrows on the worker dispatch path (all default-on, no flag):

- **Sibling pairing after `completeTask` (C2).** `completeTask` short-circuits its batch with a `return`; any tool call AFTER it in the same assistant turn is now paired with a synthetic `SUPERSEDED` `tool_result`, so the persisted/replayed history never carries a dangling `tool_use` (Anthropic pairing invariant).
- **Pre-dispatch arg validation (B4).** `dispatchOne` (and the Spring-AI `ToolRegistry.ContextBoundToolCallback.call`) run `tool/ToolArgValidator` before `tool.execute` — a conservative, fail-open, **absent-only** check that rejects a call omitting a schema-declared `required` field with an `INVALID_ARGS` the model recovers from. No model-visible schema change (sidesteps the RL-2026-05-061 schema-sensitivity class).
- **Read-only-batch parallelism (C1, RL-2026-06-010).** When a single assistant turn emits ONLY read-only tools (`readFile`/`searchFiles`/`listFiles`/`gitStatus`/`gitDiff`, ≥2, non-remote), `maybeParallelReadDispatch` pre-executes their bodies concurrently on virtual threads; the sequential loop then consumes the cached `{callId→envelope}` map, so every per-call mutation (metrics/SSE/`toolResponses` order/gate-tracking) is byte-identical. Any batch with a write/build/shell/`completeTask` (or a remote run) stays fully sequential (no read/write ordering hazard); fail-safe (timeout/partial → sequential; reads are idempotent). Behavior-neutral + faster on read-heavy exploration turns. Safe because the read set is pure (`ReadFileStateStore` = synchronizedMap+ConcurrentHashMap; `RunCostTracker` reached via `maybeCompact` is `AtomicLong`-based).

## Invariants

- **Nothing implements or calls `CodeGenEngine` yet** — Phase 1 is contract-only, so every production
  run is byte-identical. Phase 2 adds the facade (parallel + unused first).
- Keep this a **crisp contract, not a sprawling framework** (the COMPARISON caveat — clear seam, accept
  some coupling).
- The engine has **no knowledge** of credits / PRs / scoring / governance / SSE / DB — those are the
  caller's (upstream/downstream) concern.

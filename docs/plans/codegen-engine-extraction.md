# Code-gen engine extraction — design + migration plan

> Founder directive (2026-05-27): isolate code-generation as a **core engine**, with the pipeline
> (classifier / planner / reviewer / scoring / governance) as **upstream / downstream** layers, so we
> can improve the engine continuously + keep the product moat intact, across multiple providers.
> Validated by the competitive analysis: **opencode is literally "core engine as a server + thin
> clients"** (see `docs/competitive-analysis/COMPARISON.md §7`). RL-2026-05-051.

## 1. Why (the case, in one paragraph)

Today the coding "engine" (the worker loop + tools + context) is **entangled** with the pipeline
inside `integration/RunOrchestrator` (4,800 LOC) + `harness/SequentialExecutor` + `agent/WorkerAgent`.
The black-pollution bug is the proof: a *pipeline* concern (the `── 2.5` lint auto-fix) silently
corrupted the *engine* output (the diff). A clean engine boundary makes that class of bug
structurally impossible, makes the SWE-bench number measure exactly the thing we're improving (we
already hand-extract it via "stop at worker"), lets us iterate on the engine in isolation (faster
eval, no pipeline noise), and is the natural home for the harness adoptions still pending
(#5 cache discipline, #7 compaction, #8 fork-sub-agents, #9 hooks/permission-ruleset).

## 2. The engine contract (the seam)

A single, provider-agnostic entry — pure code-gen, no knowledge of credits / PRs / scoring /
governance:

```
CodeGenEngine.solve(EngineRequest) -> EngineResult

EngineRequest {
  taskSpec        // the unit of work (issue / task description / failing-test spec)
  workspace       // a prepared working tree (engine does NOT clone/checkout — caller owns setup)
  toolset         // the bound tools (file r/w/edit, shell, search, build, runTests, completeTask)
  model           // provider + model id (+ optional per-tier overrides)
  budget          // iter cap, wall-clock, $ cap
  verifyMode      // NONE | SOFT_NUDGE | HARD_GATE (the #3 lever, caller-selected)
}
EngineResult {
  diff            // the captured change set (git diff vs base, infra-excluded)
  verification    // build/test/repro evidence the engine gathered
  transcript      // the message + tool-call log
  cost            // tokens + $ (cache-aware, per-model)
  outcome         // COMPLETED | STAGNATED | ITER_LIMIT | BUDGET | ERROR
}
```

Properties:
- **Provider-agnostic:** model is an input; the engine calls `LlmRouter` (which already abstracts
  Anthropic/OpenAI/Gemini/OpenRouter). Per-task model routing (our union-19/30 lever) stays a caller
  concern OR an engine option.
- **Tool-pluggable:** `toolset` is injected (mirrors `ToolRegistry.bindForWorker` today). Permissions
  become a declarative ruleset on the toolset (the opencode `evaluate()` pattern), so "plan mode =
  deny edits", "BYOK", "guardrail level" are *data*, not code branches.
- **No side effects beyond the workspace:** no DB writes, no SSE, no push. The caller wires those.
  (Today `WorkerAgent` emits SSE + writes cost — those move to a thin adapter the caller passes.)

## 3. What is engine vs upstream vs downstream

| Layer | Owns | Today's code |
|-------|------|--------------|
| **Upstream** | classify, plan, synthesize the task spec, assemble context (memory/codemap/RAG/git-status), prepare the workspace (clone/checkout) | `SmartClassifierAgent`, `OutlinePlannerAgent`, `TaskExpanderAgent`, context assembly, `WorkspaceInitService` |
| **ENGINE** | the worker loop: stream model → run tools → verify → produce diff + transcript + cost | `WorkerAgent` + the worker half of `SequentialExecutor` + `tool/*` + `LlmRouter` |
| **Downstream** | reviewer, lint, scoring, validation report, PR creation, governance (standards/contracts/ADR), credits, memory ledger, preview | `ReviewerAgent`, `LintPass`, `ValidationReportService`, `ScmPushService`, governance packages, billing |

The benchmark already runs `upstream(classify+plan) → ENGINE (stop here) → score externally`. Product
runs are `upstream → ENGINE → downstream(review+score+PR+govern)`. **Same engine, different
wrapping.**

## 4. Migration plan (strangler-fig — incremental, behind the current interface)

Do NOT big-bang rewrite `RunOrchestrator`. Phases, each shippable + green:

1. **Define the contract** — `engine/CodeGenEngine` interface + `EngineRequest`/`EngineResult` records.
   No behavior change.
2. **Wrap, don't move** — implement `CodeGenEngineImpl` as a thin facade that *calls today's*
   `SequentialExecutor.executeOne` / `WorkerAgent.run` for one task, mapping in/out of the contract.
   `RunOrchestrator` keeps calling `SequentialExecutor`; the engine facade is parallel + unused at
   first (or used only by the benchmark path). **Pin with the existing eval (no regression).**
3. **Route the benchmark through the engine** — `BenchmarkRunService`/`BenchmarkGenerationService`
   call `CodeGenEngine.solve(...)` directly instead of `submitRun`. This is the cleanest first real
   consumer (it already wants "engine only"). Re-run the 30 to confirm parity.
4. **Move the worker internals into `engine/`** — relocate `WorkerAgent` + the worker tool-dispatch +
   context/compaction into the engine package; `SequentialExecutor` becomes a thin per-task driver
   that calls the engine. Tools stay in `tool/` (shared).
5. **Lift side effects out** — replace direct SSE/cost/DB calls in the worker with callbacks on
   `EngineRequest` (an `EngineObserver`), so the engine is pure; `RunOrchestrator` supplies the
   product observer, the benchmark supplies a no-op.
6. **Land the pending harness internals IN the engine** — #5 (cache-stable prompt assembly + per-model
   prompts), #7 (compaction-as-a-loop-task), #8 (fork-sub-agents), #9 (permission ruleset + hooks).
   These are engine-architecture; they belong here, built once.
7. **(Optional, later) Engine-as-a-service** — expose the engine behind the existing REST/OpenAPI so a
   thin client (CLI / VS Code) can run it in-process or remote, per opencode's `serve` + generated-SDK
   + swappable-transport model.

## 5. Risk controls

- Every phase keeps the **v2-eval + the SWE-bench 30 green** (no-regression gate before merge).
- Production runs stay **byte-identical** until phase 5 (the wrap facade just delegates).
- The plan gate, governance, scoring, PR creation — all **downstream, untouched** by phases 1-4.
- Keep the engine boundary a **crisp contract**, not a sprawling framework (the COMPARISON caveat:
  even Claude Code isn't cleanly layered — aim for "clear seam, accept some coupling").

## 6. What we explicitly preserve (the moat)

Plan gate, multi-repo PR creation + cross-link, governance (standards/contracts/ADR/invariants),
verified additive scoring, project memory + RAG, the 4 autofix surfaces, billing/tiers, and
**multi-provider per-task routing** (the union-19/30 lever neither competitor has). Extraction
*clarifies* these as upstream/downstream layers — it does not expose or weaken them.

## 7. Status

Design (this doc) = the "start" of the extraction during the benchmark window. On the same branch
(`feat/benchmark-codegen-harness`). #5/#7/#8/#9 land in phases 4–6.

**Update (2026-05-27, RL-2026-05-052):** **Phase 1 (the contract) is shipped** — the `engine/` package
(`CodeGenEngine.solve(EngineRequest) → EngineResult` + `EngineOutcome` / `VerifyMode` / `EngineBudget` /
`EngineCost` / `EngineVerification`), compile + spotless green. **No behaviour change — nothing
implements or calls `CodeGenEngine` yet, so every production run is byte-identical.**

**Phase 2 (initial wrap facade) shipped, now superseded** — `engine/CodeGenEngineImpl` first wrapped
the then-current `PatchAgent.run` path and mapped `WorkerResult` + `RunCostTracker.snapshot` into
`EngineResult`.

**Phase 3 routing shipped, now expanded** — `BenchmarkGenerationService.generate()` became the first
consumer of `CodeGenEngine.solve(...)`; as of 2026-06-12, orchestrated PATCH runs also route through
the same engine.

**Phase 4 is done** — `WorkerAgent` + `WorkerResult` + `WorkerTaskRequest` relocated `agent/` → `engine/`
(the worker loop now physically lives in the engine; worker tools stay in `tool/`). Behaviour-neutral
package move — ~30 files re-pointed, `mvn compile` + `test-compile` green; `WorkerAgent.truncate` widened
to `public` for the now-cross-package `WorkerAgentTruncateTest`. **Caveat:** 4 pre-existing worker
unit-test off-by-ones (nudge / wall-clock / stagnation, all "expected N got N+1") are **debt from the earlier
verify-nudge harness work (RL-2026-05-051), NOT the move** — verified by re-running them at HEAD (same 4
red before the move). Flagged for a focused fix. Product runs stay on `RunOrchestrator` → byte-identical
throughout. **Phase 5** (lift SSE/cost/DB behind an `EngineObserver`) is next.

**Update (2026-06-12, RL-2026-06-030):** `CodeGenEngineImpl` now owns the PATCH-style
`WorkerTaskRequest` construction directly and calls `WorkerAgent`; `RunOrchestrator.runPatchPipeline`
and `BenchmarkGenerationService.generate` both route through `CodeGenEngine.solve`. `PatchAgent` is
deprecated and has no active call sites. The former PatchAgent policy (patch prompt, provider patch
fragments, raised caps, source-scoped test-verification gate, critic/VBC flags) moved into the engine.

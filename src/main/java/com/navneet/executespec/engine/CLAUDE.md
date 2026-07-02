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
| **2** | **Direct engine worker facade — `CodeGenEngineImpl`** builds the PATCH-style `WorkerTaskRequest` itself (prompt + provider patch fragments + per-provider caps + source-scoped test gate + critic/VBC flags) and calls `WorkerAgent` directly. `PatchAgent` is deprecated and no longer on the active path. | **done** |
| **3** | **Route benchmark + PATCH orchestration through the engine** — `BenchmarkGenerationService.generate()` and `RunOrchestrator.runPatchPipeline()` call `CodeGenEngine.solve(...)`; benchmark keeps its container diff-capture for the scored patch. | **done** |
| **4** | **Relocated `WorkerAgent` + `WorkerResult` + `WorkerTaskRequest`** from `agent/` → `engine/` (the worker loop now lives in the engine; worker tools stay in `tool/`). Behaviour-neutral package move — ~30 files re-pointed, `mvn compile` + `test-compile` green. | **worker relocated** |
| 5 | Lift side effects out — SSE/cost/DB become an `EngineObserver` callback so the engine is pure. | |
| **5+6** | **`ToolOutputCompactor` + prompt-cache discipline** — bulk worker tool output (> 3 KB; `readFile`/`searchFiles`/`listFiles`/`shell`/`buildProject`/`runTests`) is summarised through the LIGHTWEIGHT tier (Haiku / Flash) before being appended to the WORKHORSE worker's history. Only the compacted summary lands in the worker's context. Skip list: writeFile/editFile/gitCommit/completeTask/gitDiff/gitStatus (already short / load-bearing); most-recent-tool-call (preserve verbatim); raw < 3 KB. Fail-safe: any LLM exception → raw output. Default OFF behind `worker.tool-compactor.enabled` (V216, default false). Mirrors Anthropic's Claude Code CLI internal pattern — bulk through Haiku, decision-context through Sonnet. | **landed default-OFF** (RL-2026-05-052, this branch) |
| 6 | Land the pending harness internals IN the engine: #5 cache discipline · #7 compaction · #8 fork-sub-agents · #9 permission ruleset + hooks. (Static/dynamic system-prompt boundary for true prefix-cache stability is still future scope; the compactor above is the first half of #7.) | partial |

## Files

| File | Purpose |
|------|---------|
| `CodeGenEngine.java` | The seam — `EngineResult solve(EngineRequest)`. Pure code-gen: no credits/PRs/scoring/governance; no clone/checkout (the caller owns the workspace); no side effects beyond the workspace. |
| `CodeGenEngineImpl.java` | `@Service` implementation. `solve()` builds an `ExecutionContext` over the prepared workspace, constructs the PATCH-style `WorkerTaskRequest` directly, calls `WorkerAgent.run`, captures host-side `git diff` (infra-excluded), and maps `WorkerResult` + `RunCostTracker.snapshot` into `EngineResult`. It owns the former PatchAgent policy: patch prompt assembly, provider patch fragments, Anthropic/DeepSeek/OpenRouter/Fireworks 220 / OpenAI 150 / Gemini 180 caps, and source-scoped test verification (`swebench*`/`benchmark*` OFF, customer patch/finding sources ON unless `VerifyMode.NONE`). |
| `EngineRequest.java` | `taskSpec · workspace(Path) · provider · model · budget · verifyMode · runId · taskId`. |
| `EngineResult.java` | `diff · verification · transcript · cost · outcome · summary`. |
| `EngineOutcome.java` | COMPLETED / STAGNATED / ITER_LIMIT / BUDGET / WALL_CLOCK / STOPPED / ERROR (maps from `agent.WorkerResult.Outcome`). |
| `VerifyMode.java` | NONE / SOFT_NUDGE / HARD_GATE — the verify lever as **data**, not a code branch. |
| `EngineBudget.java` | iterCap / wallClockSeconds / dollarCap (null = engine/tier default). |
| `EngineCost.java` | inputTokens / outputTokens / cacheReadTokens / usd (cache-aware). |
| `EngineVerification.java` | buildOk / testsPassed / testsTotal / detail (null field = **not measured**, distinct from failed). |
| `WorkerAgent.java` | **(Phase 4, moved from `agent/`)** the sequential multi-turn worker loop — stream → tools → `completeTask`; progress/verify nudges; Lever-B salvage. **PATCH no-edit nudge (RL-2026-06-030):** non-terminal PATCH runs inject the existing progress nudge after 6 consecutive non-write iterations with zero edits, and pre-edit build/test/read loops do not reset that watchdog; BUILD and Terminal-Bench keep their existing thresholds. **Forced-tool-choice salvage (RL-2026-05-059, flag `worker.salvage-force-tool-choice.enabled`, V224, default OFF):** when on, `runSalvageCall` does a `salvageAttempt` with the tool set restricted to `writeFile`/`editFile`/`completeTask` (`salvageTools(restrict=true)`) + `tool_choice="required"`, then **falls back** to today's full-tools/auto attempt if the forced one yields no tool call — so a model that returns reasoning-only text (the verified 4/10 native-DeepSeek zero-write empty-patch failure) is forced to emit an edit, with no regression for providers that reject `"required"`. Still imports `agent.LlmRouter` + `agent.LlmRequest`. |
| `WorkerResult.java` | **(Phase 4, moved from `agent/`)** the worker's terminal result + `Outcome` enum. |
| `WorkerTaskRequest.java` | **(Phase 4, moved from `agent/`)** the worker's input (provider / model / prompts / context / budget / verify flags). |

## Common harness stages on the worker (RL-2026-06)

Common completion/protection stages live IN the shared `WorkerAgent` as **flag/data-activated capabilities** — NOT a Terminal-Bench fork. Each is bounded/fail-open where it can block, and deliverable reads use `dispatchToolDirect` (workspace-agnostic: local `tool.execute()` on the VM workspace, or remote container dispatch). Inert for any consumer that doesn't opt in, so BUILD/PATCH are byte-identical until they do.

| Stage | Field | What it does | Consumers |
|-------|-------|--------------|-----------|
| **#1 Critic pass** | `criticPass` (Boolean) | Before the FIRST completeTask, ONE fresh-context, no-tool `LlmRouter` acceptance review over the task + the deliverable — **git diff** for a local product run, **/app** files for a remote TB run. Remote TB context now includes `/tests` listing, `/tests/test.sh` when present, and candidate `/app` file contents when explicit deliverable extraction is empty, so the critic is not blind on extensionless/implicit-output tasks. Concrete gaps are fed back once (`runCriticPass`/`criticMessage`/`CRITIC_SYSTEM_PROMPT`). Subsumes the verify nudge when on. | TB (`ES_TB_CRITIC`), PATCH via `CodeGenEngineImpl` (`worker.critic-pass.enabled`, V231, default OFF). BUILD has `ReviewerAgent`. |
| **#2 Deliverable gate** | `requiredDeliverablePaths` (List) | At completeTask, ONE deterministic `shell` existence check for the required paths; missing ⇒ refuse (bounded to `DELIVERABLE_GATE_MAX_REFUSALS=2` then YIELD). Activates on a non-empty list — only TB supplies one today (`TerminalBenchRunService.extractDeliverablePaths`). | TB (`ES_TB_DELIVERABLE_GATE`). |
| **#3 Verify gate** | `terminalVerifyGate` (Boolean) | Terminal refinement of the generic `!gateOn` verify nudge: fires even after a shallow shell smoke (which defeats the generic `lastEditIter > lastVerifyIter` trigger). | TB (`ES_TB_VERIFY_GATE`). |
| **#3b Visible-test completion gate** | terminal mode + `terminalVerifyGate` | If the remote sandbox exposes verifier files under `/tests`, `completeTask` is refused until a passing verifier-shaped command actually targets `/tests` (for example `bash /tests/test.sh` or `pytest /tests`). This blocks the regression where a worker self-smokes the wrong contract while critic-pass misses the visible verifier mismatch. Bounded to `TERMINAL_VISIBLE_TEST_GATE_MAX_REFUSALS=3`, then yields. | TB terminal mode. |
| **#4 Verified-pass guard** | terminal mode + verifier-shaped command | After a conservative verifier-shaped command succeeds (`/tests/test.sh`, commands under `/tests/`, common test runners, explicit verify/benchmark commands) and extracted deliverables exist, the worker snapshots required deliverables under `/tmp/es-passing-candidate-<runId>`, suppresses critic/verify nudges, refuses later risky mutating/rerun tools, and force-completes on a text-only follow-up. This makes "do not regress a passing candidate" mechanical rather than prompt-only. | TB terminal mode. |
| **#VBC verify-before-complete** | `verifyBeforeComplete` (Boolean) | At the FIRST completeTask, **SERVER-RUNS the project's own test suite** (`smoketest/WorkspaceTestRunner` called DIRECTLY — NOT the flag-gated `runWorkspaceTests` tool, so decoupled from V217) and refuses if red (`verifyBeforeCompleteFailure`/`verifyBeforeCompleteMessage`; bounded `VBC_GATE_MAX_REFUSALS=3` then YIELD; fail-open on unwired/remote/no-runner/zero-tests/any error). Unlike the model-dependent test/build SOFT gates above, it RUNS the tests itself — a worker that never verifies is still caught. A *server gate, not a model tool* (no schema change → immune to the RL-2026-05-061 stall). Suppresses the soft `!gateOn` verify nudge when on. | PATCH via `CodeGenEngineImpl` (`worker.verify-before-complete.enabled`, V235, default OFF — every run), BUILD (`SequentialExecutor`, **final task only**). Remote/TB fail-open. |

Validated on Terminal-Bench (DeepSeek): #2+#1 took a 13-task near-miss slice **4/13 → 8/13 (pass@2)**, 0 regressions; the critic demonstrably refuses premature completions then permits the fixed candidate.

Terminal finalizer note: the finalizer can force one last closing tool turn for terminal tasks, but it
must still pass the same deterministic deliverable, visible-test, and critic gates before returning `COMPLETED`.
Productive shell/write calls alone are not proof of completion.

## Dispatch-layer robustness + parallelism (vix-leverage harness borrows)

Three provider-neutral borrows on the worker dispatch path (default-on unless noted):

- **Sibling pairing after `completeTask` (C2).** `completeTask` short-circuits its batch with a `return`; any tool call AFTER it in the same assistant turn is now paired with a synthetic `SUPERSEDED` `tool_result`, so the persisted/replayed history never carries a dangling `tool_use` (Anthropic pairing invariant).
- **Pre-dispatch arg validation (B4).** `dispatchOne` (and the Spring-AI `ToolRegistry.ContextBoundToolCallback.call`) run `tool/ToolArgValidator` before `tool.execute` — a conservative, fail-open, **absent-only** check that rejects a call omitting a schema-declared `required` field with an `INVALID_ARGS` the model recovers from. No model-visible schema change (sidesteps the RL-2026-05-061 schema-sensitivity class).
- **Read-only-batch parallelism (C1, RL-2026-06-010).** When `execution.continuation.parallel-read-tools-enabled=true` and a single assistant turn emits ONLY read-only tools (`readFile`/`searchFiles`/`listFiles`/`gitStatus`/`gitDiff`, ≥2, non-remote), `maybeParallelReadDispatch` pre-executes their bodies concurrently on virtual threads; the sequential loop then consumes the cached `{callId→envelope}` map, so every per-call mutation (metrics/SSE/`toolResponses` order/gate-tracking) is byte-identical. Server logs and legacy `LOG` SSE lines emit the parallel batch labels on start plus per-call `ok`/`err` results on completion. Any batch with a write/build/shell/`completeTask` (or a remote run) stays fully sequential (no read/write ordering hazard); fail-safe (timeout/partial → sequential; reads are idempotent). Behavior-neutral + faster on read-heavy exploration turns. Safe because the read set is pure (`ReadFileStateStore` = synchronizedMap+ConcurrentHashMap; `RunCostTracker` reached via `maybeCompact` is `AtomicLong`-based).
- **Tool-call result logging for waste guards.** Normal worker `toolCallLog` entries now append `-> OK` / `-> ERR` after the bounded argument preview. `SequentialExecutor` uses that result-bearing log to group repeated `shell` / `bash` / `buildProject` / `runTests` failures by stable command/test signature.

## Invariants

- `CodeGenEngine` is now the active single-task codegen seam for benchmark generation and PATCH-mode
  orchestration. Do not add new `PatchAgent` call sites; migrate/delete the deprecated shell instead.
- Keep this a **crisp contract, not a sprawling framework** (the COMPARISON caveat — clear seam, accept
  some coupling).
- The engine has **no knowledge** of credits / PRs / scoring / governance / SSE / DB — those are the
  caller's (upstream/downstream) concern.

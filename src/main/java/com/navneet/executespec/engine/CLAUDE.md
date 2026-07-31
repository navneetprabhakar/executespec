# `engine/` — the code-gen engine seam

## PATCH/critic prompt authority (2026-07-18)

`CodeGenEngineImpl` frames the PATCH task and deterministic repository orientation independently as
untrusted user evidence. `WorkerAgent`'s optional critic pass likewise frames the task and candidate
deliverable independently; neither repository text nor generated/tool-derived content is appended to
the critic system policy. `ApplyService` frames post-run apply suggestions before worker dispatch.

> Added 2026-05-27 (RL-2026-05-052). Design + migration plan:
> [codegen engine extraction plan](https://github.com/navneetprabhakar/executespec-docs/blob/main/backend/plans/codegen-engine-extraction.md).

Isolates **code generation** as a provider-agnostic core engine, with the pipeline
(classifier / planner / reviewer / scoring / governance) as **upstream / downstream** layers — so the
engine can be improved + measured in isolation while the product moat stays intact. Strangler-fig
rollout, each phase shippable + green:

| Phase | What | Status |
|-------|------|--------|
| **1** | **The contract** — `CodeGenEngine.solve(EngineRequest) → EngineResult` + supporting records. **No behaviour change.** | **done (this package)** |
| **2** | **Direct engine worker facade — `CodeGenEngineImpl`** builds the PATCH-style `WorkerTaskRequest` itself (prompt + provider patch fragments + per-provider caps + source-scoped test gate + critic/VBC flags) and calls `WorkerAgent` directly. The former `PatchAgent` wrapper is deleted. Required prompt resources now fail closed at startup; provider overlays use `PromptAssembler` rather than direct classpath reads. | **done** |
| **3** | **Route benchmark + PATCH orchestration through the engine** — `BenchmarkGenerationService.generate()` and `RunOrchestrator.runPatchPipeline()` call `CodeGenEngine.solve(...)`; benchmark keeps its container diff-capture for the scored patch. | **done** |
| **4** | **Relocated `WorkerAgent` + `WorkerResult` + `WorkerTaskRequest`** from `agent/` → `engine/` (the worker loop now lives in the engine; worker tools stay in `tool/`). Behaviour-neutral package move — ~30 files re-pointed, `mvn compile` + `test-compile` green. | **worker relocated** |
| 5 | Lift side effects out — SSE/cost/DB become an `EngineObserver` callback so the engine is pure. | |
| **5+6** | **`ToolOutputCompactor` + prompt-cache discipline** — bulk worker tool output (> 3 KB; `readFile`/`searchFiles`/`listFiles`/`shell`/`buildProject`/`runTests`) is summarised through the LIGHTWEIGHT tier (Haiku / Flash) before being appended to the WORKHORSE worker's history. Only the compacted summary lands in the worker's context. Skip list: writeFile/editFile/gitCommit/completeTask/gitDiff/gitStatus (already short / load-bearing); most-recent-tool-call (preserve verbatim); raw < 3 KB. Fail-safe: any LLM exception → raw output. Default OFF behind `worker.tool-compactor.enabled` (V216, default false). Mirrors Anthropic's Claude Code CLI internal pattern — bulk through Haiku, decision-context through Sonnet. | **landed default-OFF** (RL-2026-05-052, this branch) |
| 6 | Land the pending harness internals IN the engine: #5 cache discipline · #7 compaction · #8 fork-sub-agents · #9 permission ruleset + hooks. (Static/dynamic system-prompt boundary for true prefix-cache stability is still future scope; the compactor above is the first half of #7.) | partial |

## Capability-keyed tool policy (RR-1599, 2026-07-20)

`WorkerToolPolicy` is the single classifier behind every `WorkerAgent` safety and progress gate.
The registry mints several NAMES over one implementation (`executeCommand`/`runCommand`/
`terminalCommand` = shell; `build`/`runBuild` = build; `rename` = `move`; `deletePath` = `delete`;
`applyPatch`/`ast_edit` = edit), so the previous literal-name predicates silently failed OPEN for
every alias they had not enumerated: the verified-pass guard did not refuse an alias, and the
build-verification gate could not be satisfied through `build`/`runBuild`. Policy now keys on
`tool.ToolCapability`.

Fail-closed direction differs per predicate and is deliberate: safety predicates
(`isRiskyAfterVerifiedPass`, `isWriteTool`, `isShellTool`) treat an UNCLASSIFIED name as dangerous,
while `isProgressTool` treats it as NOT progress so an unknown tool cannot hold the stagnation guard
open. `WorkerAgent.mutationTarget` also uses explicit classification only — an unclassified tool's
arguments must never be claimed as a delivered product path — and now emits `[target=…]` for
`move` / `delete` / `ast_edit` too, which previously produced no delivery evidence at all.

## Files

| File | Purpose |
|------|---------|
| `CodeGenEngine.java` | The seam — `EngineResult solve(EngineRequest)`. Pure code-gen: no credits/PRs/scoring/governance; no clone/checkout (the caller owns the workspace); no side effects beyond the workspace. |
| `CodeGenEngineImpl.java` | `@Service` implementation. `solve()` builds an `ExecutionContext` over the prepared workspace, constructs the PATCH-style `WorkerTaskRequest` directly, calls `WorkerAgent.run`, captures host-side `git diff` (infra-excluded), and maps `WorkerResult` + `RunCostTracker.snapshot` into `EngineResult`. It owns the former PatchAgent policy: patch prompt assembly, provider patch fragments, Anthropic/DeepSeek/OpenRouter/Fireworks 220 / OpenAI 150 / Gemini 180 caps, and source-scoped test verification (`swebench*`/`benchmark*` OFF, customer patch/finding sources ON unless `VerifyMode.NONE`). |
| `EngineRequest.java` | `taskSpec · workspace(Path) · provider · model · budget · verifyMode · runId · taskId`. |
| `EngineResult.java` | `diff · verification · transcript · cost · outcome · summary`. |
| `EngineOutcome.java` | COMPLETED / STAGNATED / ITER_LIMIT / BUDGET / WALL_CLOCK / STOPPED / ERROR (maps from `agent.WorkerResult.Outcome`). |
| `VerifyMode.java` | NONE / SOFT_NUDGE / HARD_GATE — the verify lever as **data**, not a code branch. |
| `EngineBudget.java` | iterCap / wallClockSeconds / dollarCap plus an optional absolute deadline and protected shutdown reserve (null = engine/tier default). The three-argument constructor remains source-compatible. |
| `EngineCost.java` | inputTokens / outputTokens / cacheReadTokens / usd (cache-aware). |
| `EngineVerification.java` | buildOk / testsPassed / testsTotal / detail (null field = **not measured**, distinct from failed). |
| `WorkerAgent.java` | **(Phase 4, moved from `agent/`)** the sequential multi-turn worker loop — stream → tools → `completeTask`; progress/verify nudges; Lever-B salvage. **PATCH no-edit nudge (RL-2026-06-030):** non-terminal PATCH runs inject the existing progress nudge after 6 consecutive non-write iterations with zero edits, and pre-edit build/test/read loops do not reset that watchdog; BUILD and Terminal-Bench keep their existing thresholds. **Forced-tool-choice salvage (RL-2026-05-059, flag `worker.salvage-force-tool-choice.enabled`, V224, default OFF):** when on, `runSalvageCall` does a `salvageAttempt` with the tool set restricted to `writeFile`/`editFile`/`completeTask` (`salvageTools(restrict=true)`) + `tool_choice="required"`, then **falls back** to today's full-tools/auto attempt if the forced one yields no tool call — so a model that returns reasoning-only text (the verified 4/10 native-DeepSeek zero-write empty-patch failure) is forced to emit an edit, with no regression for providers that reject `"required"`. Selected cross-model/provider continuation requests set `zeroWriteSalvageAllowed=false`: their shared workspace already contains the primary attempt's patch, so a zero-write recovery stops at the progress bound instead of manufacturing an unverified replacement edit. Still imports `agent.LlmRouter` + `agent.LlmRequest`. |
| `TerminalCandidateSnapshotCommand.java` | Builds the restricted shell command used to preserve a terminal-task candidate after verifier-shaped proof, keeping command construction and shell-token sanitization out of the worker loop. |
| `WorkerResult.java` | **(Phase 4, moved from `agent/`)** the worker's terminal result + `Outcome` enum. |
| `WorkerTaskRequest.java` | **(Phase 4, moved from `agent/`)** the worker's input (provider / model / prompts / context / budget / verify flags), including an optional tighten-only `allowedToolNames` profile for bounded specialist passes. |

## Common harness stages on the worker (RL-2026-06)

Common completion/protection stages live IN the shared `WorkerAgent` as **flag/data-activated capabilities** — NOT a Terminal-Bench fork. Each is bounded/fail-open where it can block, and deliverable reads use `dispatchToolDirect` (workspace-agnostic: local `tool.execute()` on the VM workspace, or remote container dispatch). Inert for any consumer that doesn't opt in, so BUILD/PATCH are byte-identical until they do.

| Stage | Field | What it does | Consumers |
|-------|-------|--------------|-----------|
| **#1 Critic pass** | `criticPass` (Boolean) | Before the FIRST completeTask, ONE fresh-context, no-tool `LlmRouter` acceptance review over the task + the deliverable — **git diff** for a local product run, **/app** files for a remote TB run. Remote TB context now includes `/tests` listing, `/tests/test.sh` when present, and candidate `/app` file contents when explicit deliverable extraction is empty, so the critic is not blind on extensionless/implicit-output tasks. Concrete gaps are fed back once (`runCriticPass`/`criticMessage`/`CRITIC_SYSTEM_PROMPT`). Subsumes the verify nudge when on. | TB (`ES_TB_CRITIC`), PATCH via `CodeGenEngineImpl` (`worker.critic-pass.enabled`, V231, default OFF). BUILD has `ReviewerAgent`. |
| **#2 Deliverable gate** | `requiredDeliverablePaths` (List) | At completeTask, ONE deterministic `shell` existence check for the required paths; missing ⇒ refuse (bounded to `DELIVERABLE_GATE_MAX_REFUSALS=2` then YIELD). Activates on a non-empty list — only TB supplies one today (`TerminalBenchRunService.extractDeliverablePaths`). | TB (`ES_TB_DELIVERABLE_GATE`). |
| **#3 Verify gate** | `terminalVerifyGate` (Boolean) | Terminal refinement of the generic `!gateOn` verify nudge: fires even after a shallow shell smoke (which defeats the generic `lastEditIter > lastVerifyIter` trigger). | TB (`ES_TB_VERIFY_GATE`). |
| **#3b Visible-test completion gate** | terminal mode + `terminalVerifyGate` | If the remote sandbox exposes verifier files under `/tests`, `completeTask` is refused until a passing verifier-shaped command actually targets `/tests` (for example `bash /tests/test.sh` or `pytest /tests`). This blocks the regression where a worker self-smokes the wrong contract while critic-pass misses the visible verifier mismatch. Bounded to `TERMINAL_VISIBLE_TEST_GATE_MAX_REFUSALS=3`, then yields. | TB terminal mode. |
| **#4 Verified-pass guard** | terminal mode + verifier-shaped command | After a conservative verifier-shaped command succeeds (executing `/tests/test.sh`, running a real test runner against `/tests`, common project test runners, or explicit verify/benchmark commands) and extracted deliverables exist, the worker snapshots required deliverables under `/tmp/es-passing-candidate-<runId>`, suppresses critic/verify nudges, refuses later risky mutating/rerun tools, and force-completes on a text-only follow-up. Listing or reading `/tests` is not accepted as proof. This makes "do not regress a passing candidate" mechanical rather than prompt-only. | TB terminal mode. |
| **#VBC verify-before-complete** | `verifyBeforeComplete` (Boolean) | At the FIRST completeTask, **SERVER-RUNS the project's own test suite** (`smoketest/WorkspaceTestRunner` called DIRECTLY — NOT the flag-gated `runWorkspaceTests` tool, so decoupled from V217) and refuses red completion for **one bounded server-owned repair pass** (`verifyBeforeCompleteFailure` / `verifyBeforeCompleteMessage`). The refusal carries structured `failedTests` candidates plus a bounded `outputTail`; the next completion attempt yields fail-open, so the gate cannot consume unbounded iterations/tokens. Unlike the model-dependent test/build SOFT gates above, it RUNS the tests itself — a worker that never verifies is still caught. A *server gate, not a model tool* (no schema change → immune to the RL-2026-05-061 stall). Suppresses the soft `!gateOn` verify nudge when on. | PATCH via `CodeGenEngineImpl` (`worker.verify-before-complete.enabled`, V235, graduated ON by V239/V259), BUILD (`SequentialExecutor`, **final task only**). Remote/TB fail-open. |

Validated on Terminal-Bench (DeepSeek): #2+#1 took a 13-task near-miss slice **4/13 → 8/13 (pass@2)**, 0 regressions; the critic demonstrably refuses premature completions then permits the fixed candidate.

Terminal finalizer note: the finalizer can force one last closing tool turn for terminal tasks, but it
must still pass the same deterministic deliverable, visible-test, and critic gates before returning `COMPLETED`.
Productive shell/write calls alone are not proof of completion.

## Dispatch-layer robustness + parallelism (vix-leverage harness borrows)

Three provider-neutral borrows on the worker dispatch path (default-on unless noted):

- **Sibling pairing after `completeTask` (C2).** `completeTask` short-circuits its batch with a `return`; any tool call AFTER it in the same assistant turn is now paired with a synthetic `SUPERSEDED` `tool_result`, so the persisted/replayed history never carries a dangling `tool_use` (Anthropic pairing invariant).
- **Pre-dispatch arg validation (B4).** `dispatchOne` (and the Spring-AI `ToolRegistry.ContextBoundToolCallback.call`) run `tool/ToolArgValidator` before `tool.execute` — a conservative, fail-open, **absent-only** check that rejects a call omitting a schema-declared `required` field with an `INVALID_ARGS` the model recovers from. No model-visible schema change (sidesteps the RL-2026-05-061 schema-sensitivity class).
- **Read-only-batch parallelism (C1, RL-2026-06-010).** When `execution.continuation.parallel-read-tools-enabled=true` and a single assistant turn emits ONLY read-only tools (`readFile`/`searchFiles`/`listFiles`/`gitStatus`/`gitDiff`, ≥2, non-remote), `maybeParallelReadDispatch` pre-executes their bodies concurrently on virtual threads; the sequential loop then consumes the cached `{callId→envelope}` map, so every per-call mutation (metrics/SSE/`toolResponses` order/gate-tracking) is byte-identical. Server logs and legacy `LOG` SSE lines emit the parallel batch labels on start plus per-call `ok`/`err` results on completion. Any batch with a write/build/shell/`completeTask` (or a remote run) stays fully sequential (no read/write ordering hazard); fail-safe (timeout/partial → sequential; reads are idempotent). Behavior-neutral + faster on read-heavy exploration turns. Safe because the read set is pure (`ReadFileStateStore` = synchronizedMap+ConcurrentHashMap; `RunCostTracker` reached via `maybeCompact` is `AtomicLong`-based).
- **Tool-call result logging for waste guards.** Normal worker `toolCallLog` entries persist only the tool name, an opaque SHA-256 argument signature and `-> OK` / `-> ERR`; raw arguments and commands are never retained. Mutating calls also append an exact bounded `[target=...]` so task-attributable diff evidence survives without edit content. `SequentialExecutor` uses the result-bearing signature to group repeated `shell` / `bash` / `buildProject` / `runTests` failures; only explicit `-> OK` mutation entries may claim a product path.

## Invariants

- **Canonical orientation search (RL-2026-07-036).** `RepoOrientationService` delegates identifier
  localization to `RepositorySearchService` instead of maintaining a second file walker. This
  keeps orientation git-aware, fingerprinted, symlink-safe, and consistent with worker search;
  directory layout and build/test command discovery remain advisory and local to the orientation
  seam.

- `CodeGenEngine` is now the active single-task codegen seam for benchmark generation and PATCH-mode
  orchestration. Keep PATCH policy in `CodeGenEngineImpl`; do not recreate a wrapper around it.
- Keep this a **crisp contract, not a sprawling framework** (the COMPARISON caveat — clear seam, accept
  some coupling).
- The engine has **no knowledge** of credits / PRs / scoring / governance / SSE / DB — those are the
  caller's (upstream/downstream) concern.


## Harness-resolution (2026-07-04; lean toolset graduated ON by V340)

- `WorkerAgent` progress-v2 (`worker.progress-v2.enabled`): **successful** novelty-based progress (seen-set of tool+args signatures; failed/forbidden calls are remembered for repetition evidence but never reset stagnation), per-signature consecutive-failure force (nudge@3/force@5), PATCH exploration ceiling replaces the 6-non-write nudge, bounded `tool_choice=required` retries on text-only stops, `progressNudgeMessageV2` (no 'imperfect edit beats zero'). This prevents a model from holding the worker open by varying sandbox-denied shell wrappers while its cached context grows every turn. Focused `WorkerMutationChurnGuard` tracks successful mutations per exact path since the last **proven exit-zero** test/build. BUILD orchestration supplies `execution.continuation.max-edit-churn` as the exact force threshold even when progress-v2 is off; progress-v2 callers without an explicit threshold retain the historical 6/10 default. Shell exit evidence is parsed across the full bounded envelope because Maven/Jest output can precede the JSON-escaped `exit=0` marker. Terminal-Bench is excluded because its artifact iteration contract is separate. Pinned by `agent/WorkerAgentProgressV2Test`, `engine/WorkerAgentTerminalVerifierPassTest`, and `engine/WorkerMutationChurnGuardTest`.
- `WorkerMutationEpochWatchdog` is an always-on non-terminal backstop independent of progress-v2. A successful mutation opens an in-memory epoch; only the first successful build/test/shell verification in that epoch receives progress credit. Its independent 4/8 clock advances only on verification-only turns: novel successful read/search/list/diff investigation resets the clock, other non-verification turns pause it, and a completion-policy-refused `completeTask` gets one response turn before any further watchdog decision. This stops the reproduced iter-18 mutation + green-verification loop at iter 26 without inheriting HIGH's 24/40 creative-work thresholds or killing legitimate debugging. Same-epoch signatures normalize capability + success/failure. Resume starts a fresh bounded window; this is not a durable cross-JVM fence. WorkerAgent establishes one authoritative per-attempt start when callers omit it. JVM-local cost/token safety stops use `snapshotTask` and are opt-in through `worker.best-effort-max-task-cost-usd=-1` and `worker.best-effort-max-task-total-tokens=-1`; they are not durable authorization because restart recovery does not hydrate RunCostTracker. Terminal-Bench is exempt from those defaults and preserves one shutdown-reserve provider-and-tool finalizer after its ordinary wall-clock cap, though explicit request cost/token caps still apply. `WorkerDispatchMode` makes that narrow authority explicit. Pinned by `WorkerMutationEpochWatchdogTest`, `WorkerAgentProgressV2Test`, and `WorkerAgentLiveTaskBudgetTest`.
- lean toolset (`worker.lean-toolset.enabled`): V340 graduates the canonical server-worker schema after live release-blocker validation showed 34 duplicate/compatibility tool definitions on every DeepSeek turn. Compatibility aliases remain registered for local-hands and replay; ordinary server workers see only canonical capabilities.
- auto-verify (`worker.auto-verify.enabled`): server runs the resolved suite after a burst of **successful** write/edit calls (and on completion intent) and injects counts + failing tail (schema-free); rejected writes do not trigger a suite run; VBC refusals carry failure detail via `WorkspaceTestRunner.runTestsDetailed`. A green observation explicitly directs immediate `completeTask` and forbids another final-verification/cleanup/warning-polish loop—the former "Tests are green — continue" wording caused a live DeepSeek worker to spend 18 more turns after its suite was already green. A model-invoked whole-suite `runTests` pass establishes the same signal (targeted tests do not). Once the task has also written a maintained test file, a next turn containing only read/list/search/glob/git inspection force-completes from that deterministic evidence; shell/build/mutation clears the signal and requires fresh verification. The maintained-test prerequisite prevents an unrelated pre-existing green suite from terminating code-only work.
- context headroom (`context/ContextPlanner`): before each worker request, bind the exact worker tool schemas once, reserve requested output plus an 8% model-window safety margin, and compare against the larger of provider-reported input usage or a deterministic message/tool estimate. Unsafe headroom invokes the existing tool-pair-safe `WorkerHistoryCompactor`; unknown model metadata or missing planner dependencies preserve the legacy flag-gated trigger and provider-overflow recovery.
- `WorkerHistoryPolicy` owns the stateless truncation, context-overflow detection, and hard-trim rules used by `WorkerAgent`; the public static wrappers remain only for compatibility with existing characterization tests.
- Context-overflow recovery: `isContextLengthError` + compactNow → `hardTrimHistory` → ERROR (was: instant ERROR). The deterministic fallback now bounds oversized short tool envelopes while preserving assistant/tool pairing, and progress signatures use text-safe separators (no NUL byte). Pinned by `WorkerAgentOverflowRecoveryTest`.
- `RepoOrientationService` (`worker.localization.enabled`): deterministic warm-start block (layout, identifier grep hits, resolver commands) injected into the BUILD planner cold-start and the first worker user message. The planner and worker share the same advisory evidence marker; the flag remains fail-open and default-off until a measured gate flips it.
- `WorkerTaskRequest` gained `progressV2` + `autoVerify`. New `@RequiredArgsConstructor` deps go at the END of the field list (7 test ctor sites were churned by mid-list insertion).

## P1 audit-remediation batch (engine lane, 2026-07-22)

Fail-closed / evidence-honest corrections; no migration.

- **RR-259** — worker progress and `totalWriteCalls` now derive from SUCCESSFUL mutations /
  verification transitions, not tool-name matches. A rejected write or non-zero build no longer
  resets the no-progress watchdog: `buildProject` returns a transport-level Ok envelope even for a
  red child process, so build progress additionally requires structured exit-zero evidence. A
  single failed write no longer inflates `totalWriteCalls` (which had disabled zero-write Lever-B
  salvage). The 2026-07-24 Go reviewer demonstrated the old mismatch by repeating red buildProject
  calls through 38 iterations while each transport Ok reset stagnation.
- **RR-260** — `WorkerResult.salvagedUnverified` flags a best-guess salvage write; the executor
  treats a salvaged `COMPLETED` as a soft completion so it must pass the smoke gate to reach SUCCESS
  (COMPLETED_WITH_WARNING when smoke is skipped). "file changed" is no longer "task completed".
- **RR-591** — the direct verification/critic path (`dispatchToolDirect` and the
  missingDeliverables / visibleTestsExist / readDeliverableContext / snapshotTerminalVerifiedCandidate
  / terminalVerifierPass helpers) now threads the task's immutable absolute deadline into
  `dispatchRemote`, so reconnects can no longer renew a terminal remote call forever.
- **RR-622** — worker tool-call metrics record `boundedToolMetricName(tc.name())` (registered alias
  or `"unknown"`), so an adversarial provider cannot mint unbounded Micrometer series.
- **RR-1562** — `EngineBudget.dollarCap` now propagates onto `WorkerTaskRequest.dollarCap`; the worker
  enforces `min(dollarCap, PipelineRun.hardCapUsd)`, so a per-solve cap is no longer a dead field.

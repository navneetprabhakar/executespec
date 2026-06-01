# RunOrchestrator god-class split — dedicated-session plan

> **Status (2026-05-30):** increments **1–4 DONE + pushed** (PR #196) — RunOrchestrator **5,288 → 4,456 LOC (−16%)**, 4 new collaborators, full suite 1432 green at each step, zero behaviour change. **inc-3b DONE (2026-06-01, `claude/agent-competitive-review-F4boG` / PR #199):** `buildMultiRepoSummary` + `buildLanguageParityBlock` → `RunContextComposer` (+ a `projectRepoService` dep), `languageConventionHint` → `RunPipelineSupport` (+ `LanguageParityBlockTest` repointed); 4 call sites rewired via `contextComposer.`; RunOrchestrator 4,456 → 4,363 LOC; full `mvn verify` green, zero behaviour change. **inc-4b pool-lift DONE (2026-06-01):** the internally-owned `pool` `ExecutorService` field → a shared `PipelineExecutorPool` `@Component` bean (byte-identical sizing / daemon `v2-orchestrator-N` threads / 30s `@PreDestroy` shutdown); the 7 `pool.submit` sites unchanged, the 2 `CompletableFuture(..., pool)` fan-outs → `pool.executor()`; RunOrchestrator 4,363 → 4,324 LOC; full `mvn verify` green. **inc-4b method-move DONE (2026-06-01):** `runLargePipeline` + `expandEpic` → `RunPlanningCoordinator` (byte-identical; added `taskExpander`/`coherencePlanner`/`pool`-bean deps + a `coerceToLeafFallback` static import); the orchestrator drives them via `planningCoordinator`; RunOrchestrator 4,324 → 3,957 LOC (under 4k for the first time, from the original 5,288), full `mvn verify` green. **inc-4 is now COMPLETE (4/4 phases: classifier-shadow + outline + large-pipeline + epic-expand).** **Remaining (each carries real entanglement — warrant a careful supervised pass):** inc 5 (`generateValidationReport` ×2 is ~250 LOC/~10 deps + `runStandardsEnforcement` ↔ test-pinned `toStandardsViolationRows`), inc 6 (lifecycle + gate/cancel/resume — highest risk). The clean low-risk extractions are done; the rest touch the run pool / gate semantics / test-pinned statics and are better done deliberately.
> **Owner:** active session. **Branch:** `fix/cross-org-invitee-access` (PR #196 — bundled with the invite fix per founder direction). Audit item #43.
> **File:** [`integration/RunOrchestrator.java`](../../src/main/java/com/navneet/executespec/integration/RunOrchestrator.java) — **5,288 LOC / 158 methods / ~70 injected dependencies**.

## Why this is its own session (do NOT script it)

This was attempted **twice** in the 2026-05-29 remediation session with text-based extraction tooling
(a naive line-brace counter, then a char-level brace matcher that ignores strings/comments/text-blocks).
**Both corrupted the file identically** — the class-closing brace ended up orphaned (`illegal start of type`
at EOF), caught at `mvn compile` and reverted with zero harm. Root cause is structural, not a tooling bug
to "fix and retry":

- **7 `submitRun` overloads** (lines 336–588) and **5 `runPipeline` overloads** (785–882) defeat every
  regex/line-based "find the method named X" heuristic — signature matching is ambiguous.
- The file **interleaves** pure `static` helpers with deeply stateful methods, so range-deletion math is
  fragile around the boundaries.
- ~70 collaborators mean almost every candidate method closes over instance state; a method cannot move
  without its dependencies moving with it.

**Mandate for the executing session:** use an **AST-aware refactor** (IntelliJ *Refactor → Move Members* /
*Extract Delegate*, or Eclipse JDT) — never text/sed/python. Compile after **every** extraction; run the
**full suite** (`mvn test`, ~1,406 tests) before **every** commit. One collaborator per commit.

## Invariants that MUST survive (from root `CLAUDE.md` → "Patterns: MUST follow")

1. **Ownership before async** — `createdByUserId` / `orgId` / `projectId` stamped on the row BEFORE the
   pipeline thread starts. Any extraction touching `submitRun` must preserve this ordering.
2. **Gate-loss fallback** — `approve()` with a missing gate auto-fails with a message.
3. **Async context propagation** — every `Thread.ofVirtual()/pool` runnable wrapped in
   `ContextSnapshotFactory.captureAll().wrap(...)` so traceId + RequestAttributes survive.
4. **`activeRuns` map discipline** — `ConcurrentMap<String,Future<?>>` (line 287) is the cancel/halt handle;
   register/unregister must stay paired or cancel/pause/resume break.
5. **Terminal-mirror events** — `eventPublisher` fires `PipelineEvent.RunCompleted/RunFailed`; the B5
   `ExecutionOrchestrator` listener depends on these. Keep publishing from the same points.
6. **Remote-tool / LOCAL-mode guards** — register/unregister + skip-clone + skip-auto-push branches
   (CLI/VS Code) are null/`isRemote`-guarded; keep them inert on the default path.

## Collaborator groups (extract in this order — low risk → high risk)

### Increment 1 — `RunPipelineSupport` (pure statics, no instance state) — LOWEST RISK ✅ DONE (2026-05-30)
> Extracted to package-private `integration/RunPipelineSupport` (`@Slf4j` for the one `log` user). RunOrchestrator reaches them via **static import** — so the ~33 call sites stayed untouched (safer than rewriting each). The 4 package-private statics below STAYED. RunOrchestrator 5,288 → 5,117 LOC; full suite green. Done by exact-string surgery + compile-after-every-step, **not** the brace-counting scripts that corrupted it before.

14 `private static` helpers. **Test-safe** (none are referenced by tests — they're private):
`deserializeRepoIds` (2765), `isTerminal` (3267), `computeRepoCountForEstimate` (3807),
`mapOverallToV1Status` (3943), `mapTaskStatusToV1` (4004), `truncate` (4392),
`describeReviewerFixes` (4449), `serializeRepoIds` (4539), `lastPathSegment` (4677),
`coerceToLeafFallback` (5201), `shortMsg` (5226), `readWorkspaceFromLog` (5233),
`listWorkspaceFiles` (5242), `providerForAccuracy` (5278).

> ⚠️ The 4 **package-private** statics — `clampHardCap` (3906), `resolveTerminalStatus` (3965),
> `toBreakingChangeIssues` (4320), `languageConventionHint` (4649) — are exercised by unit tests that call
> `RunOrchestrator.<name>(...)`. Either **leave them in RunOrchestrator** (recommended for inc. 1) or move
> them AND update the test references in the same commit. Do not move them silently.

### Increment 2 — `RunPersistenceWriter` (`@Component`, deps: `runRepo`, `taskRepo`, `runCostLogRepo`, `broadcaster`, `objectMapper`) ✅ DONE (2026-05-30)
> Moved **10** methods (byte-identical) + rewired ~20 call sites via a **compiler-guided** pass (delete defs → compiler pinpoints every call site → qualify with `persistenceWriter.`), which dodged the `r.setWorkspacePath(...)` entity-setter collision a blind replace-all would have hit. **`persistEstimate` deliberately deferred** — it's an estimation method (`costHistoryService`/`costTracker`/`apiKeyResolver` + the `clampHardCap` stayer), not a thin write. `updateStatus`'s SSE broadcast preserved via the injected `broadcaster`. RunOrchestrator 5,117 → 4,872 LOC; full suite 1432 green.

`persistPlan` (2022), `persistTasks` (2048), `applyExecutionResultToTasks` (2102), `persistReview` (2120),
`persistReviewFailure` (2150), `setWorkspacePath` (2165), `persistRunTitle` (3762), `persistEstimate` (3819),
`writeRunCostLog` (3911), `updateStatus` (3274), `reconstructExecResultFromTasks` (3232). ~11 methods.
Mostly thin DB writes — clean to move; watch that `updateStatus` also broadcasts SSE (`broadcaster`).

### Increment 3 — `RunContextComposer` (`@Component`, deps: `objectMapper`, `uiStyleResolver`, `projectArchitectureService`, `projectRepository`, `modelRegistry`) ✅ DONE (2026-05-30, multi-repo pair deferred to 3b)
> The plan's 9-dep list was aspirational — the actual methods touch only **5** collaborators. Moved **7** of the 9 (README pair, `writeAgentContext`, `buildDesignBlock`, `buildCodeIntelContext`, `normalizeProvider`, `defaultModelFor`) + the `CODEINTEL_*`/`DEFAULT_PROVIDER` constants; rewired 8 call sites (compiler-guided) + removed the now-dead `uiStyleResolver` field from the god-class. RunOrchestrator 4,872 → 4,601 LOC; full suite 1432 green. **Done (3b, 2026-06-01):** `buildMultiRepoSummary` + `buildLanguageParityBlock` moved to `RunContextComposer` byte-identically (+ a `projectRepoService` dep); the `languageConventionHint` stayer was relocated to `RunPipelineSupport` and `LanguageParityBlockTest` repointed to it; the 4 call sites rewired via `contextComposer.`. Full `mvn verify` green.

Prompt/context string builders, ~400 LOC, no run-lifecycle state:
`renderRunReadme` (3466), `ensureReadme` (3442), `writeAgentContext` (3742), `buildDesignBlock` (3779),
`buildCodeIntelContext` (4412), `buildMultiRepoSummary` (4556), `buildLanguageParityBlock` (4609),
`normalizeProvider` (4473), `defaultModelFor` (4528). Returns strings → easy to unit-test post-extraction.

### Increment 4 — `RunPlanningCoordinator` (`@Component`, deps: `smartClassifier`, `outlinePlanner`, `costTracker`, `runRepo`, `objectMapper`, `projectRepoService`, `projectMemoryProvider`, +`taskExpander`/`coherencePlanner`/`pool`) ✅ DONE — 4 of 4 (2026-05-30 + inc-4b 2026-06-01)
> Moved the **2 pool-free** phases — `runClassifierShadow` + `runOutlinePhase` — into the new `@Component`; rewired 3 call sites + removed the now-dead `smartClassifier` field. **`runLargePipeline` + `expandEpic` deliberately STAY** in RunOrchestrator: both fan out task expansion across the internally-owned `pool` (an `ExecutorService` field used by `runPipeline` + pause/resume too, **not** a bean), so a clean move first needs the pool lifted to a shared bean (follow-up `4b`). RunOrchestrator 4,601 → 4,456 LOC; full suite 1432 green.

The classify→outline→expand phase: `runClassifierShadow` (4699), `runOutlinePhase` (4802),
`runLargePipeline` (4862), `expandEpic` (5044). Large + parallel (TaskExpander fan-out) — extract as a unit;
`runPipeline` calls into it. Verify LARGE/LEGACY path with a hierarchical-plan eval after.

### Increment 5 — `RunValidationKickoff` (`@Component`) ⏳ PARTIAL — standards cluster DONE (2026-06-01)
**Standards cluster ✅ DONE (inc-5a, 2026-06-01):** `runStandardsEnforcement` + `collectWorkspaceFiles` + the test-pinned `toStandardsViolationRows` static + the `STANDARDS_*` constants moved byte-identically to a focused **`RunStandardsEnforcer`** `@Component` (deps `effectiveStandardsResolver` / `standardsRunner` / `standardsViolationRepository` / `projectRepository`, all removed from the god-class); the call site routes via `standardsEnforcer`; `RunOrchestratorEnterpriseEnforcementTest` repointed. RunOrchestrator 3,957 → 3,813 LOC; full `mvn verify` green.
**Remaining (inc-5b):** `generateValidationReport` ×2 + its `synthesiseReviewIssues` private-static — the fat (~12-collaborator, scoring-path) move; do it as its own careful pass (background-dispatched → preserve the async wrapping, invariant #3).

### Increment 6 — `RunLifecycleService` (`@Component`, deps: `gateStore`, `broadcaster`, the `activeRuns` map, `scmPushService`) — HIGHEST RISK
`cancel` (2212), `pause` (2285), `resume` (2334), `resumeAfterCrash` (2448), `runPipelineForResume` (2391),
`runPipelineFromExecuteCheckpoint` (2589), `finalizeRunAfterResume` (2695), `haltAllActiveRuns` (2802),
`forceFailTask` (2836), `retryTask` (2896), `retryValidation` (3015), `reReview` (3063),
`runReReviewBlocking` (3123). ~13 methods. Touches `activeRuns`, gate semantics, and PR-close-on-cancel.
**Do this last and alone** — invariants #2 and #4 live here. Consider keeping `activeRuns` ownership in
RunOrchestrator and passing a small interface to the service rather than sharing the map.

### Stays in RunOrchestrator (the irreducible core)
The 7 `submitRun` entry overloads (336–588), `shutdownPool` (315), the `RunPipelineRequest` record (2731),
and the heart — `runPipeline` ×5 (785–882, ~1,200 LOC) + `runPatchPipeline` (1792) + `terminate` ×2 (3301/3310)
+ `commitWorkspaceOutputs` (2185) + `isCodeGenOnlyRun` (3292). After all 6 increments this should land ~2,000–2,400 LOC.

## Per-increment protocol (non-negotiable)
1. AST-move one group (IntelliJ Move Members) → new `@Component` (or util class for inc. 1).
2. Wire the new collaborator as a `private final` constructor dep on RunOrchestrator (`@RequiredArgsConstructor`).
3. `JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn compile -q` → must be green.
4. `mvn test` (full ~1,406) → must be green (zero behaviour change is the bar).
5. Doc-hygiene: `integration/CLAUDE.md` + `docs/CODEMAP.md` (new class) + this file's status.
6. **Ask before push** (standing rule), one commit per increment.

## Expected outcome
RunOrchestrator: 5,288 → ~2,200 LOC. Six new focused, independently-testable collaborators. No behaviour
change (proven by the full suite per step). Removes the #1 backend god-class from `docs/tech-debt.md`.

# `harness/` — V2 pipeline orchestration

> Last reviewed: 2026-05-12 (1.0.0-M1).
>
> V1 `PipelineHarness` was deleted in deprecation P1-P6 (2026-04-13). The live entry point is `integration/RunOrchestrator`; `harness/` now holds the V2 sequential executor + the workspace + gate primitives that survived from the V1 era.

## Key files

| File | Purpose |
|------|---------|
| `SequentialExecutor.java` | Per-task tier-routed worker dispatch. For each task: compose system + user prompt → `WorkerAgent.run()` → if worker hit `completeTask`, run `buildProject` smoke (final task only on multi-task plans) → pass=SUCCESS / fail=ONE retry with build error injected. Aggregate: ALL_SUCCESS / PARTIAL_WITH_WARNINGS / FAILED / ABORTED. Per-task tier-config: LOW=LIGHTWEIGHT (30 iters / 4k tokens / 15-min wall-clock), MEDIUM=WORKHORSE (50 / 8k / 25-min), HIGH=PLANNING (75 / 16k / 45-min); project-run multiplier ×2. PATCH-mode per-provider caps: Anthropic 150 / OpenAI 100 / Gemini 120. **Soft-completion gate** (Fix C of `feature/worker-runaway-fixes`): when worker outcome is `COMPLETED_BY_STAGNATION` or `STOPPED_WITHOUT_COMPLETE` with `totalWriteCalls > 0`, falls through to the same smoke gate that COMPLETED uses — multi-task intermediate task with writes → `COMPLETE`; final smoke fail → `PARTIAL`. **2026-05-10:** `wallClockSeconds` per tier-config sibling to `maxIterations` (whichever fires first wins); `Outcome.WALL_CLOCK_EXCEEDED`. **`persistTaskStatus`** carries a defensive 4000-char cap on `failureReason` (V184 widens the column to TEXT). Also exposes the boot-time `execute(req, ResumeRequest)` overload that walks per-task status to decide SKIP_TERMINAL / RESUME_FROM_SNAPSHOT / RUN_FRESH on JVM restart. **2026-05-19:** `SYSTEM_PROMPT_BASE` carries a tests-are-mandatory directive (any task touching a function/endpoint/module must ship happy-path + error/edge tests before `completeTask`, or justify why untestable); exposed read-only via static `workerSystemPromptBase()` so tier-eval-v2 WORKHORSE cases drive the exact production prompt. |
| `WorkspaceInitService.java` | Creates fresh git repo for each run. Writes `.gitignore`, `.gitattributes`. Multi-repo runs clone each linked repo into `<workspace>/<runId>/repos/<name>/` via `cloneMultipleRepos`. Captures per-repo SHA snapshot into `run_repo_snapshot` (V100) for the "reset to run state" feature. |
| `GateStore.java` | Redis-backed plan-gate storage. 500ms poll, 2h TTL, 24h max wait timeout. `isPlanGateWaiting(runId)` detects when the pipeline thread is gone (server restart during AWAITING_APPROVAL) so `approve()` can fail cleanly with 409. |
| `UiStyleResolver.java` | Resolves design-system / UI style from user request or project defaults. 3-signal priority: explicit hint > planner `ui_style` > project type heuristic. |
| `DesignSystemProperties.java` | YAML config for the company design-system library. Filesystem-backed (54 design systems shipped). |
| `WorkerTaskRequest.java` | Per-task request shape — `taskId`, `complexity`, model + tier, `maxIterations`, `wallClockSeconds`, `requireBuildVerification`, `requireTestVerification` (patch-mode gate). |
| `ApprovalGate.java` | Plan-gate interface. |

## Pipeline sequencing (PRODUCTION mode)

1. `RunOrchestrator.submitRun(req)` persists the run row + estimate.
2. `SmartClassifierAgent.classify(spec)` → `PlanStrategy`.
3. Strategy router emits the plan via `OutlinePlannerAgent` (FULL_PLAN / SKELETON_ONLY) + optional `TaskExpanderAgent` parallel expansion + `CoherencePlannerAgent` patch.
4. **Plan approval gate** — `GateStore.openPlanGate(runId)`. SSE `GATE_OPENED` includes credits + estimated cost. PRODUCTION pauses; PROTOTYPE / `skipApproval` bypasses.
5. On approval: charges credits via `TierEnforcementService.assertAndIncrementRunCount(userId, credits)`. FREE blocks specs requiring >1 credit. BYOK orgs skip the platform cap entirely.
6. **`SequentialExecutor.execute(req)`** runs tasks one at a time. Tier-routed per-task model. Soft-completion gate covers `COMPLETED_BY_STAGNATION` / `STOPPED_WITHOUT_COMPLETE` paths. Final-task smoke + one-retry on fail.
7. `ReviewerAgent.review(workspace, plan)` — general + project-type-aware wiring pass + post-edit formatter + a fix-the-red-build third pass (flag `reviewer.third-pass.enabled`, default ON, V202) when the final build is still red.
8. `AccuracyAssessmentService` reads key files, scores requirements coverage (grades `testCoverageLevel` against explicit NONE/PARTIAL/GOOD/COMPREHENSIVE bands — no longer defaults to PARTIAL).
9. `ValidationReportService` computes `verifiedScore` — additive-weighted by default (flag `scoring.additive-v2`, V201): CODE_DELIVERED(30)+BUILD(25)+TESTS(20)+REVIEWER(20)+CRITICALS(5), weighted-average over fired slots, hard 0-floor when zero files generated. Legacy multiplicative formula stays behind the flag-off path.
10. **Mark run terminal** (`COMPLETE / PARTIAL / FAILED`) with `status_reason`.
11. **Background: auto-preview** (5-min TTL) + `TestGenOrchestrator` (plan → generate → compile → execute against live preview) + `MergeCompleted` event (drives Slack / JIRA / Confluence outbound comments).
12. **Auto-push:** `ScmPushService.pushMultiRepo` opens per-repo PRs with cross-link.

PATCH mode: `PatchAgent.runPatchPipeline` runs a single thin-agent loop, skipping classifier / planner / reviewer. Used by the 4 autofix surfaces + SWE-bench / ProgramBench adapters.

## Worker outcomes + resilience

| Outcome | Meaning |
|---------|---------|
| `COMPLETED` | Worker called `completeTask` cleanly. |
| `COMPLETED_BY_STAGNATION` | 12-iter non-write watchdog force-terminated; falls through to soft-completion gate + Lever B salvage. |
| `STOPPED_WITHOUT_COMPLETE` | Worker hit terminal without calling `completeTask`. Falls through to soft-completion gate. |
| `ITERATION_LIMIT_EXCEEDED` | Per-tier cap hit. |
| `WALL_CLOCK_EXCEEDED` | Per-tier wall-clock cap hit (2026-05-10). |
| `BUDGET_EXCEEDED` | Hard-cap dollar ceiling hit. Lever B salvage fires when 0 writes happened. |
| `ERROR` | Router or tool error. |

**Three nudge levers** in `WorkerAgent`:
- **A — force-edit watchdog** at 0.7×maxIter with `totalWriteCalls=0`: inject a UserMessage "commit to ANY edit now". Anthropic/Gemini cache-safe.
- **B — best-guess salvage** on iter-limit / budget-exceeded / stagnation-force when 0 writes happened: one final LLM call asking for the single best-guess writeFile/editFile.
- **C — completion-gate nudge** (2026-05-10) when the worker emits a "summary" status text past 0.5×maxIter without `completeTask` and `totalWriteCalls > 0`: inject a one-shot UserMessage telling it to call `completeTask` now or list the next concrete edit.

## Concurrency

- **Bounded thread pool** at the orchestrator entry: `ThreadPoolExecutor` with `CallerRunsPolicy` backpressure. Size = `PoolSizing.coreMultiple(1, 4, 16)`.
- **Per-task sequential** within a run (no intra-run parallelism).
- **Plan-gate wait** Redis-backed, 500ms polling, 24h max wait.
- **Async work** in virtual threads wrapped with `ContextSnapshotFactory.captureAll().wrap(runnable)` so traceId + RequestAttributes survive.
- **`AsyncAllowlistTest`** (ArchUnit) pins every `@Async` method to a curated allowlist (regression guard against the `@Async` + lazy-JPA class of bugs).

## Patterns

- Run ownership (`userId`, `orgId`, `projectId`) stamped BEFORE the async pipeline thread starts.
- `.agent-context/project.json` written from planner output — used by preview / deploy for project-type detection.
- `gitCommit` denied for workers via `ToolRegistry.bindForWorker(ctx)` — auto-push happens at run-end.
- Cost estimate + credits computed after planner emits the plan; persisted to `creditsCharged` + `hardCapUsd` via `RunOrchestrator.persistEstimate` (clamps with `userCostCap` when present; skips the platform cap entirely for BYOK orgs).
- Credits charged at plan approval (not submission). Refunded on FAILURE.
- Cost audit: `RunCostLog` entity persisted after each run completion — tracks estimated vs actual, margin, credits, budget-hit for pricing analytics.

# Deprecation Removal — V1 Harness Retirement

> **Status: ✅ shipped 2026-04-13** (commits `e3930414` P1 → `062310b2` P5/P6).
> All six phases landed on `feature/v2-next`. Test suite green: 205/205 (Chaos
> test still requires Docker; environmental). V2 batch implementation (separate
> plan) is now unblocked.
>
> Original plan retained below for archaeology — section text refers to the
> pre-execution state.

## Goal (achieved)

Delete every V1 class annotated `@Deprecated(since = "v2")` and the dead code paths they anchor. End state: one harness (V2), one runtime, one LLM router for non-tool-calling agents (LlmRouter retained without deprecation marker).

Original footprint:
- **339** `@Deprecated(since = "v2")` call-site/member annotations across **18+ classes**.
- `RunController` alone holds **19** `harness.*` calls into `PipelineHarness`.
- `LlmRouter` (V1, deprecated) still has **~10 live callers** across V1 agents that must be migrated before deletion.

## Non-goals

- Not rewriting behaviour. Every V1 feature that is still useful must have a V2 equivalent already shipped *or* be explicitly killed.
- Not changing the DB schema except to drop columns that become orphaned.
- Not deprecating anything V2. This is a V1-only sweep.
- Not touching shared infrastructure: `StateStore`, `PipelineRun` entity, `CostEstimator`, `ApiKeyResolver`, `WorkspaceInitService`, `GitHubRepoService`, `ModelResolver`, `PromptAssembler`, `TokenBudgetMonitor`, `UiStyleResolver`, `GateStore`, `ProviderRunPool`. These are live under V2.

## Sequencing constraints

1. **Batch re-implementation in V2 (`v2-batch-execution-plan.md`) happens after this plan's P3 deletes `batch/*`.** Doing them in the other order means two batch pollers competing for the same Shedlock key.
2. **Startup resume for V2 (`harness-v2-remaining-work.md` Gap 2) must land before this plan's P2.** P2 rewires `RunController.retry/pause/resume`; if V2 still can't resume a crashed run, those endpoints lose functionality.
3. Every phase must leave the build green + the eval harness (`scripts/v2-eval.sh`) green.

## Inventory of things to delete

### Entire packages
- `batch/` — 14 files (AnthropicBatchClient, OpenAiBatchClient, GoogleBatchClient, BatchClientRouter, BatchRequestBuilder, BatchPollService, BatchResumeService, BatchRequest/Result/Results/Status, BatchBuildResult, LlmBatchClient, BatchSubmissionException).
- `compiler/` — 3 files (CompilerAgent, CompileResult, CompilerProperties).

### Classes inside live packages

| File | Why dead under V2 |
|---|---|
| `harness/PipelineHarness.java` | V2 uses `V2RunOrchestrator` |
| `harness/SequentialTaskExecutor.java` | V2 uses `SequentialExecutorV2` |
| `harness/SubtaskDecomposer.java` | V2 has no subtask decomposition |
| `harness/TaskCompileSupervisor.java`, `SupervisionAction.java`, `TaskCompileDecision.java` | V2 has no compile supervisor; smoke test + reviewer replace it |
| `harness/AdaptiveIterationService.java` | V2 iteration caps are per-complexity constants |
| `harness/ProgressiveMergeValidator.java` | V2 auto-merges |
| `harness/MergeAnalysis.java`, `MergeDecision.java`, `MergeOutcome.java`, `MergeBlockedException.java`, `merge/` | same |
| `harness/GitMergeService.java` | V2 commits directly via `gitCommit` tool |
| `harness/SingleShotExecutor.java` | V2 planner has `SINGLE_SHOT` fast path |
| `harness/RunBudgetTracker.java`, `RunCostBudgetService.java` | V2 uses `V2RunCostTracker` |
| `harness/CoherenceGroupAnalyzer.java`, `LanguageValidator.java` | V1 pre-exec gating — V2 reviewer replaces |
| `harness/StartupRunResumer.java`, `PartialRunResumeService.java` | Will be replaced by `StartupRunResumerV2` (tracked in remaining-work.md Gap 2) |
| `harness/ApprovalGate.java` | V2 uses `GateStore` directly |
| `harness/BackgroundValidationService.java` | V2 runs validation inline in `V2RunOrchestrator.generateValidationReport` |
| `harness/WorkerPool.java`, `ProviderRunPool.java`, `ProviderRunPoolManager.java`, `ProviderQueueEntry.java` | **Keep** — still used for V2 run queueing (verify in P3) |
| `agent/PlannerAgent.java` | V2 uses `PlannerAgentV2` |
| `agent/WorkerAgent.java`, `WorkerToolCallbackFactory.java` | V2 uses `WorkerAgentV2` + `ToolRegistryV2` |
| `agent/RequestTriageService.java` | Folded into `PlannerAgentV2.classify()` |
| `agent/LlmRouter.java` | V2 uses `LlmRouterV2` — but has ~10 live callers outside V1 (see P4) |
| `agent/TokenBudgetMonitor.java`, `TokenBudgetPolicy.java` | **Keep** if V2 worker uses them; confirm in P3 |
| `agent/ContextWindowPolicy.java`, `TriageEscalationDetector.java`, `LlmResponseCleaner.java`, `LlmOutputParser.java` | V1-only parsing helpers |
| `reviewer/ReviewerAgentV2.java` | V2 uses `ReviewerAgentV3` |
| `reviewer/GapClosingService.java` | V2 has no gap closer (reviewer handles) |

### Columns / migrations to drop (evaluate in P5)

- `pipeline_run.harness_version` — if we're deleting V1 entirely, drop the column in a final migration. Keep the row value `'v2'` as a constant in `V2RunOrchestrator` (no read path needed).
- Any `batch_*` columns on `pipeline_run` that become orphaned once V2 batch replaces them (`batchProviderJobId`, `batchProgressTotal`, `batchProgressCompleted` — likely reused; confirm per-field in P3).

## Phased execution

> **All phases shipped on 2026-04-13.** Marker `✅` on each. Per-phase commits:
> - P1 docs sweep — `e3930414`
> - P2 RunController rewire — `be944a42`
> - P3 V1 deletion — `154169d9` (~20k LOC removed)
> - P4 LlmRouter @Deprecated removal + rollout gate retired — `9ce8dbb5`
> - P5/P6 schema cleanup + tests — `062310b2`
>
> Notable departures from the original plan:
> - **LlmRouter not deleted** in P4. With 11 non-tool-calling callers (testgen
>   stack, accuracy assessor, conversation, context, validation report, eval),
>   migrating each to `LlmRouterV2` (which requires `ExecutionContext`) was
>   judged higher risk than keeping `LlmRouter` as the shared text-completion
>   router. `@Deprecated(since="v2")` removed instead. V2 tool-calling agents
>   continue to use `LlmRouterV2`.
> - **`pipeline_run.harness_version` column kept** rather than dropped in P5 —
>   useful audit signal; new rows are always `"v2"`.

### ✅ P0 — Prerequisites (no code change; just confirm)

- [ ] `v2-batch-execution-plan.md` probe (P0 of that plan) completed — proves native-tool-calling in batch APIs works.
- [ ] `StartupRunResumerV2` designed (Gap 2); interface agreed.
- [ ] V2 eval matrix (`scripts/v2-eval.sh`) running green at baseline; results archived under `scripts/v2-eval-results/pre-deprecation-removal/`.

Deliverable: sign-off memo in this file's changelog. No code.

### ✅ P1 — Docs sweep (1 PR, zero-risk)

Scope: flip every doc that still presents V1 as a supported path.

- `CLAUDE.md` — remove the "V1 → V2 canary" language; state "V2 is the only harness".
- `docs/USER_MANUAL.md` — strip V1-only flows.
- `docs/JAVADOC.md` — same.
- `docs/plans/harness-v2-remaining-work.md` — update the open-gap tracker to reflect post-deprecation state.
- Add a top-of-CHANGELOG entry: "Breaking: V1 harness retired."

Estimated size: ~200 lines changed.

### ✅ P2 — Rewire `RunController` (1 PR, medium-high risk)

`RunController` has 19 `harness.*` calls. Each needs a resolution:

| Endpoint | Current V1 call | V2 resolution |
|---|---|---|
| `submitRun` | `harness.submitRun(...)` | Already routed through `HarnessVersionResolver` — call `V2RunOrchestrator.submit()` directly, remove V1 branch |
| `approve` | `harness.resolvePlanGate(...)` | `GateStore.setPlanGate()` (already what V2 does) |
| `clarify` | `harness.resolveClarificationGate(...)` | **Remove** — V2 has no clarification gate; return 410 Gone or delete route |
| `merge` | `harness.resolveMergeGate(...)` | **Remove** — V2 auto-merges |
| `retry` | `harness.restartRun(...)` | Wire to `V2RunOrchestrator.restart(runId)` (new method; depends on Gap 2) |
| `pause` + `resume` | `harness.pauseRun / resumeRun` | Wire to new `V2RunOrchestrator.pause / resume` (depends on Gap 2) |
| `restartWithNewGoal` | `harness.restartWithNewGoal(...)` | Submit a new run with the previous runId as `sourceRunId` — no harness call needed |
| `pushToGitHubManual` | `harness.pushToGitHubManual(run)` | Extract into a standalone `GitHubPushService.push(run)` — this is not harness business logic |
| `pushToScmManual` | `harness.pushToScmManual(run)` | Same — `ScmPushService` facade |
| `cancelRun` | `harness.cancelRun(runId)` | `V2RunOrchestrator.cancel(runId)` (new method — simple status flip + worker thread interrupt) |
| `retryPartialTask` | `harness.retryPartialTask(...)` | `SequentialExecutorV2.executeOne(task)` — already works; just rebind |
| `implementSuggestions` | `harness.implementSuggestions(...)` | Defer or delete; V2 apply-suggestion flow TBD; **recommend: 410 Gone until V2 ConversationAgent integration lands** |
| `reworkRun` | `harness.reworkRun(...)` | Defer; recommend: 410 Gone |
| `reopenMergeGate` | `harness.reopenMergeGate(...)` | Delete endpoint — no reopen when there's no merge gate |
| `retryBackgroundValidation` | `harness.retryBackgroundValidation(...)` | Wire to `V2RunOrchestrator.generateValidationReport()` |

Side tasks:
- Remove `@Autowired PipelineHarness harness` field.
- Remove `BackgroundValidationService` field; inline the one method into `V2RunOrchestrator`.
- `V2DeferredActionGuard` becomes unnecessary — every endpoint now has a real V2 implementation or is explicitly 410. Delete the guard.

Estimated size: ~400 lines net. Blast radius: high — this is the HTTP surface.

### ✅ P3 — Delete V1 harness + agents + batch + compiler (1 PR, medium risk after P2)

Only safe once P2 has committed and eval is still green.

Steps:
1. `git rm` on every class in the **"Inventory"** section above that is actually unreferenced after P2. Run `mvn dependency:analyze` + compile to confirm.
2. `git rm -rf src/main/java/com/navneet/executespec/batch/`.
3. `git rm -rf src/main/java/com/navneet/executespec/compiler/`.
4. Rip `HarnessVersionResolver` (no more versions) and its feature flag (`harness.version`, `harness.v2.canary_percent`).
5. Inside `harness/` — keep only the non-deprecated classes: `GateStore`, `WorkspaceInitService`, `UiStyleResolver`, `DesignSystemProperties`, `ExecutionMode` (may move to `v2/` if it belongs there), pool/queue classes *if V2 uses them* — verify each.
6. Delete the SSE event types that are V1-only (any `BATCH_*` events if V2 batch hasn't re-implemented them yet — coordinate with batch plan).
7. Prompt files: remove `prompts/planner.txt`, `prompts/worker.txt`, `prompts/compiler.txt`, `prompts/reviewer.txt`, `prompts/request-triage.txt`, `prompts/supervisor.txt`, `prompts/gap-closer.txt` (any prompt file that is V1-only). Retain `prompts/v2/*`, `prompts/test-*`, and prompt fragments.

Estimated size: ~10,000 lines deleted, ~400 added. Largest phase by raw LOC.

### ✅ P4 — Migrate V1 `LlmRouter` callers to `LlmRouterV2` (1 PR, high risk)

After P3, `LlmRouter` (V1) still exists because non-harness agents depend on it. List (from grep, excluding V1 dirs already deleted):

- `reviewer/AccuracyAssessmentService`
- `agent/ConversationAgent`
- `testgen/TestPlannerAgent`
- `testgen/TestWorkerAgent`
- `testgen/TestReportGenerator`
- `testgen/TestPreviewCoordinator` (indirect)
- `apitests/ApiTestSuiteAgent`
- `context/ContextAgent`
- `report/ValidationReportGenerator`
- `agent/ConflictResolverAgent` (V1; verify still used)
- `agent/PromptOptimiserAgent` (V1; verify still used)
- Eval stack: `eval/LlmJudgeValidator`, `eval/ModelEvaluator`

Three options per caller, decide on a per-class basis:

1. **Migrate** — switch the caller to `LlmRouterV2.call(LlmRequestV2)`. Cleanest; ties the caller into V2 metrics + cost tracking. Most callers only need plain completion, not tool calling, so the wiring is small.
2. **Facade** — introduce `LlmInvoker` interface with two implementations, keep `LlmRouter` for tool-less callers. Works as a stopgap but keeps the dual type alive.
3. **Delete the caller** — if the V1 agent has been obsoleted by V2 (e.g., `ConflictResolverAgent`, `PromptOptimiserAgent` — check usage). If unused, rm it.

Recommended: migrate all; introduce no facade. Each migration is ~20 LOC and enables `V2RunCostTracker` to bill the call.

After all callers migrate:
- `git rm src/main/java/com/navneet/executespec/agent/LlmRouter.java`.
- `git rm` related: `ContextWindowPolicy`, `TriageEscalationDetector`, `LlmResponseCleaner`, `LlmOutputParser` if unreferenced.

Estimated size: ~600 lines changed across ~12 files. Risk is that the eval judge + some test agents depend on V1's prompt-cleanup logic.

### ✅ P5 — Schema + prompt + config cleanup (1 PR, low risk)

- Flyway migration `V80__drop_harness_version.sql`: drop `pipeline_run.harness_version` column.
- Remove `harness.version` + `harness.v2.canary_percent` flag seeds (migration + `application.yml`).
- `ModelResolver` YAML: drop matrix rows referencing `PlannerAgent`, `WorkerAgent`, `CompilerAgent`, `ReviewerAgentV2`. Keep rows for `PlannerAgentV2`, `WorkerAgentV2`, `ReviewerAgentV3`.
- Drop any `@Deprecated(since = "v2")` that survived migration — they shouldn't exist at this point.
- Remove V1-specific metric names from `CustomMetricsConfig` that no longer emit.
- Update `docs/plans/harness-v2-remaining-work.md` → close out the "batch (Gap 1)" dependency note.
- CHANGELOG: bullet the deletion.

Estimated size: ~150 lines.

### ✅ P6 — Post-cleanup verification (no code, just gates)

- Run full `scripts/v2-eval.sh --concurrency 3` matrix. Must match pre-removal accuracy within ±2%.
- Run a random sample of production-style runs end-to-end. Confirm SSE, cost, preview, push-to-github all still work.
- `mvn dependency:analyze` must show zero "Unused declared dependencies" introduced by this change.
- `grep -rn "@Deprecated(since = \"v2\")"` must return zero hits.

## Test strategy

- **Build-green after every phase** — P1 and P2 each land as separate PRs; P3 only starts when P2 is in main for at least 24h.
- **Integration tests** — the existing `V2RunOrchestratorIT` + `/api/v2/runs` test must continue to pass. Add one per newly-wired `RunController` endpoint in P2 (retry, pause, resume, cancel, retryPartialTask, retryBackgroundValidation).
- **Eval canary** — run the 9-cell `v2-eval.sh` matrix at the end of P2 and P3. If accuracy regresses > 5%, roll back and investigate.
- **Manual smoke** — submit a run in the UI, approve the plan, watch it complete, push to GitHub, open the validation report. End of P2, P3, P4 each.
- **Observability check** — Prometheus meter count before / after. New V2 meters should equal old V1 meters minus V1-only series.

## Risks

| Risk | Mitigation |
|---|---|
| `LlmRouter` V1 prompt-cleanup logic silently drops noise that V2 router doesn't handle | In P4, before deletion, add a regression fixture per caller: same prompt in = same response out. Any divergence gets patched into `LlmRouterV2` first. |
| Batch deletion in P3 blocks users mid-run | Gate P3 on a zero-active-batch-runs check. Run the migration at a traffic low. |
| `RunController` endpoints we mark 410 Gone (clarify, reopenMergeGate) are still used by old frontend builds | Coordinate with frontend; ensure no `api.ts` function calls them. Grep confirms before merging P2. |
| Shedlock leftovers from `BatchPollService` | Explicitly delete the Shedlock row in P3's migration. |
| Developer confusion from half-deleted packages | Phases are strict — P2 fully merged before P3 starts. No parallel sub-branches. |
| Observability regression — missing V1 meters cause alert noise | Pre-announce meter retirements; scrub dashboards in P5. |

## Files that will change

### Deleted
- `batch/*` (14 files)
- `compiler/*` (3 files)
- `harness/PipelineHarness.java`, `SequentialTaskExecutor.java`, `SubtaskDecomposer.java`, `TaskCompileSupervisor.java`, `SupervisionAction.java`, `TaskCompileDecision.java`, `AdaptiveIterationService.java`, `ProgressiveMergeValidator.java`, `MergeAnalysis.java`, `MergeDecision.java`, `MergeOutcome.java`, `MergeBlockedException.java`, `merge/`, `GitMergeService.java`, `SingleShotExecutor.java`, `RunBudgetTracker.java`, `RunCostBudgetService.java`, `CoherenceGroupAnalyzer.java`, `LanguageValidator.java`, `StartupRunResumer.java`, `PartialRunResumeService.java`, `ApprovalGate.java`, `BackgroundValidationService.java`
- `agent/PlannerAgent.java`, `WorkerAgent.java`, `WorkerToolCallbackFactory.java`, `RequestTriageService.java`, `LlmRouter.java`, `ContextWindowPolicy.java`, `TriageEscalationDetector.java`, `LlmResponseCleaner.java`, `LlmOutputParser.java`
- `reviewer/ReviewerAgentV2.java`, `GapClosingService.java`
- `v2/rollout/HarnessVersionResolver.java` + flag seed migrations
- `v2/integration/V2DeferredActionGuard.java`
- `prompts/planner.txt`, `worker.txt`, `compiler.txt`, `reviewer.txt`, `request-triage.txt`, `supervisor.txt`, `gap-closer.txt` (V1 prompt set)

### Modified
- `api/controller/RunController.java` (rewire all 19 `harness.*` calls)
- `v2/integration/V2RunOrchestrator.java` (add `restart`, `pause`, `resume`, `cancel`)
- Every caller of V1 `LlmRouter` (~12 files in P4)
- `resources/application.yml` (drop model-matrix rows, drop flag defaults)
- Flyway: `V80__drop_harness_version.sql`
- `CLAUDE.md`, `docs/USER_MANUAL.md`, `docs/JAVADOC.md`, `CHANGELOG.md`, `VERSION`

Net delta estimate: **−10,500 / +1,100 LOC** across ~25 new deletes and ~15 modified files.

## Suggested commit / PR boundaries

| PR | Content | Reviewer focus |
|---|---|---|
| **#1** | P1 docs sweep | Accuracy of prose |
| **#2** | P2 RunController rewire (depends on Gap 2) | HTTP surface; integration tests |
| **#3** | P3 V1 deletion | Build-green; eval accuracy |
| **#4** | P4 LlmRouter migration | Regression fixtures per caller |
| **#5** | P5 schema + config cleanup | Migration reversibility |
| **#6** | P6 verification report | None — sign-off memo only |

Each PR is fully rollback-able — P3's deletion PR is a pure revert if eval regresses.

## Exit criteria

- [ ] Zero `@Deprecated(since = "v2")` in source.
- [ ] `batch/` and `compiler/` packages do not exist.
- [ ] `harness/` contains only V2-supporting classes (Gate, Workspace, UI style, design props, pools if used).
- [ ] Eval accuracy within ±2% of the pre-removal baseline.
- [ ] CLAUDE.md no longer mentions V1.
- [ ] CHANGELOG + version bump shipped.

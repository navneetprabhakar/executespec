# harness/ — Pipeline Orchestration

Core pipeline orchestration layer. Manages run lifecycle from submission to completion.

## Key Files
- `PipelineHarness.java` — Main orchestrator. Runs async pipeline: plan → approve → work → compile → merge → **COMPLETE** → auto-preview → background validation (review → test gen → test exec → report). Writes `.agent-context/project.json` with planner-declared project type in `initAgentContext()`.
- `SequentialTaskExecutor.java` — Executes tasks in tiered parallel groups via WorkerAgent. Model resolved from matrix via ModelResolver. Validation deferred to review phase.
- `WorkspaceInitService.java` — Creates fresh git repo for each run. Writes `.gitignore`, `.gitattributes`.
- `ApprovalGate.java` — Plan approval gate interface
- `GateStore.java` — Redis-backed gate storage (500ms poll, 2h TTL, 24h max wait timeout)
- `GitMergeService.java` — Merges task branches into main. Progressive merge after each task.
- `SubtaskDecomposer.java` — Decomposes tasks into subtasks at execution time. Two strategies: (1) **File-based** — multi-file tasks (2+ owned files) split into per-file subtasks, one `writeFile` per subtask. (2) **Phase-based** — complex single-file tasks (MEDIUM/HIGH complexity, 1 owned file) split into method-level phases (MEDIUM → 2 phases, HIGH → 3 phases). Phase 1 creates the file skeleton; subsequent phases `readFile` then `writeFile` to extend it with more methods. Rework tasks excluded from both strategies.
- `LanguageValidator.java` — Blocks unsupported languages at submission
- `UiStyleResolver.java` — Resolves UI style from user request or project defaults

## Pipeline Sequencing (PRODUCTION mode)
1. PlannerAgent generates plan (includes `project_type`)
2. Cost estimation: `CostEstimator.estimateWithCredits()` computes credits (`max(1, ceil(estimate × 1.10 / $5.0))`), persists `creditsCharged` + `hardCapUsd` on the run
3. Plan approval gate (Redis-backed, user approves via UI). SSE `GATE_OPENED` includes credits + estimated cost.
   - On approval: charges N credits via `TierEnforcementService.assertAndIncrementRunCount(userId, credits)`
   - FREE tier: blocks specs requiring >1 credit (suggests top-up or tier upgrade)
   - Insufficient credits: auto-rejects with message to purchase top-up or upgrade tier
   - BYOK/ENTERPRISE: skip credit charge (unlimited)
4. `initAgentContext()` writes `.agent-context/` files including `project.json`
5. SequentialTaskExecutor runs tasks in tiered groups (SubtaskDecomposer splits multi-file tasks into per-file subtasks; complex single-file tasks into method-level phases)
6. CompilerAgent fixes compilation errors
7. Auto-merge all task branches to main
8. **Mark run COMPLETE** (status set, `completedAt` recorded)
9. **Auto-start preview** (5-min TTL) for both PROTOTYPE and PRODUCTION modes
10. **Background validation thread** (`bg-validation-{runId}` virtual thread):
   - ReviewerAgentV2 inspects + auto-fixes
   - Re-validation after auto-fix
   - TestGenOrchestrator generates integration tests (async)
   - TestPreviewCoordinator reuses existing preview → executes tests
   - ValidationReport generated
   - MergeCompleted event published (triggers JIRA/Confluence outbound comments via IntegrationManager)
   - Cost calculated and persisted
   - Cleanup: `backgroundValidationRuns.remove()`, `runKeyStore.remove()`, `broadcaster.complete()`

**PROTOTYPE mode:** Skips review/test gen. Still publishes MergeCompleted (with null review) so integration comments fire.

**Existing-repo GitHub push:** `pushToGitHubIfConfigured()` creates branch `executespec/{runId-short}`, pushes to branch (not main), opens PR via `GitHubRepoService.createPullRequest()`, stores PR URL in `PipelineRun.githubPrUrl`.

**Batch execution flow:** At plan approval, user may choose BATCH mode (if `batchAvailable`). `approvedExecutionMode=BATCH` triggers batch submission via `BatchClientRouter` → run enters `QUEUED_BATCH` → `IN_PROGRESS_BATCH`. `BatchPollService` polls every 30s (@Scheduled + @SchedulerLock). On completion, `BatchResumeService` writes files to workspace, marks tasks, publishes `BatchCompleteEvent`. PipelineHarness `@EventListener @Async onBatchComplete()` resumes from compile step → merge → COMPLETE → validation.

**Key design:** User sees COMPLETE immediately after compile+merge. Preview is available while validation runs in background. SSE stream stays open until background validation finishes.

## Cost Budget Enforcement
- **Cost-proportional credits:** `credits = max(1, ceil(bufferedEstimate / $5.0))` where buffer = 10% retry margin
- **Silent hard cap:** `credits × $5.0 × 1.5` — no progressive warnings. User approved cost upfront at plan gate.
- `RunCostBudgetService` uses Caffeine caches (6h TTL, 1000 max). Only `isOverBudget()` as silent safety net.
- Mid-task cost check: WorkerAgent checks `isOverBudget(runId)` every 5 LLM iterations; emits `budgetExceeded` SSE event and stops cleanly
- **Refund on FAILURE:** `RunQuotaRefundListener` reads `creditsCharged` from run and refunds N credits via `TierEnforcementService.decrementRunCount(userId, credits)`
- **Cost audit:** `RunCostLog` entity persisted after each run completion — tracks estimated, actual, margin, credits, budget-hit for pricing analytics
- **`creditsConfirmed` validation:** Approve endpoint echoes credit count; 409 Conflict if mismatch (prevents stale approvals after repricing)

## Background Validation Lifecycle
- `backgroundValidationRuns` (ConcurrentHashMap.newKeySet()) tracks runs with active background threads
- Main `executeRunAsync()` finally block checks this set — defers cleanup for background runs
- Background thread's own `finally` block handles: `backgroundValidationRuns.remove()`, `runKeyStore.remove()`, `runBudgetTracker.remove()`, `broadcaster.complete()`
- Preview NOT stopped after test execution (stays up for user's full TTL)

## Concurrency Controls
- **Thread pool:** Bounded `ThreadPoolExecutor` (core=maxRuns, max=maxRuns*3, CallerRunsPolicy) — replaces unbounded `newCachedThreadPool`
- **Run semaphore:** `runSlots.tryAcquire()` on ALL entry points: `startIterationRun()`, `resumeRun()`, `executeApplySuggestion()`. Released in `finally` block.
- **Gate timeout:** All gate waits (`awaitPlanGate`, `awaitMergeGate`, `awaitClarificationGate`) have 24h max wait. Throws `InterruptedException` on deadline expiry.

## Patterns
- Pipeline runs in virtual thread, MUST wrap with `ContextSnapshotFactory.captureAll()`
- Run ownership (userId, orgId, projectId) stamped BEFORE async thread starts
- `doReviewAndMerge()` is the centralized post-task flow for resume/retry paths (NOT updated for background pattern — runs synchronous)
- Cost estimate + credits computed after plan creation via `CostEstimator.estimateWithCredits()`, persisted to `creditsCharged` + `hardCapUsd`
- Credits charged at plan approval, NOT at submission (`assertCanSubmitRun` at submit, `assertAndIncrementRunCount(userId, credits)` at approval)
- `.agent-context/project.json` written from planner output — used by preview/deploy for project type detection

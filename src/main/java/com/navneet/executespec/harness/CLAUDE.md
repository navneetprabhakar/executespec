# harness/ — Pipeline Orchestration

Core pipeline orchestration layer. Manages run lifecycle from submission to completion.

## Key Files
- `PipelineHarness.java` — Main orchestrator. Runs async pipeline: plan → approve → work → compile → merge → **COMPLETE** → auto-preview → background validation (review → test gen → test exec → report). Writes `.agent-context/project.json` with planner-declared project type in `initAgentContext()`.
- `SequentialTaskExecutor.java` — Executes tasks in tiered parallel groups via WorkerAgent. Model resolved from matrix via ModelResolver. Validation deferred to review phase.
- `WorkspaceInitService.java` — Creates fresh git repo for each run. Writes `.gitignore`, `.gitattributes`.
- `ApprovalGate.java` — Plan approval gate interface
- `GateStore.java` — Redis-backed gate storage (500ms poll, 2h TTL)
- `GitMergeService.java` — Merges task branches into main. Progressive merge after each task.
- `LanguageValidator.java` — Blocks unsupported languages at submission
- `UiStyleResolver.java` — Resolves UI style from user request or project defaults

## Pipeline Sequencing (PRODUCTION mode)
1. PlannerAgent generates plan (includes `project_type`)
2. Plan approval gate (Redis-backed, user approves via UI)
3. `initAgentContext()` writes `.agent-context/` files including `project.json`
4. SequentialTaskExecutor runs tasks in tiered groups
5. CompilerAgent fixes compilation errors
6. Auto-merge all task branches to main
7. **Mark run COMPLETE** (status set, `completedAt` recorded)
8. **Auto-start preview** (10-min TTL) for both PROTOTYPE and PRODUCTION modes
9. **Background validation thread** (`bg-validation-{runId}` virtual thread):
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

**Key design:** User sees COMPLETE immediately after compile+merge. Preview is available while validation runs in background. SSE stream stays open until background validation finishes.

## Background Validation Lifecycle
- `backgroundValidationRuns` (ConcurrentHashMap.newKeySet()) tracks runs with active background threads
- Main `executeRunAsync()` finally block checks this set — defers cleanup for background runs
- Background thread's own `finally` block handles: `backgroundValidationRuns.remove()`, `runKeyStore.remove()`, `runBudgetTracker.remove()`, `broadcaster.complete()`
- Preview NOT stopped after test execution (stays up for user's full TTL)

## Patterns
- Pipeline runs in virtual thread, MUST wrap with `ContextSnapshotFactory.captureAll()`
- Run ownership (userId, orgId, projectId) stamped BEFORE async thread starts
- `doReviewAndMerge()` is the centralized post-task flow for resume/retry paths (NOT updated for background pattern — runs synchronous)
- Cost estimate computed after plan creation via CostEstimator
- `.agent-context/project.json` written from planner output — used by preview/deploy for project type detection

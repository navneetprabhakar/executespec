# harness/ — Pipeline Orchestration

Core pipeline orchestration layer. Manages run lifecycle from submission to completion.

## Key Files
- `PipelineHarness.java` — Main orchestrator. Runs async pipeline: plan → approve → work → compile → merge → review → test gen → preview → test exec → report → complete. Writes `.agent-context/project.json` with planner-declared project type in `initAgentContext()`.
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
7. ReviewerAgentV2 inspects + auto-fixes
8. TestGenOrchestrator generates integration tests
9. TestPreviewCoordinator starts preview AFTER tests compile → executes tests
10. ValidationReport generated → COMPLETE

**Key change:** Preview is NOT started in parallel with test gen (old behavior). TestPreviewCoordinator owns preview lifecycle for test execution, ensuring full TTL availability.

## Patterns
- Pipeline runs in virtual thread, MUST wrap with `ContextSnapshotFactory.captureAll()`
- Run ownership (userId, orgId, projectId) stamped BEFORE async thread starts
- `doReviewAndMerge()` is the centralized post-task flow — don't bypass it
- Cost estimate computed after plan creation via CostEstimator
- `.agent-context/project.json` written from planner output — used by preview/deploy for project type detection

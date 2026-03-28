# harness/ — Pipeline Orchestration

Core pipeline orchestration layer. Manages run lifecycle from submission to completion.

## Key Files
- `PipelineHarness.java` — Main orchestrator. Runs async pipeline: plan → work → compile → merge → review → report → complete
- `SequentialTaskExecutor.java` — Executes tasks in parallel groups via WorkerAgent. Model resolved from matrix via ModelResolver (no tier ceiling). Validation deferred to review phase.
- `ApprovalGate.java` — Plan approval gate interface
- `GateStore.java` — Redis-backed gate storage (500ms poll, 2h TTL)
- `GitMergeService.java` — Merges task branches into main. Progressive merge after each task.
- `LanguageValidator.java` — Blocks unsupported languages at submission (added 2026-03-26)

## Patterns
- Pipeline runs in virtual thread, MUST wrap with `ContextSnapshotFactory.captureAll()`
- Run ownership (userId, orgId, projectId) stamped BEFORE async thread starts
- `doReviewAndMerge()` is the centralized post-task flow — don't bypass it
- Cost estimate computed after plan creation via CostEstimator

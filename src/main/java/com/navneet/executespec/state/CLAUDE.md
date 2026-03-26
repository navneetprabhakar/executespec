# state/ — Run State Management

Persistence layer for pipeline runs, tasks, and all mutable state.

## Key Files
- `StateStore.java` — Central state manager. All run/task state mutations go through here. 1500+ lines.
- `entity/PipelineRun.java` — JPA entity for runs. Key fields: status, plan, costEstimate, actualCostUsd, failureReason.
- `entity/AgentTask.java` — JPA entity for individual tasks within a run.
- `repository/PipelineRunRepository.java` — JPA repository for PipelineRun.
- `InboxService.java` — User inbox/notification service.

## Patterns
- StateStore is the single source of truth — no direct repository access from agents
- Task compaction recorded via `recordCompaction()` (emits SSE event)
- Token usage tracked per run via `addTokensToRun()`

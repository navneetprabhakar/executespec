# state/ — Run State Management

Persistence layer for pipeline runs, tasks, and all mutable state.

## Key Files
- `StateStore.java` — Central state manager. All run/task state mutations go through here. 1500+ lines.
- `entity/PipelineRun.java` — JPA entity for runs. Key fields: status, plan, costEstimate, actualCostUsd, failureReason.
- `entity/AgentTask.java` — JPA entity for individual tasks within a run.
- `entity/PlanVersion.java` — JPA entity for plan version history. Columns widened in V45 (id/run_id/created_by → VARCHAR(255), source → VARCHAR(64)).
- `repository/PipelineRunRepository.java` — JPA repository for PipelineRun.
- `InboxService.java` — User inbox/notification service.

## DB Migrations
- V1–V44: Core schema through report improvements
- V45: Widened `plan_version` columns from VARCHAR(36) to VARCHAR(255) for IDs and VARCHAR(64) for source (prevents truncation errors)

## Patterns
- StateStore is the single source of truth — no direct repository access from agents
- Task compaction recorded via `recordCompaction()` (emits SSE event)
- Token usage tracked per run via `addTokensToRun()`
- Plan versioning via `savePlanVersion()` / `incrementPlanVersion()` — used by apply-suggestion flows

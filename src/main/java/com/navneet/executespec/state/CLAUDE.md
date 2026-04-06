# state/ — Run State Management

Persistence layer for pipeline runs, tasks, and all mutable state.

## Key Files
- `StateStore.java` — Central state manager. All run/task state mutations go through here. 1500+ lines.
- `entity/PipelineRun.java` — JPA entity for runs. Key fields: status, plan, costEstimate, actualCostUsd, failureReason, githubPrUrl.
- `entity/AgentTask.java` — JPA entity for individual tasks within a run. Includes subtask decomposition fields: `parentTaskId` (null for top-level), `subtaskIndex` (1-based), `subtaskTotal`.
- `entity/PlanVersion.java` — JPA entity for plan version history. Columns widened in V45 (id/run_id/created_by → VARCHAR(255), source → VARCHAR(64)).
- `repository/PipelineRunRepository.java` — JPA repository for PipelineRun. Includes `addTokens()` atomic SQL update (no read-modify-write race).
- `InboxService.java` — User inbox/notification service.

## DB Migrations
- V1–V44: Core schema through report improvements
- V45: Widened `plan_version` columns (VARCHAR(255) for IDs, VARCHAR(64) for source)
- V46: Stripe and payment gateway support
- V47: OAuth support (GitHub, Google login)
- V48: View mode column
- V49: Feature flags
- V50: Free tier restrictions
- V51: Force logout flag
- V52: `github_pr_url VARCHAR(512)` on `pipeline_run`
- V53–V56: Further schema updates
- V58: Subtask support (`parent_task_id`, `subtask_index`, `subtask_total` columns on `agent_task`)

## Patterns
- StateStore is the single source of truth — no direct repository access from agents
- Task compaction recorded via `recordCompaction()` (emits SSE event)
- Token usage tracked per run via atomic `addTokens()` SQL update (no read-modify-write race)
- Quota enforcement: `TierEnforcementService.assertAndIncrementRunCount()` uses pessimistic lock + single TX (TOCTOU fix)
- Plan versioning via `savePlanVersion()` / `incrementPlanVersion()` — used by apply-suggestion flows

# state/ — Run State Management

Persistence layer for pipeline runs, tasks, and all mutable state.

## Key Files
- `StateStore.java` — Central state manager. All run/task state mutations go through here. 1500+ lines.
- `entity/PipelineRun.java` — JPA entity for runs. Key fields: status, plan, costEstimate, actualCostUsd, creditsCharged, hardCapUsd, failureReason, githubPrUrl, approvedExecutionMode, batchId, batchProvider, batchCredits, batchAvailable, creditSavings, batchProgressCompleted, batchProgressTotal, batchSubmittedAt.
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
- V59: Bitbucket support
- V60: Preview registry (`preview_registry`, `preview_metrics` tables)
- V60.1: Cost-proportional credits (`credits_charged`, `hard_cap_usd` on `pipeline_run`; `cached_input_cost_per_1m` on `model_pricing`; new `run_cost_log` audit table)
- V61: Batch execution support (`approved_execution_mode`, `batch_id`, `batch_provider`, `batch_progress_*`, `batch_submitted_at`, `batch_credits`, `batch_available`, `credit_savings` on `pipeline_run`; new `notification` table)

## Patterns
- StateStore is the single source of truth — no direct repository access from agents
- Task compaction recorded via `recordCompaction()` (emits SSE event)
- Token usage tracked per run via atomic `addTokens()` SQL update (no read-modify-write race)
- Quota enforcement: `TierEnforcementService.assertAndIncrementRunCount(userId, credits)` uses pessimistic lock + single TX (TOCTOU fix). Multi-credit support: increments by N at plan approval (not submission). Refunds via `decrementRunCount(userId, credits)` on FAILURE.
- Plan versioning via `savePlanVersion()` / `incrementPlanVersion()` — used by apply-suggestion flows

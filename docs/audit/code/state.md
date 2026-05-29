# Audit: `state/` — persistence layer (entities + repositories + StateStore)

Status: **DONE** · Updated 2026-05-29 · 37 files / 4,676 lines · all read in full

## Files
- `StateStore.java` (2,155) — central state-mutation chokepoint ✓
- `entity/PipelineRun.java` (597) — the core run entity (~80 columns) ✓ · `entity/AgentTask.java` (148) ✓ · `entity/Deployment.java` (235) ✓ · 11 more entities (RunRepo/RunRecovery/MergeConflict/FileLock/PromptAuditEntry/AgentMessage/AgentInbox/GateEvent/CompactionEvent/PlanVersion/IntegrationRun/IntegrationEventLog/RunRepoSnapshot) ✓
- `repository/PipelineRunRepository.java` (336) ✓ + 13 more repositories ✓
- `InboxService.java` (103) · `RunTitleBackfillService.java` (78) · `RunMode.java` · `ApplySource.java` ✓

## What it does
The persistence layer for runs/tasks/all mutable state. `StateStore` is the documented "single source of truth — no direct repository access from agents." Entities are tenant-scoped via `@EntityListeners(TenantInterceptor.class)` (verify the interceptor actually scopes queries — Tier 5 `tenant/`).

## Findings

### Tech-debt / architecture
- 🟡 **Dual run-lifecycle state machines.** `StateStore` carries its **own** `markComplete` / `markFailed` / `markPartial` / `cancelRun` / `pauseRun` / `resumeRun` / `finaliseRun` ([StateStore.java:246-1013](../../../src/main/java/com/navneet/executespec/state/StateStore.java)) that overlap — with **different, sometimes destructive semantics** — RunOrchestrator's V2 `terminate` / `cancel` / `pause` / `resume` ([integration.md](integration.md)). E.g. `StateStore.pauseRun` flips status PAUSED **and marks every non-terminal task FAILED**; `RunOrchestrator.pause` sets `pause_requested` + interrupts and **preserves** task state. `StateStore.markComplete` re-derives PARTIAL from task rows; RunOrchestrator uses `resolveTerminalStatus`. This is V1-vs-V2 dual-state-machine drift — two ways to end/pause a run with divergent effects. Verify which surfaces call which (controllers vs orchestrator) and whether the StateStore variants are live or legacy; a caller hitting the wrong one corrupts state.
- 🟡 **`PipelineRun` is a ~80-column god-entity / kitchen-sink table** — accretes a column per feature wave (per-agent tokens, per-phase hierarchical-planner tokens, batch, pause/resume, GCS archive, chat, assessment, figma, selectedRepoIds, Bitbucket creds, …). Hard to reason about; many columns are null for any given run. Typical for a central run table but a candidate for satellite tables (batch_*, pause_*, gcs_* could be 1:1 child rows).
- 🟡 **V1 multi-agent coordination is vestigial in the sequential V2 path.** `InboxService` (agent-to-agent inbox: file-lock conflicts, provider-fallback notices, coordination), `MergeConflict` (parallel-branch merge resolution), `FileLock` (per-file worker locks), `AgentMessage` (inter-agent messages) were built for V1's parallel workers. V2's `SequentialExecutor` runs tasks one at a time, so there are no concurrent file-lock conflicts or branch merges to coordinate. Likely dead/low-use — verify callers; candidates for removal alongside the V1 deletion.

### Cost / efficiency
- 🟢 **Atomic token + status updates** — `runRepo.addTokens()` / `updateStatusAndClearGate` / `clearCurrentGate` / `updateApprovedExecutionMode` are `@Modifying` JPQL updates (no read-modify-write race), and `AgentTaskRepository.lockAndStart` flips PENDING→WORKING in one UPDATE (prevents double-execution). Good concurrency hygiene.
- 🟡 **Admin analytics do in-memory full-table scans.** `computeModelComparison` / `computeModelComparisonForOrg` / `computeUserRunsSummary*` call `runRepo.findAll()` / `taskRepo.findAll()` then stream-filter by org/date in heap ([StateStore.java:1144,1204,1302,1317](../../../src/main/java/com/navneet/executespec/state/StateStore.java)) — O(all rows) loaded per dashboard request. The plain metric counts correctly use repo `count`/`SUM` queries; only the model-comparison + per-user-stats + outage paths regress to findAll(). Push the org/date filter into the query as the table grows.
- 🟢 `PipelineRunRepository` already has accurate per-user/project/org `SUM(actualCostUsd)` queries (`sumActualCostBy{User,Project,Org}AndSince`) used by the per-project spend cap + billing dashboards — **reinforces the [report.md](report.md) finding** that `UsageReportService` should sum `actual_cost_usd` instead of re-estimating tokens at stale Haiku rates.

### Security / correctness
- 🟡 **Plaintext credentials on `PipelineRun`** — `github_pat` (widened to TEXT in V208 for longer tokens — definitively cleartext) + `bitbucket_app_password` stored unencrypted, same theme as the integration OAuth tokens ([integration.md](integration.md)). Per-run credential capture is by design (immutability), but at-rest they're cleartext.
- 🟡 `PipelineRun.aiProvider` DB default = `"OPENAI"` + `getAiProviderEnum()` falls back to OPENAI — the stale-OpenAI-default pattern again (CLAUDE.md says Anthropic). Latent (submit path stamps a provider).
- 🟢 Run lifecycle filters are careful: `listRuns` always hides soft-deleted, archive toggle, filters NULL-startedAt synthetic benchmark rows; idempotent archive/soft-delete/restore with state guards.
- 📝 3rd occurrence of the stale `ReviewerAgentV2` reference (`StateStore.saveReviewV2` comment) — same dead class name as [reviewer.md](reviewer.md)/[report.md](report.md).
- 🟢 Boilerplate JPA entities/repos are clean; `Deployment`/`RunRepo` store timestamps as `String` (minor — no DB temporal ordering/indexing).

## Cross-refs
Mutated by every pipeline path → [integration.md](integration.md) (`RunOrchestrator`), [harness.md](harness.md) (`SequentialExecutor` task locking + persistTaskStatus). Cost columns → [cost.md](cost.md), [report.md](report.md). Tenant scoping → `tenant/TenantInterceptor` (Tier 5). Quota/credit enforcement on these rows → `billing/`+`entitlements/` (Tier 5). Idempotency log + IntegrationRun → [integration.md](integration.md) (`IntegrationManager`).

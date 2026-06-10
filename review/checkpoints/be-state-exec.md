# be-state-exec — raw findings

Reviewed on branch `benchmark` (working tree as-is; the uncommitted changes — `agent/LlmRouter.java`, `router/*`, `application.yml`, `router/FireworksModelMapper.java` — touch none of the reviewed packages). Every `.java` file in scope was read in full. All dead/unused claims below were grep-verified repo-wide (src/main, src/test, frontend/). No docs were used as evidence.

## 1. Inventory

| Package | Files | LOC | Read |
|---|---|---|---|
| `state/` (StateStore, 14 entities, 16 repos, InboxService, RunTitleBackfillService, ApplySource, RunMode) | 37 | ~4,730 | 100% |
| `execution/` (3 controllers, 11 services, 5 entities, 5 repos, 5 DTOs, 6 models, ExecutionEventTypes) | 38 | ~5,030 | 100% |
| `run/` (RunIdempotencyService, RunLocalExecutionFlag, RunSkipPushFlag, sync/RunSyncService) | 4 | 334 | 100% |
| `recovery/` (StartupRunResumer) | 1 | 352 | 100% |
| `sse/` (SseBroadcaster, RunEvent, RunDetailSseService) | 3 | 851 | 100% |
| `batch/` (3 provider clients, router, coordinator, poll, resume, 3 records, interface) | 11 | 1,267 | 100% |
| `saga/` (SideEffectOutbox, Entry, Repository, Executor) | 4 | 435 | 100% |
| Matching tests (state 1, recovery 1, run 2, batch 4, execution 8) | 16 | ~2,034 | 100% |

Total main: 98 files / ~12.9K LOC.

## 2. Findings

Real bugs first, then risks/perf/smells. file:line on everything.

### Bugs

**B1. [BUG] The saga outbox executor cannot process any entry — double defect.**
`saga/SideEffectExecutor.java:58-66` — `processDue()` (the `@Scheduled` entry point, not transactional) calls `this.processEntry(entry)`. `processEntry` is `@Transactional` (`:68`), but the self-invocation bypasses the Spring proxy, so it runs with **no transaction**. Its first statement is `repository.markProcessing(entry.getId())` (`:71`) — a `@Modifying` JPQL update with no `@Transactional` on the repository method (`saga/SideEffectOutboxRepository.java:23-27`). Executing an update query without an active transaction throws `TransactionRequiredException` at runtime, *before* the try/catch at `:77`, killing the whole scheduled tick on the first due entry. Net effect: `GCS_ARCHIVE` retries (enqueued by `deployment/WorkspaceGcsUploadListener.java:112`) and `COMPENSATE_GITHUB_BRANCH` orphan-branch cleanup (enqueued by `integration/scm/ScmPushService.java:319`) never execute. Compare `PipelineRunRepository.java:23-28` and `IntegrationEventLogRepository.java:16-19`, which correctly put `@Transactional` on their `@Modifying` methods.
Second, independent defect: `findDue` selects `status IN ('PENDING','PROCESSING')` (`SideEffectOutboxRepository.java:18`) — clearly intended to recover entries orphaned in PROCESSING by a crash — but `markProcessing` only claims `WHERE status = 'PENDING'` (`:26`). A crash-orphaned PROCESSING row is therefore re-fetched every 30 s, logged "already claimed — skipping" (`SideEffectExecutor.java:73`), and never processed or failed. Zombie + log spam forever.
Test coverage does not catch this: `chaos/ChaosScenariosTest` only asserts the outbox *entry is created*; nothing exercises `processEntry` via the scheduled path.

**B2. [BUG] `@Async` lost via self-invocation in `ExecutionOrchestrator` — plan kickoff blocks the HTTP request thread for up to 4 hours.**
`execution/service/ExecutionOrchestrator.java:395-401` — `kickoffPlanning()` (plain method, called from `ExecutionController.generatePlan:185`) invokes `this.dispatchUnified(executionRunId)`. `dispatchUnified` is `@Async` (`:424`) but self-invocation bypasses the proxy, so the V2 submit + the 5-second poll loop (`:493-531`, deadline 4 h) run synchronously on the controller thread. The "202 Accepted" at `ExecutionController.java:186` is only returned after planning reaches AWAITING_APPROVAL (minutes) or the 4 h deadline. Same pattern at boot: `onApplicationReady` (`:124-141`) self-invokes `pollAndMirrorV2(...)` and `executeApprovedPlan(...)` — both `@Async`-annotated, both run synchronously, so multiple resumable executions are recovered **serially**, each potentially holding the boot-resume thread for up to 4 h before the next is re-armed.

**B3. [BUG] `AdminReconciliationService` force-FAILs healthy waiting/long-running executions, and the failure can become permanent across a JVM restart.**
`execution/service/AdminReconciliationService.java:145-161` — the 15-minute sweep marks FAILED every non-terminal `ExecutionRun` whose `updated_at` is >60 min old. Nothing writes the row during a unified-dispatch EXECUTING phase (the only writes are at plan-mirror and terminal — `ExecutionOrchestrator.java:505-515, 627-632`; `ExecutionRun` `updatedAt` only changes via `@PreUpdate`, `ExecutionRun.java:204-207`), so **any execution whose V2 run takes >60 min is reconciled to FAILED while still running**. Same for `PAUSED` (awaiting a user decision — explicitly a wait-for-human state) and `ARCH_REVIEW`/plan-approval states where the user simply takes >1 h. The boot sweep (`:112-117`) does the same at a 1-minute cutoff for everything except EXECUTING — including PAUSED.
The wedge: once FAILED, the event-driven mirror refuses to update the row (`ExecutionOrchestrator.mirrorLaunchOnV2Terminal:177` filters out COMPLETE/FAILED/ABORTED), and boot-resume only re-arms polls for rows still in EXECUTING (`:115`). So if the JVM restarts after the sweep fired, a V2 run that later completes (PR opened, cost incurred) is never mirrored — the launch stays FAILED forever. (In the same-JVM case the still-alive `pollAndMirrorV2` thread eventually overwrites FAILED at terminal, masking the bug.)

**B4. [BUG] SSE replay sequence numbers break after 500 events per run.**
`sse/SseBroadcaster.java:258-268` — `appendToReplayLog` returns `rightPush(...) − 1` as the event's sequence/SSE id, then immediately `trim(key, -500, -1)`. Once a run exceeds `MAX_REPLAY_EVENTS` (=500; a normal worker run emits far more than 500 events), the list size plateaus, so consecutive events get the **same id (~499/500)**, and `replayMissedEvents` (`:306-332`) treats `Last-Event-ID` as an absolute list index into a list whose head has been trimmed away — reconnecting clients receive the wrong slice (silently duplicated/missed events). The id is also what `GET /api/runs/{id}/event-log` ordering implicitly relies on.

**B5. [BUG] GDPR soft-delete exclusion list uses V1-era status names — in-flight V2 runs can be tombstoned mid-pipeline.**
`state/repository/PipelineRunRepository.java:316-321` — `softDeleteByUserId` excludes `'PENDING','PLANNING','IN_PROGRESS','IN_PROGRESS_BATCH'`. Grep-verified: nothing in main ever writes `IN_PROGRESS`/`IN_PROGRESS_BATCH`/`PENDING` to `pipeline_run.status`; the live in-flight vocabulary is `QUEUED/WORKING/REVIEWING/AWAITING_APPROVAL/STUCK/PAUSED`. A right-to-erasure request while a run is WORKING stamps `deleted_at` on a row the pipeline thread still holds in memory — its next full-entity `save()` writes `deletedAt=null` back (un-deleting), or the run continues invisible. Either way inconsistent.

**B6. [BUG] (execution lane) `RunEvent` constructor argument mixups — payload lands in the `taskId` field.**
`sse/RunEvent.java:5-11` declares `(type, runId, taskId, payload, timestamp)`. `ExecutionOrchestrator.emit` (`:1125-1128`) and `SolutionExecutionService.emit` (`:538-541`) pass payload as the **3rd** argument (taskId); `ExecutionPlannerService.emit` (`:389-394`) passes it as the **4th**. So e.g. `EXEC_COMPENSATION_UPDATE`'s JSON payload (built at `ExecutionOrchestrator.java:1013-1024`, documented as payload at `ExecutionEventTypes.java:47-51`) is emitted in `taskId`; the (orphaned) consumer `frontend/src/hooks/useExecutionSse.ts:128-141` reads `evt.payload` → always null. Two emit helpers in the same package disagree on the wire contract.

**B7. [BUG] (execution lane, legacy path) SKIP/RETRY decisions never restart dispatch.**
`ExecutionOrchestrator.executeApprovedPlan:763-768` returns when the run goes PAUSED, with the comment "resumption happens via SolutionExecutionService.processDecision". But `processDecision` (`SolutionExecutionService.java:474-512`) and `resume()` (`:528-536`) only flip the solution to QUEUED and the run to EXECUTING — **no code path re-enters the layer-dispatch loop**. The retried solution sits QUEUED forever unless the user separately calls `POST /api/executions/{id}/resume`. The frontend store (`frontend/src/stores/useExecutionStore.ts:110-112`) does not chain a resume after decide.

**B8. [BUG] A live IDE feature depends on the dead lane's gates.**
`GET /api/projects/{id}/prs/{n}/reviews` lives in `ExecutionController.java:289-300` behind `requireFeatureEnabled` — i.e. behind the `execution.view.enabled` feature flag (seeded `'false'` by `V89__execution_view.sql:117`; `FeatureFlagService.isEnabled` returns false for absent/false → controller throws 404) AND `tier_config.execution_view_enabled` (TRUE only for ENTERPRISE per V89:123, plus whatever V122/V144 seeded for TEAM). The IDE's "Address Review" flow calls exactly this endpoint (`frontend/src/ide/IdeProjectTabs.tsx:628` → `lib/api/reviews.ts:46-49`). For any env where the flag wasn't hand-flipped, or any FREE/PRO user, the fetch 404s/403s. This endpoint has nothing to do with the Execution View and should not sit behind its gates.

**B9. [BUG] Live Discovery surfaces feed runs into a dead-end lane.**
`readymade/service/ReadymadeOrchestrator.java:186-193` and `migration/service/MigrationOrchestrator.java:194` (both reachable from the live IDE — `frontend/src/ide/IdeDiscovery.tsx:993` `instantiateLibrarySolution`, `:1163` `migrationApply`) call `executionOrchestrator.createExecution(...)` + `triggerAnalysis(...)`. The created `ExecutionRun` reaches `ARCH_REVIEW`, after which the only ways forward are `ExecutionController` endpoints that (a) 404 behind the OFF flag and (b) have no UI — `frontend/src/App.tsx:75` actively rewrites `/projects/{id}/executions/...` URLs back to the project page, and `useExecutionStore`/`useExecutionSse`/`execution-api.ts` are imported by nothing (grep-verified). The row is then killed by B3's boot sweep (1 min after the next deploy) or the 60-min sweep. Customer-visible effect: "Run this solution" produces a launch that silently becomes FAILED.

**B10. [BUG-lite] Dead `@Transactional` via lifecycle/self-invocation (two more sites).**
- `execution/service/ExecutionTotalsBackfillJob.java:44-46` — `@Transactional` on a `@PostConstruct` method is never applied (bean not yet proxied); the backfill also runs a full-table scan synchronously on **every** startup, blocking boot.
- `recovery/StartupRunResumer.java:101-113` — `onApplicationReady` (@Async) self-invokes `scanAndRecover()` whose `@Transactional` is therefore bypassed. Functionally benign here (per-save TXs), but the annotation is dead and misleading.

**B11. [BUG] (latent — unified dispatch bypasses it) Cross-tenant repo fuzzy-match in the legacy planner.**
`execution/service/ExecutionPlannerService.java:333-349` — when the LLM returns a non-UUID repoId, the fallback matcher runs `projectRepoRepo.findAll()` (every repo of every org) and matches by `contains()` in both directions. A short repo name can match another tenant's repo, wiring a foreign `repoId` into the solution's repo mapping (clone/push target). Only reachable when `execution.unified-dispatch.enabled=false` (default true, `ExecutionOrchestrator.java:74-75`), but it is a tenant-isolation landmine sitting in live code.

### Risks

**R1. [RISK] `SseBroadcaster` emitter counter drift (non-atomic prune).** `sse/SseBroadcaster.java:251-254`, `:380-383`, `:146-149` — three places do `clients.removeAll(dead); totalEmitterCount.addAndGet(-dead.size())`. Two threads detecting the same dead emitter concurrently both decrement, so the gauge/global-cap counter drifts downward (can go negative), weakening `MAX_TOTAL_EMITTERS` enforcement. Should decrement only per *successfully removed* emitter (cf. `remove()` at `:343-348`, which does it right).

**R2. [RISK] `emitters` map leaks channel entries.** `SseBroadcaster.java:134` creates a `CopyOnWriteArrayList` per channel; only `complete(runId)` removes the map entry (`:226`). `complete` is never called for `exec:*` channels (grep: completers are run-lifecycle, TB, benchmark, resumer only) nor for `codereview:*` — empty lists accumulate per channel id for the JVM lifetime.

**R3. [RISK] `INSTANCE_ID` built from the loopback hostname** (`SseBroadcaster.java:72-73`) — effectively `"localhost-<pid>"` on typical hosts. Two VMs with colliding PIDs would suppress each other's cross-instance SSE delivery (echo-suppression false positive). `getLocalHost()` was probably intended.

**R4. [RISK] `PipelineRun` has no `@Version` and mixes atomic-JPQL writers with full-entity `save()` writers.** E.g. `StateStore.setAgentTokens` (`StateStore.java:419-460`) does read-accumulate-save on the same row that `addTokens` updates atomically (`PipelineRunRepository.java:23-28`); `updateHeartbeat` (`:2091-2100`) saves the entire ~100-column entity every 30 s per run (its javadoc claims "lightweight update to avoid loading the full entity" — it does the opposite). Any concurrent JPQL update between a stale load and a full save is silently overwritten. The codebase already paid for this class of bug on `ExecutionRun` (hence `ExecutionRunCasUpdater`); `PipelineRun` has neither the version column nor the CAS helper.

**R5. [RISK] `ExecutionSolution` has no `@Version`.** `SolutionExecutionService.executeSolution:87-96` / `processDecision:482-509` / `onV2Complete:243-291` write the same row from the dispatch thread and the user-decision thread — last write wins, e.g. a user SKIP can be flipped back to RUNNING.

**R6. [RISK] `RunSyncService.runGit` has no process timeout** (`run/sync/RunSyncService.java:154-162`) — a hung `git fetch origin` (network black-hole) blocks the calling request thread indefinitely. `detectDefaultBranch` (`:138-152`) likewise.

**R7. [RISK] Timestamps stored as `String`.** `state/entity/Deployment.java:82-92` (`ttlExpiresAt`, `createdAt`, `deployedAt`, `deletedAt` are `Instant.toString()` strings, while `autoDestroyAt:143` is a real `Instant` in the same entity) and `state/entity/RunRepo.java:46-50`. `DeploymentRepository.findByStatusAndTtlExpiresAtBefore` (`:26`) does lexicographic comparison on ISO strings — works only because the prefix is fixed-width; sub-second ordering is wrong (un-padded fractional seconds), and no DB date arithmetic is possible. Live consumer: `deployment/DeploymentCleanupJob.java:30`.

**R8. [RISK] `AgentTask` PK is `taskId` alone** (`state/entity/AgentTask.java:23`) with cross-run uniqueness enforced only by the 8-hex-char runId prefix convention (`StateStore.savePlan:91-96` — the comment itself documents the overwrite hazard). JPA `save()` is an upsert: any collision silently overwrites another run's task. No unique constraint on `(run_id, task_id)`.

**R9. [RISK] Batch clients are reasoned-not-smoked, with concrete traps already visible:** `batch/OpenAiBatchClient.java:221` sends `max_tokens` — current OpenAI chat models (the configured `gpt-5.4` family) reject it in favor of `max_completion_tokens`. `batch/GeminiBatchClient.java:91,114,126` passes the API key as a `?key=` query parameter (leaks into access logs/proxies) and `:208-209` skips the schema sanitization `GeminiNativeToolClient` needs. `AnthropicBatchClient.mapProcessingStatus` (`:161-168`) can never yield FAILED/CANCELLED. All acknowledged in javadocs; flagged because the flag-flip would meet them immediately.

**R10. [RISK] `ExecutionController.createExecution` single-active-execution gate is check-then-act** (`:89-121`) with no DB constraint — two concurrent submits both pass `findActiveByProjectId` and create two active executions.

**R11. [RISK] `RunIdempotencyService` uses field injection** (`run/RunIdempotencyService.java:42-43`, `@Autowired(required=false)`) — violates the project's own constructor-injection rule; an `ObjectProvider<StringRedisTemplate>` constructor parameter achieves the same test ergonomics.

**R12. [RISK] `RunTitleBackfillService` re-runs on every boot and its counter lies** (`state/RunTitleBackfillService.java:30-63`): runs that can never be backfilled (no title in plan JSON) are reloaded and "processed" every startup; `updated++` increments even when nothing was set; uses `jakarta.transaction.Transactional` while the rest of the package uses Spring's annotation.

### Perf

**P1. [PERF] No index on `agent_task(run_id)`.** Grep of all migrations: the only indexes on `agent_task` are `tenant_id` and `parent_task_id` (+ RLS policy). `findByRunId` backs hot paths — `StateStore.markFailed/cancelRun` (`:256, :915`), `RunDetailSseService` (per SSE trigger event!), `SolutionExecutionService.isWorkerOutcomeClean:600`, `StartupRunResumer.isExecutorDone:275`, the timeline endpoint — every call is a sequential scan on a table that grows with every task ever run.

**P2. [PERF] `RunDetailSseService.onRunEvent`** (`sse/RunDetailSseService.java:56-78`) re-fetches the run + **all** its tasks and serializes a full `RunDetail` for every TASK_STATUS/RUN_STATUS/... event of every run. On a busy run this multiplies P1's unindexed scan by the event rate.

**P3. [PERF] N+1s in `ProjectLaunchController`:** `overview` (`:207-231`) loads ≤500 runs then queries solutions **per run**; `projectPrs` (`:150-165`) queries PR records per pipeline-run (≤500). `listLaunches` (`:73-101`) additionally has broken merge-pagination: page N of `execution_run` is merged with the first 500 `pipeline_run` rows, re-sorted, then re-sliced — items repeat/skip across pages and `totalElements` is wrong. (Lane is flag-dead, but `/launches`/`/overview` were its list endpoints.)

**P4. [PERF] `ExecutionSolution` double-EAGER:** `dependencies` (self-referencing ManyToMany, `ExecutionSolution.java:125-131`) and `repoMappings` (`:137-143`) are both EAGER → loading any solution chain-loads its transitive dependency graph + mappings. The repository/controller comments still describe them as lazy (`ExecutionRunRepository.java:30-36`, `ExecutionController.java:132-137` "force-init the lazy dependencies") — stale w.r.t. the entity.

**P5. [PERF] `StateStore.computeMetrics`/`computeMetricsForOrg`/date variants** (`:1003-1116`) issue 8–10 separate COUNT/SUM queries each where one `GROUP BY status` would do. Admin-only, low frequency.

**P6. [PERF] Poll-based mirroring holds threads:** `dispatchUnified`/`pollAndMirrorV2` poll the DB every 5 s for up to 4 h per execution (`ExecutionOrchestrator.java:85-86`), `pollUntilTerminal` every 10 s for up to 2 h per solution (`SolutionExecutionService.java:53-54`) — even though the event-driven mirror (B5-audit listener, `:161-171`) already exists. Also `parseSelectedRepoIds` news up an `ObjectMapper` per call (`:656`).

### Smells / inconsistencies / duplication

- [SMELL] `StateStore` is `@Transactional` at class level with one redundant method-level `@Transactional` (`:413`); statuses are raw strings everywhere; `markComplete` keys behavior off `taskId.contains("_gap_")` (`:245`) — magic substring.
- [INCONSISTENT] `PipelineRun.archivedAt` javadoc says archive "is irreversible — cannot be unarchived" (`PipelineRun.java:485-488`) while `StateStore.unarchiveRun:842` exists; `chatMessageCount` javadoc claims "capped at 20" (`:427-431`) — no such cap in this layer.
- [INCONSISTENT] `ExecutionOrchestrator.approveArchDoc:372-380` saves a controller-loaded stale entity directly — the only write path in the class that bypasses `ExecutionRunCasUpdater`, the exact pattern the 2026-04-26 hotfix comments say always goes through CAS.
- [INCONSISTENT] `ExecutionController.requireRunAccess:374` checks org match only, while `requireProjectAccess:358-362` honors cross-org `ProjectMember` — an accepted invitee can list executions via the project route but 403s on detail. Also `:377` mutates `updatedAt` inside an access check (never flushed — dead statement).
- [INCONSISTENT] `mirrorV2Terminal` maps V2 PARTIAL→V3 COMPLETE (`:608-612`, documented) yet `ExecutionRunRepository.findCompletedWithZeroTotals:117-120` still queries for an ExecutionRun status `'PARTIAL'` that nothing writes.
- [INCONSISTENT] `softDeleteByUserId` aside (B5), `findActiveByTriggeredByFinding` (`PipelineRunRepository.java:331-335`) excludes `'ABORTED'` — a status never written to `pipeline_run`.
- [DUPLICATION] `StateStore.findRunsByStatusIn` (`:570`) and `findRunsByStatuses` (`:1358`) are identical (and both dead, see §3). `RunSkipPushFlag`/`RunLocalExecutionFlag` are acknowledged copy-mirrors.
- [SMELL] `serializeRepoIds` hand-builds JSON from **user-supplied** `req.repoIds()` claiming "every entry is a UUID-shaped string" without validating (`ExecutionOrchestrator.java:270-278`); `emitCompensation` hand-escapes only quotes (`:1013-1024`).
- [SMELL] `dispatchUnified` writes a fake `totalEstimatedTokens=0` "proxy" (`:509-514`).
- [SMELL] `RunEvent` factories encode structured data as pipe-delimited strings (`gateOpenedWithCredits:73-78`, `budgetWarning:312-339`, etc.) — a stringly-typed protocol the frontend must parse by position.
- [SMELL] `ModelRegistry.workhouseModelFor` — typo'd API name ("workhouse") used at 10 call sites including `StateStore.java:1142,1203`.
- [SMELL] `detectTechStack` keyword `"container"` ⇒ docker badge (`StateStore.java:2026`) — "DI container" false-positives.
- [LAYERING] `ExecutionJiraSync` does raw `Map`-typed JIRA REST with `RestTemplate` while the batch clients use `RestClient` — two HTTP stacks in adjacent packages; `ExecutionPlannerService` loads its prompt via `ClassPathResource` (`:90-92`) instead of the `PromptAssembler` convention, and resolves an agent name ("PlannerAgent") that is **absent from `application.yml` `agent-tier-map`** — `ModelResolver` logs a warning and defaults it to WORKHORSE (`router/ModelResolver.java:60-64`), contradicting the class javadoc's "PLANNING-tier" claim.

## 3. Dead code & junk (grep-verified)

**Execution lane reachability verdict — effectively dead weight with two live tendrils, plus always-on background residue:**
- HTTP surface: every `ExecutionController` + `ProjectLaunchController` route is gated by `execution.view.enabled`, seeded `'false'` (`V89__execution_view.sql:117`; `FeatureFlagService.isEnabled` = false unless flipped at runtime) + tier gate `tier_config.execution_view_enabled` (ENTERPRISE only per V89/V90; V140 revoked MAX).
- Frontend: `lib/execution-api.ts` is imported only by `hooks/useExecutionSse.ts` and `stores/useExecutionStore.ts`, and **those two are imported by nothing** (grep across frontend/src). `App.tsx:75` rewrites `/projects/{id}/executions/...` URLs back to `/projects/{id}`. The execution UI was deleted with the Signal chrome; the API/store/hook/types files are orphans.
- Live tendrils: (1) `GET /api/projects/{id}/prs/{n}/reviews` used by the IDE Address-Review flow (finding B8); (2) `ReadymadeOrchestrator`/`MigrationOrchestrator` create `ExecutionRun`s from the live IDE Discovery page (finding B9) — into a lane with no UI to advance them.
- Always-on residue regardless of flag: `ExecutionOrchestrator.onApplicationReady` boot scan, `onV2RunCompleted/onV2RunFailed` listeners (one `findFirstByPipelineRunId` DB probe per V2 terminal event, every run), `AdminReconciliationService` boot + 15-min sweeps, `ExecutionTotalsBackfillJob` full scan each boot, `BatchPollService` 30-s no-op tick, `SideEffectExecutor` 30-s tick.
- Within-lane dead pieces (zero callers, grep): `IncrementalCodeMapService` (whole class), `model/InterfaceContract`, `ExecutionRunRepository.findDetailById`, `CrossRepoContextService.CONTRACT_DIFF_CODEMAP`/`CONTRACT_TEST_ANCHOR`, `ExecutionDecision` record (never instantiated — used only as a constants holder), 8 of 23 `ExecutionEventTypes` constants (`EXEC_REPO_CODEMAP_STARTED/DONE`, `EXEC_REPO_ARCH_STARTED/DONE`, `EXEC_CONFLICT_DETECTED`, `EXEC_SOLUTION_VALIDATING`, `EXEC_SOLUTION_PR_RAISED`, `EXEC_SOLUTION_LOG`). `ExecutionBranchService`'s git tags are name-strings only — "Actual git ops (tag / revert) are deferred to follow-up work" (`:21`) never landed, so `createFinalPr` opens PRs from an `exec/<id>` branch nothing ever pushed.

**StateStore: ~55 of ~95 public methods have zero callers outside the class (grep `\.<method>(` across src/main, excluding StateStore itself).** Dead families:
- V1 stuck/recovery/escalation: `markRetrying`, `markRetryingWithFeedback`, `markStuckDetected`, `markRunStuck`, `markTaskForRetry`, `escalateTask`, `setEscalationLevel`, `updateLastActivity`, `getWorkingTasks`, `getActiveTasksForRun`, `findRunsByStatuses`, `findRunsByStatusIn`, `getOutageQueuedRuns`, `blockTask`, `failTask`, `recordLlmFailure`, `forceCompactionFlag`, `findResumableRun` — their javadocs still cite `OrphanedRunWatchdog`, deleted in commit `154169d9` ("P3: delete V1 harness…").
- Subtask decomposition: `createSubtasks`, `getSubtasks`, `completeParentFromSubtasks`.
- Batch-era: `markTaskComplete`, `markTaskFailed`, `setTaskModelProvider`, `resetTaskForRetry`, `updateTaskTokens`, `setPromptHash`, `setBatchInfo`, `updateBatchProgress`, `setBatchCostFields` → `pipeline_run.batch_id/batch_provider/batch_progress_*/batch_credits/...` columns receive **no writes** in current main code.
- Plan/review/chat/pause: `savePlan`, `recreateTasksFromPlan`, `markApproved`, `markRejected`, `markReviewing`, `saveReview`, `saveReviewV2`, `getReviewReport`, `getReviewReportV2` (test-only), `markComplete`, `markPartial`, `cancelRun`, `pauseRun`, `resumeRun`, `saveChatMessage`, `getChatHistory`, `createIterationRun`, `setCostEstimate`, `setUiStyle`, `setAgentTokens`, `clearCurrentGate`, `updateHeartbeat`, `markFailed`-siblings, `recordGateOpened`, `recordGateResolved`, `recordCompaction`, `appendTaskLog`, `getTaskEventLog`, `logMessage` (test-only), `updateTaskReadmeStatus`, `saveTask`, `setActualCost`, `getPendingTasks`, `getIncompleteTasks`, `findRunsToArchive`. The V2 orchestrator family (`RunPersistenceWriter` etc.) clearly superseded these; StateStore survives mostly as `findRun/save/addTokensToRun/completeTask/lockTask/listRuns/archive-delete-restore/metrics/GCS-sweep` plus analytics.
- Dead injected dependencies inside StateStore itself: `recoveryRepo` and `promptAuditRepo` are constructor-injected and never referenced (`StateStore.java:45-46`).

**Write-dead tables (no producer in main, grep):** `agent_message` (writers `logMessage`/`appendTaskLog` are dead; `RunController.java:1108` still reads it for the timeline → always empty for new runs), `gate_event` + `compaction_event` (recorders dead; `buildTimeline` reads them), `merge_conflict` (`MergeConflictRepository` has **zero** importers), `run_recovery` (read by `PromptPatternAnalyser` + `RunController`, written by nobody), `file_lock` (`FileLockTool` is only read via `FileSystemTool.checkFileLock`; nothing acquires locks — `tool/package-info.java:21` itself: "No FileLockTool — V2 is sequential"). `agent_inbox` is written only via `FallbackLlmRouter.sendProviderFallback` (legacy router); `InboxService.getFallbackEvents`/`getDeliveredMessages` have zero callers; `AgentTaskRepository.lockIfPending` and `CompactionEventRepository.findByTaskIdOrderByOccurredAtAsc` zero callers.

**batch/ package: inert scaffold, self-aware.** `BatchCoordinator.submit` throws `UnsupportedOperationException` (`:48-50`), `BatchResumeService.resume` throws (`:26-27`), `BatchPollService` ticks a no-op every 30 s (`:30-37`), and **no class outside `batch/` references any of them** (grep). The three provider clients are real code with unit tests but unreachable; `BatchJob.submittedAt/lastPolledAt` are always null (`AnthropicBatchClient.java:105-111` et al.). The `harness.v2.batch_enabled` flag gates a path that ends in a throw.

**Stale comments quoted (no TODO/FIXME markers exist in any scoped package — grep clean):**
- `BatchClient.java:11-12`: "implementations land in Plan 5 P2/P3" — they landed; coordinator wiring didn't.
- `CrossRepoContextService.java:21`: "Layers (2) and (3) arrive when the diff inspector lands" — never landed; constants dead.
- `ExecutionBranchService.java:20-21`: "Actual git ops (tag / revert) are deferred to follow-up work" — still deferred; tags are fiction.
- `ExecutionController.java:132-134`: "Force-init the lazy dependencies collection" — `dependencies` is EAGER (`ExecutionSolution.java:125`).
- `StateStore.java:1362` (`getActiveTasksForRun`) and `RunRecoveryRepository.java:14-16`: "used by OrphanedRunWatchdog" — class deleted.
- `PipelineRun.java:485-488`: archive "irreversible" — `unarchiveRun` exists.

## 4. Model/LLM touchpoints

- `ExecutionPlannerService.java:126` resolves agent `"PlannerAgent"` — **not present** in `application.yml model-matrix.agent-tier-map` (which has `OutlinePlannerAgent`) → `ModelResolver` warn + WORKHORSE default. Calls `FallbackLlmRouter.callWithFallback(..., NO_FALLBACK, 0, false)` (`:144-153`); prompt loaded raw from `prompts/execution_planner.txt` (`:91`).
- Hardcoded pricing: `ExecutionPlannerService.parsePlan:268-271` estimates cost at a flat `$0.00001/token` ($10/M blended); `Deployment.create:211` hardcodes `estimatedCostUsd = 0.008`; `SolutionSpec` default `estimatedTokens=30_000` (`:259`).
- `StateStore.computeModelComparison[ForOrg]:1138-1142,1199-1203` defaults null provider to `"anthropic"` and null model to `modelRegistry.workhouseModelFor("anthropic")`; `AgentTask.java:68` comment cites example model `claude-sonnet-4-5`; `:61-64` hardcodes cache-pricing multipliers in comments (0.10x/1.25x).
- `PipelineRun.java:225` builder-default `aiProvider = "OPENAI"` and `getAiProviderEnum:284-290` falls back to `OPENAI` on garbage — diverges from the platform's Anthropic default (set at submission, but the entity default is a trap for any path that skips it).
- Batch clients read `spring.ai.anthropic.*` / `spring.ai.openai.*` / `spring.ai.google.genai.*` base-url + api-key props directly with BYOK fallback via `ApiKeyResolver` (`AnthropicBatchClient.java:67-78,307-324` etc.); Anthropic pin `anthropic-version: 2023-06-01` (`:56`); all three `DEFAULT_MAX_TOKENS = 8192`; OpenAI `completion_window 24h`; OpenAI `max_tokens` field risk (R9); Gemini single-model-per-batch caveat + missing schema sanitization (`GeminiBatchClient.java:38-42,208`).
- `BatchRequest` carries Spring AI `Message`/`ToolCallback` types directly (`batch/BatchRequest.java:16-22`).

## 5. Libraries

Spring Boot 3.5 stack: Spring MVC + `SseEmitter`, Spring Data JPA (Hibernate 6 — `@JdbcTypeCode(SqlTypes.JSON/UUID)`), Spring Data Redis (`StringRedisTemplate`, `RedisMessageListenerContainer` pub/sub), Spring `@Async`/`@Scheduled` (+ `spring.threads.virtual.enabled=true`), Spring AI (`Message`, `AssistantMessage`, `ToolCallback`, `Prompt` in batch/ + execution planner), ShedLock (`AdminReconciliationService`, `BatchPollService` — note `SideEffectExecutor` has **no** SchedulerLock; its only multi-instance guard is the broken claim), Jackson, Lombok (`@Data` on entities — see §7), Micrometer (gauges/counters in sse/ + saga/), `RestClient` + `JdkClientHttpRequestFactory` (batch), `RestTemplate` (`integrationRestTemplate` in JIRA sync + launch controller), JavaCrumbs ShedLock annotations. Tests: JUnit 5, Mockito, AssertJ, Testcontainers (Postgres/Redis in chaos test), `ReflectionTestUtils`.

## 6. Test coverage

- **state/**: 1 file (`InboxServiceTest`, 4 tests) for ~4.7K LOC. **StateStore (2,109 LOC) has zero direct tests** — `detectTechStack`, `listRuns` spec stacking, archive/soft-delete state machine, `appendSuggestionTasks` all unpinned. `ChaosScenariosTest` (manual `@Tag("chaos")`, not in CI default) touches StateStore + outbox enqueue only.
- **execution/**: 8 files / 35 tests — decent on the CAS retry helpers (`ExecutionRunCasUpdaterTest` 4, orchestrator approve/abort/analysis/terminal CAS suites 15) and planner pure logic (codemap filter 5, selected-repo-ids 7, persistPlan CAS 3, cost aggregator 7). **Nothing covers** `dispatchUnified`/`pollAndMirrorV2`/`mirrorV2Terminal` (the live unified path), `SolutionExecutionService` (651 LOC: retry/signature/decision logic untested), both controllers, `AdminReconciliationService` sweeps, or `SolutionDependencyResolver`/`FileConflictDetector` (pure logic, trivially testable, untested).
- **recovery/**: 1 file / 3 tests — only the `workspaceMissing` precheck via reflection; salvage/fail/cap/stale branching of `scanAndRecover` untested.
- **sse/**: **zero tests** — replay sequencing (B4), emitter caps, counter drift, Redis listener all unpinned.
- **batch/**: 4 files / 13 tests — JSON shaping + status maps per client + router; HTTP layer explicitly unsmoked.
- **saga/**: **zero direct tests** — B1 would have been caught by a single integration test driving `processDue` with one PENDING row.
- **run/**: 2 files / 11 tests — idempotency + local-exec flag well pinned; `RunSyncService` untested; `RunSkipPushFlag` untested (mirror of tested class).

## 7. Security notes (parked)

- `pipeline_run.github_pat` and `bitbucket_app_password` are plaintext columns; `PipelineRun` is `@Data` → generated `toString()` includes both secrets (and `@EqualsAndHashCode` over all fields). Any `log.*("{}", run)` or debugger dump leaks credentials. Same plaintext pattern for `Org` JIRA api token consumed in `ExecutionJiraSync.authEntity:146-155`.
- `GeminiBatchClient` puts the API key in the URL query string (`:91,114,126`) — logged by proxies/access logs.
- `ProjectLaunchController.validateOwner:266-290` interpolates user-supplied `githubOwner` into a GitHub API URL path unencoded — path traversal to other GitHub endpoints with the caller's own PAT (low impact, self-scoped).
- SSE scoped tokens passed as query params (`ExecutionController.java:311-327`) — standard for EventSource but ends up in access logs; token TTL 5 min mitigates.
- `ScaffoldController` (`/api/scaffolds`) has no gating beyond the default authenticated chain — static data, fine.
- `ExecutionController.requireRunAccess` org-only check vs ProjectMember (see Inconsistent) is also a minor authz asymmetry.
- The B5 GDPR status-vocabulary bug has a compliance angle (erasure not applied to in-flight runs / resurrectable tombstones).

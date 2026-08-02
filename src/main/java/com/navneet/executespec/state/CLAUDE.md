# `state/` — Run state management

V360 adds brownfield HTTP-surface JSON/digest fields to `pipeline_run`; admission writes bind the
canonical route set to the run. V361 adds nullable-for-legacy
`validation_report.workspace_generation`; every newly generated report must persist a lowercase
SHA-256 canonical Git-tree generation and propagate it to every verification-ledger row. Null means
historical/unbound, never current.
V364 adds the exact continuation-approval receipt fields on `agent_task`. A conditional repository
update claims one run/task/proposal digest before queueing; exact duplicates replay the same
approval id, conflicting active proposals fail closed, and terminal receipts allow a later distinct
proposal without permitting double queue/provider spend.
V365 adds the receipt transition timestamp and bounded recovery index. Expired CLAIMED receipts are
released because no worker crossed the QUEUED boundary; expired QUEUED receipts with no fresh run
heartbeat fail closed, stale validation, and require a fresh run.
V369 adds `correction_attempt`, the single-active-per-run durable authority written before a
correction mutates files or invalidates evidence. It persists repository path/HEAD/tree refs plus
the pre-correction validation and task evidence snapshots. A heartbeated database owner token plus
a same-workspace OS file lock serialize correction, recovery, SCM publication, and deployment;
each correction tool dispatch rechecks the exact attempt lease. Startup/scheduled recovery restores
and proves all three; `RECOVERY_FAILED` remains an admission and publication fence.
V370 retypes V369's five digest columns from `CHAR(64)` (bpchar) to `VARCHAR(64)`: Hibernate
schema-validation rejects bpchar against the entity's `@Column(length = 64)` mapping, which
crash-looped the dev deployment on boot. Values are exact 64-char hex digests, so the in-place
type conversion is content-preserving and the V369 CHECK constraints remain valid.

> Last reviewed: 2026-08-02 against the V371 worktree contract.

Persistence layer for pipeline runs, tasks, and all mutable state.

## Key Files
- `StateStore.java` — Central state manager. All run/task state mutations go through here. 1500+ lines. Key batch methods: `resetTaskForRetry()` (resets task to PENDING for batch auto-retry), `setTaskModelProvider()` (sets resolved model/provider after batch resume). The old `createIterationRun` row-only helper was removed on 2026-07-26: iteration must go through `run/RunIterationService` → `RunOrchestrator.submitIterationRun` so a persisted child always has an active pipeline and a fail-closed lineage workspace.
- Startup crash recovery considers a run stale from its durable `execution_heartbeat` (refreshed
  every 30 seconds), not merely its original start time; `started_at` is the legacy fallback. This
  keeps legitimate >6h runs eligible for the existing run/task CAS and GCS checkpoint recovery.
- Plan-gate handoff uses an explicit process-unique `:plan-gate-queued:` owner before the accepted
  Future starts. Generic startup recovery excludes only that queued lane; the dispatcher's durable
  COMPLETED-handoff sweep restores/checks the workspace while renewing the same owner. The first
  Future instruction atomically transfers to the normal runtime owner and adopts normal heartbeat
  management in the exact active-run registration, after which a crash follows ordinary WORKING
  checkpoint recovery and is never reclaimed by the plan-gate dispatcher.
- `StartupRunResumer` runs at application readiness and on a configurable 60-second sweep only
  when `agent.recovery.auto-resume-on-startup=true`; false disables both automatic entry points
  while retaining the explicit operator/test `scanAndRecover()` path.
  A boot-time `NOT_APPLICABLE` caused by the dead predecessor's still-fresh run/task heartbeat is
  deferred and retried after the existing two-minute CAS stale fence; it is no longer a permanent
  stuck-`WORKING` outcome. An in-process guard prevents overlapping scans.
- Cross-host crash recovery now fails closed unless `durability.workspace.shared-persistent=true`.
  Same-host process restart remains eligible. The asynchronous GCS task archive is not treated as
  an acknowledged task frontier: it can lag a terminal task-row commit, so using it blindly could
  skip a task whose files are absent. Until a versioned frontier is persisted and validated,
  different-host takeover terminates/refunds with `UNTRUSTED_WORKSPACE_FRONTIER`.
- Execution/recovery leases use `integration.ExecutionHostIdentity`: explicit
  `EXECUTESPEC_EXECUTION_HOST_ID`, then container `HOSTNAME`, then Linux machine-id, network host,
  and finally the host portion of the JVM runtime name. The fallback never includes the JVM PID, so
  a systemd restart on one VM remains same-host while cross-host recovery still fails closed.
- The recurring recovery sweep treats a null first heartbeat as stale only after the run's
  `startedAt` is at least two minutes old (or absent on a legacy row); it cannot claim a fresh
  planner call during the initial heartbeat window.
- Submission stamps `executingInstance` and the first `executionHeartbeat` in the initial run-row
  insert, before async planning starts. This keeps early full-entity saves from restoring a stale
  null owner and makes subsequent narrow heartbeat refreshes eligible.
- `entity/PipelineRun.java` — JPA entity for runs. Key fields include status/reason, ownership, cost, plan/review evidence and **V275 route policy**: `requestedProvider`, `requestedModel`, `routingMode` (`AUTO | PROVIDER_PREFERRED | EXACT_LOCK`) and `routePinned`. Exact routes are immutable execution contracts consumed by `RunRoutePolicyService` and rebound during recovery/retry; they are not merely UI preferences. V350 separates host data/governance `orgId` from immutable payer/provider-policy `billingOrgId`; external collaborators use the host project org for isolation and the submitting actor's org for credential policy and managed-AI debit. The legacy asynchronous SCM snapshots `githubPat` and `bitbucketAppPassword` use `EncryptedValueConverter` and are excluded from Lombok `toString`; they remain temporary credential copies until a reference-based credential lease replaces them.
- **V261 `localExecutionRequested`** — durable CLI/VS Code local-hands intent used by crash recovery so a client-owned run never silently downgrades to server shell execution.
- `entity/AgentTask.java` — JPA entity for individual tasks within a run. Includes subtask decomposition fields: `parentTaskId` (null for top-level), `subtaskIndex` (1-based), `subtaskTotal`. **v2.5.11 (V109): `meetsRequirementsJson` — JSON array of requirement IDs from `PipelineRun.planRequirementsJson` this task is expected to implement.** **v2.6.2-track (V130): `taskType` (LEAF \| EPIC), `expansionStatus` (PENDING \| EXPANDED \| FAILED \| null), `expandedAt` — populated by Phase 2 (TaskExpanderAgent) of the hierarchical planner.** **V250:** `goalDonenessJson` + `continuationDecisionJson` persist executor task-boundary goal state and next-action advice for run detail clients. **V252:** `continuationActionsJson` persists executed/skipped continuation actions so automatic model/provider upgrades survive final decision overwrites and reloads. **V364:** `continuationApproval*` fields persist the exact proposal digest, receipt id, provider/model route, action, post-review source marker, and lifecycle status used to fence approval replay.
- `entity/PlanVersion.java` — JPA entity for plan version history. Columns widened in V45 (id/run_id/created_by → VARCHAR(255), source → VARCHAR(64)).
- `repository/PipelineRunRepository.java` — JPA repository for PipelineRun. Includes atomic token floors, plan-gate pessimistic reads, stale-heartbeat-aware crash claims, and narrow terminal-transition updates. `existsByRunIdAndStatus()` is the worker/executor hard-cancellation fence. V348 makes `PlanGateDecisionService`, not the retained `claimApprovalResume()` compatibility query, the production approval/cancellation boundary: it locks the run first and commits run, gate, receipt, and outbox state together. `claimCrashResume()` remains the WORKING recovery boundary; APPROVAL_RESUMING is recovered only through the plan-gate outbox. `claimTerminalTransition()` prevents reviewer/validation completion from overwriting concurrent cancellation or pause. The N2 analytics queries (`findRunIdsByOrgId`, `findByOrgId*`, `findByStartedAtAfter`) filter model-comparison and user-summary reads in PostgreSQL; the unbounded global all-time summary still uses `findAll()`.
- `repository/AgentTaskRepository.java` — JPA repository for AgentTask. **2026-05-04**: gained `findByRunIdAndTaskId(runId, taskId)` returning `Optional<AgentTask>` — used by the V2 crash-recovery resume path (`SequentialExecutor.resolveResumeAction`) to inspect each task's persisted status before deciding SKIP_TERMINAL / RESUME_FROM_SNAPSHOT / RUN_FRESH on boot-time replay. **N2 (audit 2026-05-29):** gained `findWithTokens[Since]` + `findWithTokensByRunIds[Since]` (split-by-since `@Query`, no `:param IS NULL`) backing the DB-filtered model-comparison feed. **V364:** `claimContinuationApproval` is the one-statement exact-proposal CAS; status and release updates remain receipt-id/digest fenced.
- `InboxService.java` — User inbox/notification service.

## DB Migrations

- **V364 exact continuation-approval fence:** adds the nullable-for-legacy approval receipt tuple to
  `agent_task`, checks all-or-none identity plus bounded lifecycle states, and indexes exact
  run/task/proposal lookups. Admission occurs before active-run registration or executor queueing.
- **V365 stale continuation-receipt recovery:** adds an all-or-none transition timestamp and bounded
  active-receipt index so crash windows cannot remain permanently CLAIMED/QUEUED or be replayed
  into uncertain provider/workspace side effects.
- **V369 durable correction transaction:** adds `correction_attempt` with one unresolved owner per
  run, exact repository HEAD/tree references, task/evidence snapshots, accepted/rolled-back
  generations, a DB lease plus local file-lock workspace authority, and a persistent fail-closed
  recovery state.
- **V370 correction-attempt digest retype:** converts V369's `before_generation`,
  `after_generation`, `evidence_workspace_generation`, `evidence_snapshot_digest`, and
  `proposal_digest` from `CHAR(64)` to `VARCHAR(64)` so Hibernate schema-validation matches the
  `CorrectionAttempt` mapping; the bpchar mismatch crash-looped the 2026-07-31 dev deploy. Pinned
  by `DurableCorrectionMigrationContractTest`.
- **V347 atomic plan-gate open:** `PipelineRunRepository.findByIdForPlanGateOpen()` takes the pessimistic run-row lock that serializes the gate row, WAITING continuation outbox, and `PLANNING -> AWAITING_APPROVAL` transition in one transaction.
- **V348 atomic plan-gate decision + leased handoff:** `PlanGateDecisionService` supersedes the older `claimApprovalResume()` compatibility query as the production approval/cancellation boundary. It locks run → gate → outbox → optional contract report → RequirementIR/policy rows and persists a byte-stable APPROVE/REJECT/CANCEL receipt plus canonical request fingerprint with the state transition. The receipt cross-binds provider/mode/fixed-six-decimal `estimateHighUsd`/separate nullable `hardCapUsd`/credits/payer/continuation/report and exact typed-ack fields. New acknowledgements persist uppercase `BREAKING`; complete pre-V348 tuples remain byte-exact and are semantically checked case-insensitively after trimming, with REJECT/CANCEL recorded as `NOT_EVALUATED`. Missing legacy client plan fingerprints remain absent with a null match result; supplied values must match and ENFORCED approval requires one. APPROVE creates `APPROVAL_RESUMING` + READY; REJECT/CANCEL create coherent `CANCELLED` states. `PlanGateResumeStore` owns fenced READY/PROCESSING leases, preflights identity before workspace restore, and repeats the fence while committing `APPROVAL_RESUMING → WORKING` with outbox `PROCESSING → COMPLETED`; COMPLETED means durable WORKING handoff. Startup and scheduled recovery read only this outbox for APPROVAL_RESUMING. Direct child evidence deletion is rejected while parent-run cascade is retained; the report→run FK is `NOT VALID` for historical orphans but enforced on new rows.
- **V349 payer/tenant gate correction:** the immutable plan-gate payer organization may differ from the host-project run tenant for an external collaborator; all other V348 coherence checks remain.
- **V350 execution identity split (committed at `13ead3f40`; deterministic verification green at that checkpoint):** adds and backfills `pipeline_run.billing_org_id`, adds and backfills CUSTOMER `llm_execution_context.credential_org_id`, and requires CUSTOMER contexts to retain the credential organization. Separate PostgreSQL triggers reject later payer/credential-org updates, while application admission and dispatch guards independently enforce the same immutable binding. The current worktree head is V371, next V372, with 357 source files; `requirement.ir.gate.mode` remains LEGACY.

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
- V106 (v2.5.10): `validation_report` gains `final_build_passed`, `tests_passed`, `tests_total`, `reviewer_verdict`, `verified_score` columns + seeds `scoring.v2-verified` feature flag. Deterministic-score foundation for the v3 validation rollout.
- V107 (v2.5.10): `pipeline_run.status_reason VARCHAR(80)` — explicit reason for the terminal status (see entity doc above).
- V108 (v2.5.10): one-shot backfill that reclassifies historical PARTIAL runs. Runs with `final_build_passed=TRUE` + reviewer `CLEAN|FIXED_ISSUES` + tests passing → COMPLETE + `CLEAN_WITH_INTERMEDIATE_WARNINGS`. Build-failed → stay PARTIAL + `FINAL_BUILD_FAILED`. Everything else → PARTIAL + `LEGACY_UNKNOWN`.
- V109 (v2.5.11): `pipeline_run.plan_requirements_json TEXT` + `agent_task.meets_requirements_json TEXT` + seeds `validation.v3-strict` feature flag (default OFF). Planner requirement graph for Validation v3 / Pillar 1.
- V110-V129 (v2.5.12 → v2.6.1): scoring flag enable, execution-solution dedup, optimistic lock on `execution_run`, project_generated_doc, repo_architecture_doc, plus the persona-roadmap waves (V120 GEMINI rename, V121-V124 TEAM tier + lifecycle, V125-V126 activation funnel + sandbox, V127 SSO/GitHub-app/team-trial schema, V128 provider-routing log + flags, V129 startup program + 14-day MAX trial). Each wave's full scope lives in root `CLAUDE.md`.
- V130 (v2.6.2-track): hierarchical planner phase 0 schema. Adds `pipeline_run.plan_strategy VARCHAR(32)`, `plan_outline_json JSONB`, `classifier_reasoning TEXT`, plus per-phase token + cost columns (`{classifier,outline,expansion,coherence}_{input,output}_tokens BIGINT` + `_cost_usd DOUBLE PRECISION`); `agent_task.task_type VARCHAR(16)`, `expansion_status VARCHAR(16)`, `expanded_at TIMESTAMP`; partial index `idx_pipeline_run_plan_strategy WHERE plan_strategy IS NOT NULL`. Additive only — every column nullable so in-flight runs across the rollout window aren't disturbed. **Live on dev/main.**
- **V131–V142**: enterprise-readiness phases E1–E4 (folder + activity feed + contracts + standards + ADR + invariants + notification preferences + export + tier-cap alignment + tier-ladder consolidation + dead-flag cleanup). Originally tagged "feature branch only" but **all merged to dev/main**.
- **V143** (`product_split.sql`, 2026-05-06): added `product_id` ENUM ('vibe','executespec') discriminator on pipeline_run/project/org/billing_subscription/invoice/notification + `app_user_product_access` join table. **REVERTED by V179** in the same single-surface retirement batch — backfill, columns, ENUM, and join table all dropped. Treat the schema state as "as if V143 never landed".
- **V144** (`reinstate_per_seat_team_tier.sql`, 2026-04-26 same-day correction): reverses V141 (un-renames TEAM → MAX, re-inserts the per-seat TEAM tier_config + billing_plan rows). Subsequently rolled back: V165 dropped MAX and promoted the $39/seat Team row; V176/V185 re-listed PRO at $29/month; V200 bumped FREE to 15 runs. **Current live ladder:** FREE / PRO / TEAM ($39/seat BYOK) / ENTERPRISE.
- **V145–V178**: V145 loosens FREE 5 → 10 credits (reverted by V167 — FREE = 5; later raised to 15 by V200, see below); V146 annual billing plans; V147–V164 incremental tier/billing/preview/SCM polish + v2.7 Foundation (audit-chain, governance, retention); **V165 drop MAX tier and rename to TEAM @ $39/seat**; V166 align FREE billing plan run limit; V167 revert FREE to 5; V168 pause/resume columns; V169 PR record `project_id` + `kind`; V170 ci_auto_fix_attempts; V171 reconcile pipeline_result with status_reason; V172 code-review + pr-review agents (PRO+ tier-gated); V173 widen review user_id columns; V174 cost on arch-doc artefacts; V175 external project sharing; V176/V177 unhide PRO as Solo + Solo annual + founder pricing (**both retired by V179 + V185**); V178 user_cost_cap on pipeline_run.
- **V179** (`drop_product_split.sql`, 2026-05-06 — single-surface retirement): reverse of V143. Drops `product_id` columns + `app_user_product_access` table + the ENUM type. Net schema effect: V143's groundwork no longer in the active DB.
- **V180**: lint_autofix_flag (deterministic auto-formatter pass between executor and reviewer).
- **V181**: pr_comment_inbox (incoming PR review comments captured by GitHub webhook for the v3 RunDetail ChatPane).
- **V182**: runMode discriminator on pipeline_run (BUILD vs PATCH; BUILD = original planner→executor→reviewer flow, PATCH = single thin-agent loop for SWE-bench / autofix runs).
- **V183**: triggered_by_finding column on pipeline_run (stamps the originating finding id when a patch run is dispatched in response to a code-review comment / tech-debt issue / CI failure).
- **V359**: `ci_failure_delivery_claim` makes each immutable GitHub check-run/head-SHA observation a first-writer-wins admission. The unique finding key and PR-attempt reservation commit before PATCH submission, the initial `pipeline_run` insert carries the same `triggered_by_finding`, and a later fenced transaction binds the canonical run. FAILED claims release their reservation; expired CLAIMED rows recover an already-created run by marker or are fenced for retry.
- **V184** (2026-05-10): widens `agent_task.last_llm_failure` from `VARCHAR(255)` to `TEXT`. Root cause: `value too long for type character varying(255)` overflows on the COMPLETE/PARTIAL DB flip. The `softWarnReason` from `SequentialExecutor.executeOne` routinely exceeds 255 chars when `Outcome.STOPPED_WITHOUT_COMPLETE` injects the 240-char `finalAssistantText` tail. Companion defensive 4000-char cap added in `SequentialExecutor.persistTaskStatus` so the column never absorbs unbounded router stack traces.
- **V185** (2026-05-09 — single-surface retirement follow-up to V179): retires the Solo tier branding introduced by V176/V177. Un-renames PRO → "Pro" (PRO row stays HIDDEN, grandfathered as before); drops Solo monthly/yearly + founder pricing seed.
- **V186** (2026-05-11): pull_request CI status columns. `PullRequestSnapshot.headSha` is added so `RunController.refreshPrs` can poll the `check-runs` endpoint alongside the open/merged state.
- **V189** (PR-A, 2026-05-13): one-shot UPDATE that backfills `execution_run.{actual_cost_usd,total_actual_tokens}` from the unified V188 `pipelineRunId` link + legacy `execution_solution_run` history. Repairs the 28 historical executions whose totals were wrong because the broken incremental accumulator dropped CAS-conflict contributions.
- **V190** (PR-A, 2026-05-13): `agent_task.failure_diagnostic_json TEXT` — compact JSON capturing worker outcome + iter budget + a secret-free tool-name/opaque-signature/status diagnostic at the WORKER_FAILED call site. Current writers never persist assistant text, tool arguments, commands or provider bodies in this field.
- **V250** (RL-2026-06-198): `agent_task.goal_doneness_json TEXT` + `agent_task.continuation_decision_json TEXT` — durable goal-state and continuation advice emitted by `SequentialExecutor`; surfaced through `TaskSummary` and the IDE run result task cards.
- **V251** (RL-2026-06-198): `pipeline_run.run_goal_doneness_json TEXT` — durable final run-level goal proof derived from the validation-report ledger; surfaced through `RunDetail.runGoalDoneness`.
- **V252** (RL-2026-06-205): `agent_task.continuation_actions_json TEXT` — append-only executed/skipped continuation action history emitted by `SequentialExecutor`; surfaced through `TaskSummary.continuationActions` and the IDE run result Steps card.
- **V261** (RL-2026-07-007): `pipeline_run.local_execution_requested BOOLEAN NOT NULL DEFAULT FALSE` — persists local-hands intent across JVM restarts; recovery restores the remote-tool transport and fails with `LOCAL_HANDS_DISABLED` when the operator flag is off.
- **V265** (RL-2026-07-012): deduplicates historical `LOCAL_HANDS_WORKER_RESTARTED` action events and adds a partial first-writer-wins index so concurrent startup recovery observers cannot create competing restart transitions for one call.
- **V266**: graduates bounded provider failover after same-provider transient retries; BYOK boundaries remain enforced.
- **V267**: graduates evidence-gated initial worker routing; no observed quality evidence or available provider means the caller route is preserved.
- **V270**: persists deterministic validation boot-verification verdict, health, and reason so concrete-test rescoring preserves BUILD evidence.
- **V273**: persists provider-independent executable-verification status, failure stage and bounded evidence on validation reports.
- **V274**: persists bounded Ganesh worker-attempt evidence and seeds its default-off feature flag.
- **V275**: persists the run provider/model routing contract used by submit, restart, retry, reviewer, chat and background dispatch; exact locks fail closed at the transport boundary.
- **V276**: persists linked `project_repo` source-readiness status, source count and bounded failure evidence for brownfield admission.
- **V277**: persists authoritative source/change fingerprints on generated test plans so stale or unrelated tests are excluded rather than scored.
- **V279**: widens account/org integration credential columns for the versioned AES-GCM envelope. Existing applied migration remains immutable.
- **V280**: widens the remaining account/run Bitbucket secret columns to `TEXT`; strict startup backfill now includes `pipeline_run` SCM snapshots and addresses them by `run_id`. A standalone PostgreSQL V278→V281 rehearsal covers a 480-character legacy secret, encrypt/decrypt and restart idempotence.
- **V281**: adds durable `pr_review.repo_id` and project/repository-scoped PR-review idempotency for linked-repository admission.
- **V282**: creates the exact, tenant-scoped durable LLM execution-context contract used across run, project, agent-session, review, benchmark, and explicitly internal work.
- **V320** (RR-339 / RR-340): adds `integration_run.org_id` + `created_by_user_id` and an
  `(org_id, created_at DESC)` index. `IntegrationRun.tenantId` is populated opportunistically by
  `TenantInterceptor` from a request-scoped thread local and defaults to the literal `'default'` on
  unauthenticated webhook threads, so it was never an ownership fact; these columns are. Reads
  (`GET /api/integrations/runs`) scope on `org_id`; legacy rows keep NULL and are visible only to
  SUPER_ADMIN. `created_by_user_id` records the actor that caused an interactive trigger, which now
  runs under the caller's identity rather than the global `run-on-behalf-of-user-id` runner.
- **V338** (RR-1446): `agent_task.plan_index INTEGER` (nullable) — persists the planner-intent order
  of each task so the approval gate renders the planner's sequence rather than the unspecified
  database row order (all tasks are PENDING at the gate, so the old status-only sort was a no-op).
  Written by `StateStore.savePlan` / `RunPersistenceWriter.persistTasks` (and appended plans);
  `RunMapper.TASK_DISPLAY_ORDER` sorts by it first, status is decoration only, legacy NULL rows sort
  last. Pinned by `RunMapperTaskOrderTest`.
- **V283**: expands `run_step_usage` into the canonical physical-provider-attempt ledger with tenant/context, feature, key source, retry/failure, latency, BIGINT call-index, and safe numeric usage metadata. Historical arbitrary raw JSON is scrubbed during upgrade.
- **V191** (PR-B-part-2, 2026-05-13): `project.{archived_at,deleted_at}` columns + partial indexes (`idx_project_active` on `(org_id) WHERE archived_at IS NULL AND deleted_at IS NULL`, `idx_project_deleted_at` for the PR-H cleanup sweeper). Backfills `archived_at = updated_at` on legacy `status='ARCHIVED'` rows. Companion code change: project archive is now reversible (`POST /api/projects/{id}/unarchive`), `DELETE /api/projects/{id}` performs a soft-delete with a 30-day undo window (restored via `POST /api/projects/{id}/restore`). PipelineRun already carried the same two columns from V60 / V60.1 — same lifecycle endpoints (`/runs/{id}/unarchive`, `/runs/{id}/soft-delete`, `/runs/{id}/restore`) wired on the run side too.
- **V192** (PR-M, 2026-05-13): `project_architecture_doc.generated_from_branches jsonb` — captures the branch each per-repo codemap was generated from. Fixes the permanent-STALE-badge bug on projects regenerated with a `?branch=<non-default>` override: `StalenessService.compute` now probes the SAME branch the codemap came from instead of always assuming `defaultBranch`. Nullable; legacy pre-V192 rows fall through to existing defaultBranch behaviour.
- **V193** (PR-I, 2026-05-13): `execution_run.failure_reason TEXT` — populated on every non-clean terminal path by `ExecutionOrchestrator` (V2 submitRun crash, planner deadline, mirrored V2 statusReason). Surfaces the "why did this fail" string on the V3 dashboard so operators don't have to SSH the VM. Companion `mirrorV2Terminal` change propagates V2's `statusReason` / `failureReason` into ExecutionRun.failureReason.
- **V199** (2026-05-19): one-time backfill resolving `org.tier` vs `app_user.tier` / `billing_subscription` drift (BUG-009) — the same drift that made the RUNS quota check read a stale plan. Companion code: `UsageTrackingService` now sources the RUNS quota from `tier_config.monthly_run_limit` keyed by `EntitlementResolver.resolveTier(user)` (higher-of org/user tier) instead of `billing_plan_limit`, so a promoted org on a stale FREE subscription row no longer false-positives the limit.
- **V200** (2026-05-19): bump FREE tier 5 → 15 runs/month (reverses V167). Rewrites the three authoritative surfaces in one shot — `tier_config.monthly_run_limit` (canonical, read by the post-V199 quota path), `billing_plan.run_limit`, and `billing_plan_limit.max_value WHERE metric='RUNS'`. Idempotent (only rewrites rows still at 5).
- **V201** (2026-05-19): seed `scoring.additive-v2` PLATFORM feature flag (default true). Ships with the additive `verifiedScore` rebuild in `ValidationReportService.computeVerifiedScoreAdditive`. Flip OFF to revert new runs to the legacy multiplicative formula; existing rows are not recomputed.
- **V202** (2026-05-19): seed `reviewer.third-pass.enabled` GLOBAL feature flag (default true) for the fix-the-red-build reviewer pass. Note `scope='GLOBAL'` — `FeatureFlag.Scope` now has a `GLOBAL` constant alongside `PLATFORM` (synonyms); without it `repository.findAll()` threw on the unknown enum and emptied the entire flag cache, silently disabling every flag (incl. `scoring.additive-v2`).
- **V208** (2026-05-23): widen `github_pat` VARCHAR(255) → TEXT on `pipeline_run` (+ `app_user`). Drops the brittle 255-char cap from V8 ahead of GitHub's longer stateless token format (`ghs_…`, ~520 chars) + the trend toward longer PATs; aligns with every other credential column (already TEXT). Same bug class as the `lastLlmFailure` note below. Metadata-only ALTER (no rewrite/truncation), idempotent. RL-2026-05-024.
- **V341** (2026-07-25): raise-only historical reconciliation of `pipeline_run.{total_input_tokens,total_output_tokens,actual_cost_usd}` from canonical successful `run_step_usage` physical attempts. Repairs approval-waiting rows created before planning spend was persisted at the gate and cancellation rows whose final in-flight provider response landed after the earlier terminal snapshot. Successful unpriced attempts contribute token evidence but not a fabricated zero-cost fact; failed attempts contribute neither token nor cost totals. Current code applies the same floor before the plan gate, during cancellation, at terminal publication, and after late terminal-outbox materialization.
- **V342** (2026-07-26): persists `run_sandbox` cleanup target, original failure intent, retry count, and latest bounded teardown error. A failed runtime destroy remains `RELEASING` with an immediately expired fenced lease; another JVM reclaims it and publishes the original terminal target only after runtime absence is verified.

> **Note on entity vs column type:** `lastLlmFailure` was added as a plain `String` field with no `columnDefinition` annotation, so Hibernate auto-mapped it to VARCHAR(255). Other String columns on the same entity that hold worker-generated content (`taskJson`, `workerReportJson`, `completionNotes`, `meetsRequirementsJson`) all carry `@Column(columnDefinition = "TEXT")`. V184 + the entity edit closes the inconsistency.

## Patterns
- StateStore is the single source of truth — no direct repository access from agents
- Plan-gate decisions are the focused exception to broad state mutation: only
  `PlanGateDecisionService` may atomically mutate decision evidence, gate status, run lifecycle, and
  continuation outbox. `PlanGateResumeStore` alone owns lease/handoff transitions. Generic crash
  recovery must not submit `APPROVAL_RESUMING`; the durable outbox is its only recovery authority.
- Paused/crash recovery and approved-continuation dispatch put a `FutureTask` into the active-run
  registry before executor submission. This registration order prevents an inline/fast executor
  from finishing and removing the run before a stale future is inserted; rejection and completion
  compare-and-remove the exact Future. A zero-row durable heartbeat refresh removes and interrupts
  that exact stale Future, while a transient refresh exception retains it for the next retry.
- Crash recovery uses two durable liveness fences: `pipeline_run.execution_heartbeat` is claimed
  atomically at run level, and `agent_task.last_activity_at` is refreshed on each persisted task
  transition. `RunLifecycleService` refuses takeover when a WORKING/LOCKED task is fresh and
  atomically increments `recovery_attempt` for stale tasks before queueing a resumed executor;
  this is bounded stale-task fencing, not proof of full cross-JVM worker replay.
- Task compaction recorded via `recordCompaction()` (emits SSE event)
- Token usage is tracked per run via atomic `addTokens()` updates and terminally floored from the
  canonical successful-attempt ledger via `raiseUsageTotalsToAtLeast()` (no read-modify-write race).
- Quota enforcement: `TierEnforcementService.assertAndIncrementRunCount(userId, credits)` uses pessimistic lock + single TX (TOCTOU fix). Multi-credit support: increments by N at plan approval (not submission). Refunds via `decrementRunCount(userId, credits)` on FAILURE.
- Plan versioning via `savePlanVersion()` / `incrementPlanVersion()` — used by apply-suggestion flows

## Erasure and heartbeat lifecycle (RR-090 / RR-071, 2026-07-21)

- `PipelineRunRepository.TERMINAL_STATUSES` is the shared fail-closed terminal set
  (`COMPLETE`/`FAILED`/`PARTIAL`/`CANCELLED`/`ABORTED`/`REJECTED`). GDPR erasure
  (`softDeleteTerminalByUserId`, replacing `softDeleteByUserId`) and the hard-delete sweep
  (`findSoftDeletedOlderThan`) are now `IN` ALLOWLISTS against it. The old erasure query was a
  `NOT IN` denylist naming only the obsolete `PENDING`/`IN_PROGRESS`/`IN_PROGRESS_BATCH` spellings,
  so four of the six live V2 states (`QUEUED`, `AWAITING_APPROVAL`, `APPROVAL_RESUMING`, `WORKING`,
  `REVIEWING`) were unprotected — erasure tombstoned live runs and the 7-day sweep hard-deleted the
  row while the worker was still spending. A new or unrecognised status is now REFUSED, not
  tombstoned.
- **Erasure now COMPLETES rather than skipping live runs (RR-090, 2026-07-21).** The earlier fix
  stopped tombstoning live runs but merely SKIPPED them and logged "erasure INCOMPLETE" — the
  Article-17 obligation was left unmet. `DataRetentionService.eraseUserData` now: (1) settles every
  live run through the real `RunLifecycleService.cancel(runId)` path (atomic `CANCELLED` + plan-gate
  signal + pipeline-future interrupt + PR close, not a status hack), (2) waits bounded
  (`ERASURE_SETTLE_TIMEOUT`, 10s) via `runRepository.findNonTerminalByUserId` /
  `countNonTerminalByUserId` for terminalisation, (3) tombstones the now-terminal rows through
  `ErasureObligationStore.softDeleteTerminal` (the transactional half, kept out of the
  cancel/sleep orchestration so no DB connection is held across a sleep), and (4) if any run still
  cannot be confirmed terminal, records a durable `pending_erasure_obligation` (V334) instead of a
  log line. `DataRetentionService.retryPendingErasures` (in the daily sweep) re-drives each open
  obligation to completion and clears it when no live run remains, so user data cannot survive
  erasure indefinitely. The residual is a tracked, retryable state.
- `StateStore.updateHeartbeat` delegates to `claimExecutionOwner` instead of doing
  `findById` → mutate → `save`. `PipelineRun` has no `@Version`, so that read-modify-write wrote
  back every field as loaded and could revert a concurrent terminal status, gate, token/cost or
  cancellation write. (It had no production caller; `RunOrchestrator` already used the narrow fenced
  queries. The dead method's javadoc invited exactly the wrong reuse.)
- `RunMapper` treats persisted COMPLETE as an execution-boundary fact, not proof of completion. A
  COMPLETE task with non-DONE durable goal evidence renders PARTIAL, and a COMPLETE run containing
  any such task renders PARTIAL with `TASK_PROOF_INCOMPLETE`; list hydration batches task reads by
  run id so summary and detail surfaces agree without rewriting recovery state.

# Run Execution

## What Exists

ExecuteSpec's production run path is the V2 BUILD pipeline:

1. `RunController.submit` accepts a run request and `RunOrchestrator.submitRun` stamps owner/org/project fields plus submit-time continuation policy markers before async execution.
2. `RunOrchestrator` classifies the spec, creates the plan, pauses at the plan gate for production runs, executes tasks, reviews the workspace, computes score/report data, and starts post-run validation/preview work.
3. `SequentialExecutor` routes each task by complexity into `WorkerAgent`.
4. `WorkerAgent` executes a native tool-calling loop over file, shell, build, test, git, and completion tools.
5. `ReviewerAgent` runs general and wiring review passes, plus the targeted fix-build pass when enabled.
6. `ValidationReportService` computes the additive verified score, applies trust
   caps, and exposes the verification ledger behind the headline.
7. PR push, preview, test generation, and run chat are downstream terminal/run-detail capabilities.

PATCH mode is the thin-worker path used by SWE-bench, ProgramBench, and the four autofix surfaces.

## Key Capabilities

| Capability | Status | Code |
| --- | --- | --- |
| BUILD mode classify-plan-execute-review-report | Live | `integration/RunOrchestrator.java`, `harness/SequentialExecutor.java` |
| Plan gate approval/rejection | Live | `RunController`, `RunLifecycleService`, frontend `IdePlan` |
| PATCH mode autofix | Live | `engine/CodeGenEngineImpl.java`, `finding/FindingPatchSubmitter.java` |
| Multi-repo execution and PR creation | Live | `integration/scm/ScmPushService.java` |
| Verified score additive model + trust caps | Live | `report/ValidationReportService.java` |
| Verification ledger in validation reports | Live | `report/model/VerificationEvidenceDTO.java` |
| Historical score backfill | Operator-only | `report/VerifiedScoreBackfillService.java`, `/api/admin/validation-reports/backfill-verified-score` |
| Run event replay | Live | SSE + Redis replay, `/api/runs/{id}/event-log` |
| Run chat over completed run workspace | Live | `chat/ChatService`, `chat/RunChatTools` |
| Pause/resume | Flag-gated | `v2.pause.enabled` |
| Verify-before-complete | Flag-gated default OFF | `worker.verify-before-complete.enabled`, `engine/WorkerAgent.java` |
| Worker background jobs | Flag-gated default OFF | `tool/BackgroundJobTool.java`, `tool/JobRegistry.java` |
| Local-hands run submit (`localExecution=true`) | Default ON after V244, PAT-only | `worker.local-hands.enabled`, `RunController.submit`, `JwtAuthenticationFilter` |
| Live cost snapshot with cache/reasoning/call fields | Live | `/api/runs/{id}/cost`, `cost/RunCostTracker` |
| Durable per-call LLM usage ledger | Live | `cost/RunStepUsageRecorder`, `run_step_usage` (V243) |

## Agent Sessions (CLI / VS Code Control Plane)

`agentsession/` (V240) is a durable conversation protocol for the CLI and VS Code clients, kept deliberately separate from `pipeline_run`:

- Tables `agent_session`, `agent_turn`, `agent_action_event`, `agent_usage_ledger`; REST surface under `/api/agent` (action catalog, sessions, streaming + recorded turns, context resolution, gate decision cards).
- CLI/VS Code chat prefers `POST /api/agent/sessions/{id}/turns/stream`; the legacy `POST /api/spec/chat` remains only as a compatibility fallback for older servers.
- Open-ended turns can propose `create_run`, `local_code_task`, or `approve_run` but return them as typed confirmation-required proposals; the session protocol does not implicitly create or approve a run.
- When a session carries a `projectId`, `SpecChatService` binds the project/code tool surface (`ProjectChatTools`) alongside run-control tools (RL-2026-06-035), so chat can answer grounded project/code questions, not just launch runs.

Billing boundary: run credits are charged only through the plan-approval path. Session usage is metered separately in `agent_usage_ledger` (`billingPolicy=INCLUDED` until a conversation-billing policy exists), so chat cannot silently consume run credits.

## Run Telemetry (W0)

- `GET /api/runs/{id}/cost` returns `schemaVersion=1`, `source`, `cacheReadTokens`, `cacheWriteTokens`, `reasoningTokens`, and `calls`. Live V2 runs read from `RunCostTracker`; terminal/legacy rows expose null for fields older runs do not store.
- `sse/RunEvent` carries an additive envelope (`schemaVersion=1`, typed `data`) while preserving the legacy string `payload`; `TOKEN_USAGE`, `TOOL_CALL`, `TASK_WAVE_PLAN`, `GOAL_DECISION`, `CONTINUATION_DECISION`, `CONTINUATION_ACTION`, and `CONTINUATION_ACTION_SKIPPED` expose typed fields. `CONTINUATION_ACTION.data.status` distinguishes automatic `EXECUTED` reruns from user-approved `APPROVED` upgrades. These goal/continuation event types also trigger `RunDetailSseService` to emit a fresh `RUN_DETAIL`, so the Steps tab can show persisted blocked-upgrade proposals and action history live without waiting for another task-status event; `SequentialExecutor` writes `agent_task.goal_doneness_json` / `continuation_decision_json` before emitting the live decision event that drives that refresh. `TASK_WAVE_PLAN` is an advisory, run-level safe-parallelism plan with `executionMode=SEQUENTIAL_ADVISORY`, bounded waves, serialized tasks, and the configured `maxParallelTasks`; it does not mean the executor is running tasks concurrently. `GOAL_DECISION` includes goal, recommended next step, validation, environment-block and unsafe-continuation signatures, no-progress/false-done/repeated-failure/edit-churn guard evidence, low-completion-confidence evidence for soft-completed edits, and budget evidence, including cost/token/cache/reasoning and wall-clock values when available. `CONTINUATION_DECISION` includes the semantic or complexity-fallback task category, applied quality threshold, default fallback threshold, resolved category threshold map, and `approvalRequiredReason` (`BENCHMARK_PRIOR_REQUIRED` or `EXPENSIVE_CONTINUATION_CONFIRMATION_REQUIRED`) when a blocked upgrade proposal needs user approval. The run-detail Steps tab renders those values against configured ceilings with remaining items, risks, and next-step guidance (RL-2026-06-043 / RL-2026-06-198).
- `WorkerAgent` read-only tool batches are the one automatic parallel runtime path: when `execution.continuation.parallel-read-tools-enabled=true`, a same-turn batch made only of `readFile`, `searchFiles`, `listFiles`, `gitStatus`, or `gitDiff` can run concurrently on virtual threads. Mutating, shell/build/test, remote, partial, or timed-out batches fall back to sequential dispatch. Start/done server logs and legacy `LOG` SSE lines include call labels plus per-call `ok`/`err` results, so the parallel work is visible without changing final tool-response ordering.
- `ModelProviderCandidateService` independently evaluates provider/model candidates in a bounded parallel batch when `execution.continuation.parallel-provider-evaluation-enabled=true` and more than one candidate remains after allow-list filtering. It reuses `execution.continuation.max-parallel-tasks` as the cap, logs start labels and done result labels, preserves deterministic model-order output, and writes the evaluation mode into candidate reasons consumed by continuation decisions. Candidate quality also includes fail-soft benchmark priors from compatible latest DONE `benchmark_run` rows (`resolved_tasks / total_tasks`) for the same provider/model, merged as one quality signal alongside local and caller-supplied validation/benchmark signals.
- `execution.continuation.acting.enabled` defaults true: selected-candidate `STEP_UP_MODEL`, `STEP_DOWN_MODEL`, and `CHANGE_PROVIDER` decisions rerun the same task once automatically inside the configured ceilings. On primary build-smoke failure, `SequentialExecutor` asks the continuation advisor before spending the old same-provider build-error retry; if a different selected provider/model is available within policy, the stronger rerun happens immediately. If the selected upgrade needs benchmark-confidence or spend confirmation, the task is marked `PARTIAL`, the `STOP_BLOCKED` proposal is persisted, and the same-provider retry is skipped so the user can approve the stronger route instead of burning weak-model tokens. Otherwise the legacy same-provider retry continues. Each decision receives the current provider-switch / model-step usage counters, provider-breakdown evidence, and run-specific provider availability from `AvailableProviderResolver`; the policy payload exposes `providerSwitchesUsed`, `modelStepUpsUsed`, `modelStepDownsUsed`, and `providerBehaviorPoor` beside the max values. Worker `ERROR` outcomes and provider/router/timeout-style failure text set `providerBehaviorPoor=true`, allowing the same advisor to prefer a bounded `CHANGE_PROVIDER` when the current route broke down. Candidate ranking keeps unavailable providers visible but forced below threshold, so automatic upgrades do not select a provider missing keys or disallowed by org policy. Advisor-backed policy payloads also include compact ranking evidence (`rankingPolicy=quality_desc_then_cost_asc_then_latency_asc`, `rankingQualityTieBand`, `rankingReasons`, top eligible candidates, and rejected-below-threshold candidates) so model upgrades remain auditable after reload. The ranking rule filters out candidates below the required quality threshold, builds anchored quality bands using `execution.continuation.quality-tie-band` (default 1.0 quality point), then breaks near-quality ties by cost and latency inside each band. `execution.continuation.require-benchmark-prior-for-automatic-continuation` defaults true, so automatic selection first prefers candidates with usable benchmark quality priors; candidates without usable priors stop as `STOP_BLOCKED` for approval only when no benchmark-backed option fits. `RunRequest.requireBenchmarkPriorForAutomaticContinuation` can override that benchmark guard for one submitted run, and web Compose exposes it as "Require benchmark evidence"; disabling it permits the quality-ranked selected candidate to auto-run even without a compatible benchmark prior. Setting server config `require-confirmation-before-expensive-continuation=true` or per-run `RunRequest.requireConfirmationBeforeExpensiveContinuation=true` also blocks the rerun as `STOP_BLOCKED` while preserving the proposed selected candidate; web Compose exposes this per-run flag as the "Confirm model upgrades" advanced option. `POST /api/runs/{id}/tasks/{taskId}/continuation/approve` validates either saved blocked proposal kind, queues the same-task retry with the stored provider/model, appends an `APPROVED` continuation action-history entry only after the retry is accepted, and emits typed `CONTINUATION_ACTION` with `status=APPROVED`. Approved task-boundary retries resolve terminal status from the worker retry; approved post-review retries also run reviewer + validation report generation before terminal status so reviewer-quality failures get fresh proof after the upgrade. Approved retries seed provider-switch or model-step usage from the stored proposed action, so the retry remains inside the same escalation budget. They also snapshot `RunCostTracker` around the worker retry and attribute the delta to the target task; post-review approvals add the reviewer delta to reviewer phase totals, keeping upgrade cost visible in run and task evidence. The run-detail Steps tab shows an Approve upgrade action for those states and prints whether approval is needed because the candidate lacks a usable benchmark prior or because the run requires upgrade-spend confirmation.
- Approved blocked-continuation retries also set `userConfirmedExpensiveContinuation=true` plus the approved action/provider/model scope for the resumed execution. The confirmation bypass only applies when the later policy-selected action and candidate exactly match the stored approval; a different provider/model/action is still blocked by confirmation or benchmark-prior guards.
- The run-detail Steps tab renders advisor-backed continuation ranking evidence when present: ranking rule, quality tie band, benchmark-confidence mode, top provider/model candidates, quality/cost/latency metrics, benchmark-prior marker, and rejected-below-quality-threshold count. This is the reloadable UI view of the quality > cost > latency ordering persisted in `TaskSummary.continuationDecision.policy`.
- Executed, skipped, and approved continuation actions are appended to `agent_task.continuation_actions_json` and exposed as `TaskSummary.continuationActions`, so the run-detail Steps tab still shows that a model step-up/provider switch happened after the rerun's final task-boundary decision overwrites the latest advisory JSON. When a successful or warning-level task completed through an automatic `STEP_UP_MODEL` or `CHANGE_PROVIDER` action, the final BUILD-mode reviewer is routed to that action's provider/model instead of reverting to the original run route; normal task complexity tiering and `STEP_DOWN_MODEL` actions do not promote the reviewer.
- `execution.continuation.post-review.enabled` defaults true. When the BUILD-mode reviewer returns `UNRESOLVED_ISSUES`, `REVIEW_FAILED`, or throws, `RunOrchestrator` emits a post-review `CONTINUATION_DECISION` for the latest usable worker task through the same quality-first advisor, including run-specific provider availability. If that decision is `STEP_UP_MODEL` or `CHANGE_PROVIDER` with a selected candidate, the orchestrator automatically resets the target task to `PENDING`, reruns only that task through the resume-aware executor, persists the new task result, runs lint, and re-runs the BUILD-mode reviewer against the updated workspace. The second worker pass writes only the target task row from its task snapshot instead of redistributing across synthetic skipped resume outcomes. The second reviewer pass uses additive phase attribution so reviewer token columns show first review + post-review review, while run totals add only the new delta. `STOP_BLOCKED` proposals still require approval, and `STEP_DOWN_MODEL` is never executed for reviewer failure.
- Reviewer exceptions are materialized as `ReviewerResult.Overall.REVIEW_FAILED`, and terminal status resolution checks that verdict before final-build failure, so validation/reporting surfaces keep `REVIEWER_FAILED` evidence.
- Persisted benchmark rows only become model-quality priors when `benchmark_run.total_tasks >= execution.continuation.benchmark-min-sample-tasks` (default 20) and the benchmark suite is compatible with the current task category. Terminal-Bench rows can back `TERMINAL_TASK` / `BENCHMARK_PATCH`; SWE-bench rows can back bug/test/project patch categories; ProgramBench rows can back bug/test/benchmark patch categories. Smaller or incompatible rows still appear in candidate reasons, but the router falls back to local/validation/YAML quality instead of treating the model as fully benchmarked for unrelated work. `selectedCandidate.benchmarkQualityPriorAvailable` is exposed on SSE/API JSON so clients can show why an upgrade needs confirmation.
- `GOAL_DECISION` also emits `staleExplanationCount=current/max` from `execution.continuation.max-stale-explanations` when the same assistant explanation repeats without new write or validation proof.
- `GOAL_DECISION` emits `completionConfidenceLow=true` when a worker made meaningful edits but did not cleanly prove completion. Those cases remain `NOT_DONE_BUT_PROGRESSING`, but `anotherModelProviderMightHelp=true` lets the continuation advisor propose a bounded step-up/change-provider path instead of treating a smoke-pass warning as good enough.
- `GOAL_DECISION` repeated-failure evidence groups result-bearing `shell`, `bash`, `buildProject`, and `runTests` log entries by stable tool/argument signature before applying `execution.continuation.max-repeated-failures`, so the waste guard catches the same failing command/test even when each failure has different stderr text. Normal worker tool-call logs include `-> OK` / `-> ERR` for this signal.
- Terminal waste/failed continuation stops explain when no useful model/provider escalation remains within configured limits.
- `GET /api/runs/{id}/report` returns the final `verificationLedger[]` plus conservative `runGoalDecision` proof, and the run-result Evidence tab renders both the final proof summary and the raw ledger. Reviewer `WARN` / `FAIL` evidence is treated as reviewer low-quality failure in that proof, with `anotherModelProviderMightHelp=true`, so unresolved review is an explicit escalation signal rather than a vague unproven state.
- `RunDetail.runGoalDoneness` is the durable final run-level copy of that proof, written to `pipeline_run.run_goal_doneness_json` when the validation report is generated so run detail can reload the final goal state without fetching the report first. Its `goalSummary` uses the original `PipelineRun.userRequest` when available, then planner title/summary, then the legacy run-id fallback.
- Run-scoped agent sessions receive the same typed task-wave and goal/continuation signals as durable `agent_action_event` rows (`RUN_TASK_WAVE_PLAN`, `RUN_GOAL_DECISION`, `RUN_CONTINUATION_DECISION`, `RUN_CONTINUATION_ACTION`, `RUN_CONTINUATION_ACTION_SKIPPED`) via `AgentRunEventBridge`, and final validation ledgers plus conservative `runGoalDecision` proof as `RUN_VALIDATION_EVIDENCE` via `AgentValidationReportEventBridge`, so `/api/agent/sessions/{id}/events` can reload the parallelism, decision, and evidence trail for web/CLI/VS Code clients.
- `RunStepUsageRecorder` writes every successful LLM call to `run_step_usage` (V243) with run/task/provider/model/token/cache/reasoning/cost/call-index plus producer attribution (`agentRole`, `stepId`, `retryOf`); same-provider retries and cross-provider failover rows point back to the original role/step (RL-2026-06-044). Raw provider-usage capture and typed `TOOL_RESULT`/`STEP` events remain W0 follow-ups.

## Safety Invariants

- Late status writes must not clobber `CANCELLED` or `PAUSED`.
- Patch-mode terminal paths should go through the same lifecycle termination path as BUILD mode.
- Worker tools are schema-bound only when their feature flags are enabled.
- File writes and edits pass through `CodeAbuseScanner`.
- Shell commands pass through `ShellSandboxPolicy`.
- Run ownership and submit-time continuation policy markers are stamped before async work starts.
- A high `verifiedScore` must be backed by the ledger; failed reviewer/build/test
  signals cap the headline even when other slots are strong.
- `localExecution=true` is accepted only from PAT-authenticated (CLI/VS Code) requests, never browser/JWT sessions. If an operator turns `worker.local-hands.enabled` OFF, local execution fails closed before quota/billing. Accepted summaries echo `localExecutionAccepted=true`.

## Current Proof Level

Automated verification from the 2026-06-10 cleanup batch:

- `mvn verify -q`: 1699 tests, 0 failures/errors/skips.
- `mvn spotless:check -q`: pass.
- `npm --prefix frontend run build`: pass.
- `npm --prefix frontend run format:check`: pass.
- `npm --prefix frontend run lint`: pass with warning-tolerant existing unused-import warnings.

Not yet proven in this batch:

- Real cancellation race against an active external worker.
- Real pause/resume through a live long-running job.
- Real SCM auto-push patch run.
- Authenticated browser smoke across every protected route.

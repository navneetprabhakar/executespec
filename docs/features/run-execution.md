# Run Execution

## What Exists

ExecuteSpec's production run path is the V2 BUILD pipeline:

1. `RunController.submit` accepts a run request and stamps owner/org/project fields before async execution.
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
- `sse/RunEvent` carries an additive envelope (`schemaVersion=1`, typed `data`) while preserving the legacy string `payload`; `TOKEN_USAGE`, `TOOL_CALL`, `GOAL_DECISION`, `CONTINUATION_DECISION`, `CONTINUATION_ACTION`, and `CONTINUATION_ACTION_SKIPPED` expose typed fields. `GOAL_DECISION` includes goal, validation, no-progress/false-done guard evidence, and budget evidence, including cost/token/cache/reasoning and wall-clock values when available; the run-detail Steps tab renders those values against configured ceilings with remaining items and risks (RL-2026-06-043 / RL-2026-06-198).
- `GET /api/runs/{id}/report` returns the final `verificationLedger[]` plus conservative `runGoalDecision` proof, and the run-result Evidence tab renders both the final proof summary and the raw ledger.
- Run-scoped agent sessions receive the same typed goal/continuation signals as durable `agent_action_event` rows (`RUN_GOAL_DECISION`, `RUN_CONTINUATION_DECISION`, `RUN_CONTINUATION_ACTION`, `RUN_CONTINUATION_ACTION_SKIPPED`) via `AgentRunEventBridge`, and final validation ledgers plus conservative `runGoalDecision` proof as `RUN_VALIDATION_EVIDENCE` via `AgentValidationReportEventBridge`, so `/api/agent/sessions/{id}/events` can reload the decision and evidence trail for web/CLI/VS Code clients.
- `RunStepUsageRecorder` writes every successful LLM call to `run_step_usage` (V243) with run/task/provider/model/token/cache/reasoning/cost/call-index plus producer attribution (`agentRole`, `stepId`, `retryOf`); same-provider retries and cross-provider failover rows point back to the original role/step (RL-2026-06-044). Raw provider-usage capture and typed `TOOL_RESULT`/`STEP` events remain W0 follow-ups.

## Safety Invariants

- Late status writes must not clobber `CANCELLED` or `PAUSED`.
- Patch-mode terminal paths should go through the same lifecycle termination path as BUILD mode.
- Worker tools are schema-bound only when their feature flags are enabled.
- File writes and edits pass through `CodeAbuseScanner`.
- Shell commands pass through `ShellSandboxPolicy`.
- Run ownership is stamped before async work starts.
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

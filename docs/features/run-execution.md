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
| PATCH mode autofix | Live | `agent/PatchAgent.java`, `finding/FindingPatchSubmitter.java` |
| Multi-repo execution and PR creation | Live | `integration/scm/ScmPushService.java` |
| Verified score additive model + trust caps | Live | `report/ValidationReportService.java` |
| Verification ledger in validation reports | Live | `report/model/VerificationEvidenceDTO.java` |
| Historical score backfill | Operator-only | `report/VerifiedScoreBackfillService.java`, `/api/admin/validation-reports/backfill-verified-score` |
| Run event replay | Live | SSE + Redis replay, `/api/runs/{id}/event-log` |
| Run chat over completed run workspace | Live | `chat/ChatService`, `chat/RunChatTools` |
| Pause/resume | Flag-gated | `v2.pause.enabled` |
| Verify-before-complete | Flag-gated default OFF | `worker.verify-before-complete.enabled`, `engine/WorkerAgent.java` |
| Worker background jobs | Flag-gated default OFF | `tool/BackgroundJobTool.java`, `tool/JobRegistry.java` |

## Safety Invariants

- Late status writes must not clobber `CANCELLED` or `PAUSED`.
- Patch-mode terminal paths should go through the same lifecycle termination path as BUILD mode.
- Worker tools are schema-bound only when their feature flags are enabled.
- File writes and edits pass through `CodeAbuseScanner`.
- Shell commands pass through `ShellSandboxPolicy`.
- Run ownership is stamped before async work starts.
- A high `verifiedScore` must be backed by the ledger; failed reviewer/build/test
  signals cap the headline even when other slots are strong.

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

# Harness V2 — Remaining Work

> Consolidated from `harness-v2-execution-plan.md`, `harness-v2-ui-parity-plan.md`,
> and `harness-analysis-part3-competitors.md` (all deleted 2026-04-13).
> V2 is production-default. This doc only tracks what is still incomplete.

## Status snapshot

| Area | Status |
|---|---|
| Tool layer (10 tools + completeTask) | ✅ done |
| LlmRouterV2 (native tool calling, 3 providers) | ✅ done |
| WorkerAgentV2 (sequential, native completeTask) | ✅ done |
| PlannerAgentV2 (restricted-tool plan, runtimeSurface, hardConstraints) | ✅ done |
| SequentialExecutorV2 (per-task tier routing, smoke test, retry) | ✅ done |
| ReviewerAgentV3 (general + wiring passes) | ✅ done |
| ContextStoreV2 (Redis persistence, per-task) | ✅ done |
| HarnessVersionResolver + flag routing | ✅ done |
| V2Metrics observability | ✅ done |
| V2RunOrchestrator + V1-table persistence (UI parity) | ✅ done |
| V2RunCostTracker + per-phase attribution | ✅ done |
| V2DeferredActionGuard (501 shim for V1-only endpoints) | ✅ done |

## Gap 1 — Phase 6: batch execution in V2

**Status:** not implemented. V2 runs are realtime-only.

**Plan:** see [v2-batch-execution-plan.md](v2-batch-execution-plan.md).

**Sequencing constraint:** waits on deprecation P1 (V1 `batch/*` deletion). Implementing V2 batch before that creates duplicate poll services competing for the same Shedlock key.

**Acceptance:**
- Batch-mode approval on a V2 run runs worker-tasks through the 3 provider batch APIs.
- Reviewer / test-gen / preview stay realtime.
- Eval matrix (`scripts/v2-eval.sh --mode batch`) matches realtime accuracy within 5%, at ~50% of the realtime credit cost.

## Gap 2 — Startup resume for V2 runs

**Status:** not implemented. A JVM crash mid-run leaves the run stuck in its last persisted status.

**What's needed:** `StartupRunResumerV2` — boot-time scan of `PipelineRun` rows where `harness_version = 'v2'` and `status ∈ {PLANNING, WORKING, REVIEWING, AWAITING_APPROVAL}`. For each:
- If `AWAITING_APPROVAL`: re-signal the gate (same as V1 behaviour).
- If `WORKING` or `REVIEWING`: load the latest `v2:agent-ctx:{runId}:{taskId}` snapshot from Redis; resume `WorkerAgentV2` at the saved iteration. If the snapshot is missing (TTL expired), mark the run `FAILED` with a clear reason.

**Acceptance:** kill the JVM mid-run, restart, run reaches a terminal state without user intervention. Add an integration test that uses a `TestJvmStopSignal` to simulate crash.

**Estimated size:** ~400 LOC + migration.

## Gap 3 — UI parity deferred endpoints

**Status:** `V2DeferredActionGuard` returns 501 for the endpoints below. Each is either deferred by design or blocked on another gap.

| Endpoint | Status | Unblock by |
|---|---|---|
| `POST /runs/{id}/retry` (full-run) | 501 | Gap 2 (startup resume); same mechanism |
| `POST /runs/{id}/pause` + `resume` | 501 | Gap 2 |
| `POST /runs/{id}/rework` | 501 | V2 plan-iteration flow (not yet specced) |
| `POST /runs/{id}/reopen-merge` | 501 | V2 has auto-merge only — verify this endpoint is still needed or can be removed |
| `POST /runs/{id}/tasks/{taskId}/retry` | 501 | Wire to `SequentialExecutorV2.executeOne(task)` |
| `POST /runs/{id}/implement-suggestions` | 501 | V2 chat/iterate flow (separate spec) |
| `POST /runs/{id}/retry-validation` | 501 | Wire to `V2RunOrchestrator.generateValidationReport` |
| `POST /runs/{id}/edit-plan` | 501 | Needs planner re-entry — defer until after batch |
| `POST /runs/{id}/clarify` + `chat` + `apply` + `iterate` | 501 | V2 ConversationAgent integration (scope-TBD) |
| `POST /runs/{id}/run-tests` + `apply-test-fixes` | 501 for V2 | Wire once V2 test-gen is fully plumbed through `TestGenOrchestrator` |
| `POST /runs/{id}/merge` | 501 for V2 | V2 auto-merges — confirm the endpoint can be removed for V2 entirely |

**Recommended order:**
1. Gap 2 (startup resume) unblocks 5 of the above.
2. Per-task retry is a one-liner — quick win.
3. Re-open-merge and merge endpoints: delete rather than wire (V2 has no separate merge gate).
4. Chat / iterate / edit-plan / rework / clarify: separate design doc; out of scope for V2 parity.

## Principles this architecture commits to

(Extracted from the deleted competitor-analysis doc; captured here so the intent survives.)

- **Sequential over parallel.** One task, one worker, one loop. No subtask decomposition, no ownership walls.
- **Native tool calling over XML.** Strict JSON schemas for every tool. PATH B (`<tool_call>…`) does not exist in V2.
- **Ground truth during execution.** Every task ends with a smoke test (`buildProject` by default). Reviewer re-runs the build after fixes.
- **Upfront context, not search-first.** Workspace file tree + planner-declared dependency files injected into the worker's initial system prompt. `readFile` is used to drill in, not to discover.
- **Provider-agnostic at the agent layer.** All provider quirks (OpenAI `extra_body` bug, Gemini thought-signatures, Anthropic cache blocks) live in the V2 native tool clients. The agent loop doesn't know which provider it's talking to.
- **Redis-persisted context.** Every 5 iterations, best-effort. Crash recovery is planned (Gap 2); context replay is already possible via `ContextStoreV2.load()`.

## Out of scope

- V2 does not and will not re-introduce the V1 "progressive merge" model. Auto-merge is structural.
- V2 does not support parallel task execution. If a future workload motivates it, it goes in a new `ParallelExecutorV2` — not in the sequential path.
- V2 does not support XML tool calls. Any provider that adds regressions around native tool calling gets a client wrapper (as done for OpenAI + Gemini), not a fallback.

# Harness V2 — Remaining Work

> Consolidated from `harness-v2-execution-plan.md`, `harness-v2-ui-parity-plan.md`,
> and `harness-analysis-part3-competitors.md` (all deleted 2026-04-13).
> V2 is the only harness — V1 deleted in deprecation P3 (2026-04-13).

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
| V2Metrics observability | ✅ done |
| V2RunOrchestrator + V1-table persistence (UI parity) | ✅ done |
| V2RunCostTracker + per-phase attribution + run-stats fix | ✅ done |
| StartupRunResumerV2 fail-cleanly + resume_attempts cap + 6h stale cutoff | ✅ done (Phase 1.5) |
| V2 cancel via `DELETE /api/runs/{id}` | ✅ done |
| ScmPushService extracted from V1 harness | ✅ done |
| **V1 harness deletion (deprecation P1–P6)** | ✅ done 2026-04-13 |
| HarnessVersionResolver + V2DeferredActionGuard | retired with V1 |

## Gap 1 — Phase 6: batch execution in V2

**Status:** not implemented. V2 runs are realtime-only.

**Plan:** see [v2-batch-execution-plan.md](v2-batch-execution-plan.md).

**Sequencing constraint:** ✅ unblocked — V1 `batch/*` deleted in deprecation P3 (2026-04-13). Ready to start P0 probe.

**Acceptance:**
- Batch-mode approval on a V2 run runs worker-tasks through the 3 provider batch APIs.
- Reviewer / test-gen / preview stay realtime.
- Eval matrix (`scripts/v2-eval.sh --mode batch`) matches realtime accuracy within 5%, at ~50% of the realtime credit cost.

## Gap 2 — Startup resume for V2 runs (full replay)

**Status:** Phase 1.5 shipped (fail-cleanly + resume_attempts cap of 3 + 6h stale cutoff). Full conversation replay still pending.

**Phase 1 (shipped):** boot-time scan marks non-terminal V2 runs FAILED with a clear reason + terminal SSE so the UI surfaces the interruption.

**Phase 1.5 (shipped 2026-04-13):** `pipeline_run.resume_attempts` column + 3-attempt cap + 6h stale cutoff prevent infinite resume loops across deploys.

**Phase 2 (pending — full replay):** for `WORKING` / `REVIEWING` runs with a fresh Redis snapshot, load `v2:agent-ctx:{runId}:{taskId}` and resume `WorkerAgentV2` at the saved iteration instead of failing.

**Acceptance:** kill the JVM mid-run, restart, run reaches a terminal state without user intervention. Integration test that simulates JVM stop.

**Estimated size:** ~400 LOC for Phase 2.

## Gap 3 — Endpoints returning 410 Gone in V2

After deprecation P2 the following endpoints return **HTTP 410 Gone** (was 501 via the retired `V2DeferredActionGuard`). Each is either deferred by design or blocked on another gap.

| Endpoint | Status | Unblock by |
|---|---|---|
| `POST /runs/{id}/retry` (full-run) | 410 | Gap 2 Phase 2 (full replay) |
| `POST /runs/{id}/pause` + `resume` | 410 | Gap 2 Phase 2 |
| `POST /runs/{id}/rework` | 410 | V2 plan-iteration flow (not yet specced) |
| `POST /runs/{id}/reopen-merge` | 410 | Permanent — V2 auto-merges |
| `POST /runs/{id}/tasks/{taskId}/retry` | ✅ wired baseline (`V2RunOrchestrator.retryTask`) | Full replay arrives with Gap 2 Phase 2 |
| `POST /runs/{id}/implement-suggestions` | 410 | V2 chat/iterate flow (separate spec) |
| `POST /runs/{id}/retry-validation` | ✅ wired (`V2RunOrchestrator.retryValidation`) | — |
| `POST /runs/{id}/edit-plan` | 410 | Needs planner re-entry — defer until after batch |
| `POST /runs/{id}/clarify` + `chat` + `apply` + `iterate` | 410 | V2 ConversationAgent integration (scope-TBD) |
| `POST /runs/{id}/run-tests` + `apply-test-fixes` | 410 for V2 | Wire once V2 test-gen is fully plumbed through `TestGenOrchestrator` |
| `POST /runs/{id}/merge` | 410 — permanent | V2 auto-merges; endpoint can be removed from frontend |
| `POST /api/feasibility` | 410 — permanent | Use plan-approval estimate instead |

**Recommended order:**
1. Gap 2 Phase 2 (full replay) unblocks `retry` / `pause` / `resume`.
2. `merge` / `reopen-merge` / `feasibility` / `clarify`: remove from frontend; permanent 410.
3. Chat / iterate / edit-plan / rework / implement-suggestions: separate design doc; out of scope for V2 parity.

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

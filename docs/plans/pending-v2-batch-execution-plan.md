# V2 Batch Execution — Design & Execution Plan

> Status: ready to execute (2026-04-13).
> **Sequencing dependency unblocked** — V1 `batch/*` was deleted in deprecation-removal P3 (commit `154169d9`, 2026-04-13). P0 probe + P1 scaffolding can start immediately.

## Goal

Port V1's batch-execution path to V2 primitives. Users who approve a plan in BATCH mode pay the 50% provider discount; worker tasks run async against Anthropic / OpenAI / Gemini Batch APIs; reviewer + test-gen + preview stay realtime. Functional parity with V1 batch, not a new user-facing capability.

## Non-goals

- Not changing the billing model (still 50% credit savings at plan approval).
- Not batching reviewer / test-gen / test-planner / accuracy-assessment calls. Those stay realtime (V1 parity).
- Not supporting mixed real-time + batch within a single run. A run is wholly batch or wholly realtime, chosen once at plan approval.
- Not introducing provider-specific user-facing features. The three batch APIs (Anthropic `/v1/messages/batches`, OpenAI `/v1/files` + `/v1/batches`, Gemini inline+file) remain an implementation detail.

## Open questions (resolve before implementing)

1. **Tool calling in batch mode** — V1 batch did not use native tool calling; it used `<tool_call>` XML in completion text. V2 workers use provider-native tool calling end-to-end. All three batch APIs accept the same request body as their realtime counterpart (including `tools` / `tool_choice`), so native tool calling should work — but we should verify on each provider before committing to it. **Proposed: verify via one probe per provider in P0; fall back to XML-tool-calling only if native is broken in batch.**
2. **Multi-turn in batch** — a worker conversation is multi-turn (LLM → tool → LLM → tool → …). Batch APIs are single-shot: one request in, one response out. Options:
   - **(a) Batch only the FIRST turn** of each task; remaining turns run realtime. Simple; captures a fraction of the discount.
   - **(b) Serialize each turn as a separate batch submission** (submit → wait → dispatch tools locally → resubmit). High latency (minutes per turn × 40 iterations), likely impractical.
   - **(c) "Plan-only" batch** — the planner call is the ONLY batched call; all worker turns run realtime. Gives discount on the expensive planning call.
   - **(d) Single-shot workers** — at plan approval in batch mode, the planner's `SINGLE_SHOT` path is forced; worker call is one turn with all tools the model can take, completeTask returns result. Requires planner to classify aggressively; breaks for anything non-trivial.

   **Proposed: (a) for v1.** Batch the first worker turn per task (usually reads + first writes). Any remaining turns go realtime. Document the tradeoff; revisit after measuring actual discount capture.
3. **Retry semantics** — V1 auto-retries failed batch tasks in realtime. Same in V2? **Proposed: yes, unchanged. Retry via `SequentialExecutorV2`.**
4. **Credit refund on partial batch failure** — V1 refunds only on full FAILURE. If 3/4 tasks succeed in batch + 1 retries realtime, net credits may exceed initial estimate. **Proposed: keep V1 behaviour — no refund on partial, retry cost absorbed by the 50% headroom.**

## Architecture

```
[plan approval: executionMode=BATCH]
        │
        ▼
V2RunOrchestrator.submit() ── routes to ──▶ V2BatchCoordinator.submit(plan)
                                                    │
                                                    ├─▶ V2BatchRequestBuilder.build(task, plan, ctx)
                                                    │      (one BatchRequest per task's first turn)
                                                    │
                                                    ▼
                                         V2BatchClientRouter.submit(provider, requests)
                                           ├─ V2AnthropicBatchClient
                                           ├─ V2OpenAiBatchClient      ◀── extracted / forked from V1
                                           └─ V2GeminiBatchClient
                                                    │
                                                    ▼
                                         BatchJob{runId, providerJobId, state=QUEUED}
                                                    │
                                                    ▼
                                         Redis-keyed job index  v2:batch:{runId} → provider + jobId
                                                    │
                                                    ▼
                                         [@Scheduled every 30s] V2BatchPollService
                                                    │
                                                    ▼ (terminal)
                                         V2BatchResumeService.resume(runId, results)
                                                    │  parse results → write files → append to history
                                                    │
                                                    ▼
                                         SequentialExecutorV2.continueFromBatch(runId, perTaskHistory)
                                                    │  for each task:
                                                    │    if first-turn hit completeTask → record SUCCESS
                                                    │    else resume loop realtime (WorkerAgentV2)
                                                    ▼
                                         Normal V2 post-exec path: reviewer → report → testgen → preview
```

New V2 classes:
- `v2/batch/V2BatchCoordinator.java` — entry point called by orchestrator
- `v2/batch/V2BatchRequestBuilder.java` — composes a batch request from a `PlannedTaskV2` + initial system/user prompt + tool schema
- `v2/batch/V2BatchClient.java` — provider-agnostic interface (submit / poll / fetchResults / cancel)
- `v2/batch/V2AnthropicBatchClient.java`
- `v2/batch/V2OpenAiBatchClient.java`
- `v2/batch/V2GeminiBatchClient.java`
- `v2/batch/V2BatchClientRouter.java` — provider → client dispatcher
- `v2/batch/V2BatchPollService.java` — `@Scheduled(30s)` + `@SchedulerLock`
- `v2/batch/V2BatchResumeService.java` — parse results, hand off to executor
- `v2/batch/BatchJob.java` (record) — in-flight job state persisted to DB (or Redis + DB for recovery)
- `v2/batch/BatchTaskResult.java` — per-task result: assistant message + tool calls

Reused:
- `v2/agent/LlmRouterV2.java` — **unchanged**; batch bypasses the router
- `v2/tool/ToolRegistryV2.java` — reused for tool schema serialization + post-batch local dispatch
- `v2/agent/WorkerAgentV2.java` — **extended** with `resumeFromBatch(history, ctx)` that picks up at iteration 2+ using the existing loop
- `v2/harness/SequentialExecutorV2.java` — **extended** with batch-aware task lifecycle (`executeBatch(plan)` splits submission from completion)

## Component-by-component plan

### 1. V2BatchClient contract

```java
public interface V2BatchClient {
    V2BatchJob submit(List<V2BatchRequest> requests);
    V2BatchJobState poll(V2BatchJob job);
    List<V2BatchTaskResult> fetchResults(V2BatchJob job);
    void cancel(V2BatchJob job);
    LlmProvider provider();
}
```

### 2. Request shape

```java
public record V2BatchRequest(
        String taskId,                 // correlates response → task
        String model,                  // tier-resolved (HIGH → PLANNING, etc.)
        List<Message> messages,        // same spring-ai Message list LlmRouterV2 builds
        List<ToolCallback> tools,      // same tools WorkerAgentV2 would bind
        Double temperature,
        Integer maxTokens) {}
```

Serialization reuses the wire-format adapters already written for the native tool clients:
- `OpenAiNativeToolClient.serializeMessages(...)` → lift into `V2OpenAiBatchClient`
- `GeminiNativeToolClient.serializeContents(...)` → lift into `V2GeminiBatchClient`
- Anthropic goes through Spring AI's request shape directly (no wire-adapter needed)

### 3. Polling + persistence

V1 stores batch state in `PipelineRun` (BATCH_PROGRESS fields + `batchProviderJobId`). V2 reuses the same `PipelineRun` row (runs persist there regardless of harness version). New columns **not** required — `batchAvailable`, `batchProgressTotal`, `batchProgressCompleted` already exist.

Poll lock: `@SchedulerLock(name = "v2-batch-poll", lockAtMostFor = "2m")`. Redis-backed Shedlock already configured.

### 4. Resume path

On terminal batch state:
```java
for each taskId in plan.tasks():
    result = resultsByTaskId.get(taskId)
    if result is null:
        sequentialExecutor.executeOne(task)  // fallback: run realtime
        continue
    history = buildInitialHistory(task)
    history.add(assistantTurn(result))       // batch-returned message + toolCalls
    if result.calls.contains(completeTask):
        persistTaskStatus(runId, task.id, "COMPLETE")
        continue
    history.add(toolResponses(dispatch(result.calls)))  // local tool dispatch
    worker.resumeFromBatch(task, history, ctx)          // continue realtime
```

### 5. Retry path

Failed or missing results → `SequentialExecutorV2.executeOne(task)` realtime. Already returns proper `TaskOutcome`. No new code; just invocation.

### 6. SSE + UI

Already wired in V1: `BATCH_PROGRESS`, `BATCH_COMPLETE`, `BATCH_FAILED`. Emit the same events from `V2BatchPollService` / `V2BatchResumeService`. Frontend `useSse.ts` already triggers `fetchRun()` on these.

### 7. Orchestrator wiring

```java
// V2RunOrchestrator.submit(...)
if (approvedExecutionMode == BATCH) {
    v2BatchCoordinator.submit(plan);
    // return — poll service drives the rest
} else {
    executor.execute(...);   // current path
}
```

Batch runs skip auto-preview until resume completes (V1 parity). Reviewer + testgen kick off inside the post-resume continuation path, same as realtime.

## Phased execution

### P0 — Spike / probe (1 day, throwaway branch)
1. Probe Anthropic / OpenAI / Gemini batch APIs with a single-turn native-tool-calling request each. Confirm the response contains `tool_calls` in the same shape as realtime.
2. Measure actual latency (submit → terminal) per provider under typical load. Anthropic advertises up to 24h; OpenAI up to 24h; Gemini up to 24h — real p50 is usually ≤1h for small batches.
3. Document the native-tool-calling verdict per provider (feeds into open question #1).

Deliverable: one-page probe report in `docs/v2-batch-probe-notes.md`. No code on main.

### P1 — Skeleton (1 PR, low risk)
4. Create all V2 batch classes as stubs (interfaces, empty implementations, DI wiring).
5. Feature flag `harness.v2.batch_enabled` (default `false`). No user-visible change.
6. Unit tests for `V2BatchRequestBuilder` + `V2BatchClientRouter` dispatch.

### P2 — Single provider end-to-end (1 PR, medium risk)
7. Implement `V2AnthropicBatchClient` fully (Anthropic batch is the simplest API surface).
8. Wire `V2BatchCoordinator.submit` → `V2BatchPollService` → `V2BatchResumeService` for Anthropic only.
9. Implement `WorkerAgentV2.resumeFromBatch(history, ctx)` — extract the existing loop body; two entry points (fresh start / resume).
10. Dev-only SSE event asserts resume path produces the same verdict as realtime.

Deliverable: Anthropic batch path works end-to-end behind flag.

### P3 — OpenAI + Gemini (1 PR per provider, medium risk)
11. `V2OpenAiBatchClient` — file upload (JSONL) → batch create → fetch output file. Reuse `OpenAiNativeToolClient.serializeMessages()`.
12. `V2GeminiBatchClient` — start with inline mode (≤20 requests) before file mode; reuse `GeminiNativeToolClient.serializeContents()` + thought-signature round-trip (cache already exists).

### P4 — Retest / eval (1 PR, low risk)
13. Extend `scripts/v2-eval.sh` with `--mode batch` flag.
14. Run full 9-cell matrix in batch mode. Compare to realtime baseline:
    - Accuracy score parity (must not regress > 5%)
    - Credit usage parity with 50% discount
    - End-to-end latency (expected: slower by an order of magnitude)
15. Publish results table in `scripts/v2-eval-results/`.

### P5 — Flag flip + docs (1 PR, low risk)
16. Flip `harness.v2.batch_enabled` → `true` in staging config.
17. Update `CLAUDE.md` V2 section + `docs/USER_MANUAL.md` batch flow.
18. CHANGELOG + version bump.

## Test strategy

- **Unit** — batch request builder (tool schema serialization), router dispatch, result correlation by taskId.
- **Contract** — one mock-HTTP test per provider's batch API. Fixture JSON captured from real probe in P0.
- **Integration** — full orchestrator path with a fake `V2BatchClient` that returns canned results after a short delay. Asserts resume path produces the same `TaskOutcome` as realtime.
- **Eval** — `scripts/v2-eval.sh --mode batch` full matrix as gate for flag flip.

## Risks

| Risk | Mitigation |
|---|---|
| Native tool calling unsupported in one provider's batch API | P0 probe detects it; fall back to XML-tool-calling for that provider (V1 pattern, already battle-tested). |
| Multi-turn discount capture lower than 50% sticker price | Explicitly scoped as "first turn only" in v1; measure in P4; follow-up ticket for (b) or (c) if capture is poor. |
| Server crash mid-batch loses in-flight jobs | Persist `batchProviderJobId` on `PipelineRun` at submission. `V2BatchPollService` resumes from DB on startup, same as V1. |
| Batch provider outage blocks completion | 24h timeout threshold; auto-fallback retries remaining tasks in realtime; emits `BATCH_FAILED`. |
| Provider batch latency exceeds user patience | Already documented; 50% discount is the explicit tradeoff. Frontend already shows batch badge + progress bar. |
| Rate limits on batch endpoints differ from realtime | Per-provider. Confirm in P0 probe; if tight, add per-run queuing in `V2BatchCoordinator`. |

## Files that will change

### New (~12 files)
- `v2/batch/V2BatchClient.java` (interface)
- `v2/batch/V2BatchClientRouter.java`
- `v2/batch/V2AnthropicBatchClient.java`
- `v2/batch/V2OpenAiBatchClient.java`
- `v2/batch/V2GeminiBatchClient.java`
- `v2/batch/V2BatchRequestBuilder.java`
- `v2/batch/V2BatchCoordinator.java`
- `v2/batch/V2BatchPollService.java`
- `v2/batch/V2BatchResumeService.java`
- `v2/batch/V2BatchJob.java`, `V2BatchRequest.java`, `V2BatchTaskResult.java` (records)
- Tests: contract fixture per provider + integration test + unit tests

### Modified
- `v2/integration/V2RunOrchestrator.java` — BATCH branch
- `v2/harness/SequentialExecutorV2.java` — `continueFromBatch(...)` entry point
- `v2/agent/WorkerAgentV2.java` — `resumeFromBatch(history, ctx)` method
- `scripts/v2-eval.sh` — `--mode batch` flag
- `CLAUDE.md`, `docs/USER_MANUAL.md`, `CHANGELOG.md`, `VERSION`

Estimated size: ~1200 LOC net.

## Dependency on deprecation removal (#1)

This plan assumes the V1 `batch/*` directory has been **deleted** (deprecation P1). Executing P1–P5 before that deletion would create:
- Duplicate polling services (`BatchPollService` + `V2BatchPollService`) competing for the same scheduler lock.
- Confusion at the router layer about which client the orchestrator should use.
- Twice the surface area to maintain during the transition.

Therefore **sequencing**:
- First: deprecation-removal P1 deletes `batch/*` + strips `PipelineHarness.onBatchComplete`.
- Then: V2 batch P1–P5 lands on a clean slate.

## Suggested commit boundaries

1. `v2(batch): probe notes + P0 findings` (docs only)
2. `v2(batch): scaffolding + router + flag (P1)`
3. `v2(batch): anthropic end-to-end (P2)`
4. `v2(batch): openai client (P3a)`
5. `v2(batch): gemini client (P3b)`
6. `v2(batch): eval harness --mode batch + results (P4)`
7. `docs(batch): CLAUDE.md + user manual + flag flip (P5)`

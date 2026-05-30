# `batch/` — V2 batch-mode execution

> Added 2026-05-30 (B1, audit 2026-05-29). Design + handoff: [`docs/plans/v2-batch-execution-plan.md`](../../../../../../../docs/plans/v2-batch-execution-plan.md).

Provider batch-API submission for the V2 worker's **first turn**, at the ~50%-discounted batch
rate. The model: bulk-submit each task's first turn (system + user + tool schemas) as a batch; when
the provider returns the first assistant turn, dispatch its tool calls locally and continue the task
**realtime** (the normal `WorkerAgent` loop). Batch is a pure cost optimisation on turn 1 — no
multi-turn tool-result history crosses the batch boundary.

## State (2026-05-30)

- **P1 (scaffold) + P2/P3a/P3b (the 3 provider clients) done.** All three `BatchClient`s are
  implemented + unit-tested on their JSON shaping.
- **INERT.** `BatchCoordinator.submit` still throws, nothing calls it, and `harness.v2.batch_enabled`
  (the gating flag) is OFF — the production path is byte-identical. The clients are registered so
  `BatchClientRouter` discovers them, but P4 (coordinator/poll/resume wiring into `RunOrchestrator`'s
  BATCH `executionMode` + the human submit-gate) is **not** done.
- **Verification boundary:** the pure JSON shaping (request envelope, status→state map, results
  parse) is unit-tested; the HTTP calls are **reasoned-not-smoked** (no provider reach in CI/sandbox)
  — a live smoke per provider is required before any flag flip. Gemini's batch envelope carries
  **elevated uncertainty** (least-stable provider surface — see its class javadoc).

## Files

| File | Purpose |
|------|---------|
| `BatchClient.java` | Provider-agnostic contract: `submit(runId, List<BatchRequest>) → BatchJob`, `poll(job) → V2BatchJobState`, `fetchResults(job) → List<BatchTaskResult>`, `cancel(job)`, `provider()`. |
| `AnthropicBatchClient.java` | **P2.** Anthropic Message-Batches (inline). submit/poll/fetch/cancel; first-turn serialization (system→top-level `system`, user/assistant→messages, tools→`input_schema`); content-block→`AssistantMessage` (text + tool_use). 5 unit tests. |
| `OpenAiBatchClient.java` | **P3a.** OpenAI Batch API (**file-based**): JSONL upload → create batch → output-file JSONL parse. Per-line body mirrors `OpenAiNativeToolClient` (role/content + `type:function` tools). 4 unit tests. |
| `GeminiBatchClient.java` | **P3b.** Gemini inline `:batchGenerateContent` → long-running Operation. Content serialization mirrors `GeminiNativeToolClient` (contents/systemInstruction/functionDeclarations). 4 unit tests. **⚠ batch envelope/endpoint unverified** — confirm against live docs (may need File-API/GCS input + schema-sanitisation parity) before use. |
| `BatchClientRouter.java` | Discovers `BatchClient` beans by `provider()`; `forProvider` throws if none registered. |
| `BatchCoordinator.java` | Entry point (`submit(runId)` — **throws until P4**). `enabled()` = `harness.v2.batch_enabled` flag (OFF). |
| `BatchPollService.java` | `@Scheduled` 30s poll of in-flight jobs (`@SchedulerLock`); no-op until P4. |
| `BatchResumeService.java` | Parses terminal results → local tool dispatch + realtime resume (throws until P4). |
| `BatchRequest.java` · `BatchJob.java` · `BatchTaskResult.java` | Records. `BatchRequest.taskId` is the out-of-order correlation key (custom_id / metadata key). `BatchJob.V2BatchJobState` = SUBMITTED / IN_PROGRESS / COMPLETED / FAILED / CANCELLED. |

## Adding a provider (P3 reference)

Mirror `AnthropicBatchClient`: `@Component implements BatchClient`, constructor takes `ObjectMapper`
+ `ApiKeyResolver` + `PipelineRunRepository` + `@Value` base-url/system-key; resolve the BYOK key via
`runId → PipelineRun.createdByUserId → ApiKeyResolver.resolve(userId)` (system-key fallback); keep the
JSON shaping package-private + unit-tested.

## Patterns
- All 3 clients are BYOK-aware (per-run owner key, system-key fallback) + inert until P4.
- `harness.v2.batch_enabled` gates the whole path (flag, `FeatureFlagService`).
- Actual batch **submission must stay behind an explicit human confirm** when P4 wires the coordinator
  (product requirement — do NOT auto-submit on plan approval).

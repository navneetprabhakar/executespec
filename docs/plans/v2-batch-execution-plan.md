# V2 batch execution — plan & handoff

> **Status:** P1 scaffold + **P2 (Anthropic client) DONE**; P3a/P3b + wiring DEFERRED.
> Originated from audit item **B1**. The old `docs/audit` source was removed after consolidation;
> this is now the doc the `batch/` package javadoc references (§1/§2/§3 below).
> **Why batch:** provider batch APIs price input/output at **~50% of realtime** — the single
> biggest LLM-bill lever. Trade-off: results are asynchronous (minutes–24h), so batch only fits the
> *first* worker turn, after which we resume realtime.

## The model

Batch-submit each task's **first** worker turn (system + user + tool schemas) at the discounted
rate; when the provider returns the first assistant turn, dispatch its tool calls locally and
continue the task **realtime** (the existing `WorkerAgent` loop). So:

- Serialization only needs the **first-turn** shape — system + user + tools. No multi-turn
  tool-result history crosses the batch boundary.
- The realtime resume reuses the normal worker path; batch is purely a cost optimisation on turn 1.

## Phases

| Phase | Scope | Status |
| --- | --- | --- |
| **P1** | Contract + scaffold: `BatchClient`, `BatchClientRouter`, `BatchCoordinator`, `BatchPollService`, `BatchResumeService`, `BatchRequest`/`BatchJob`/`BatchTaskResult` records. | **done** (interface-only) |
| **P2** | **Anthropic client — [`AnthropicBatchClient`](../../src/main/java/com/navneet/executespec/batch/AnthropicBatchClient.java).** Message-Batches submit/poll/fetch/cancel; first-turn serialization; BYOK key via runId→owner. **5 unit tests** on the JSON shaping. | **done (B1, 2026-05-30)** — HTTP reasoned-not-smoked; needs a live smoke before flip. |
| **P3a** | **OpenAI client — [`OpenAiBatchClient`](../../src/main/java/com/navneet/executespec/batch/OpenAiBatchClient.java).** File-based: JSONL upload → create batch → output file. 4 unit tests. | **done (B1, 2026-05-30)** — HTTP reasoned-not-smoked. |
| **P3b** | **Gemini client — [`GeminiBatchClient`](../../src/main/java/com/navneet/executespec/batch/GeminiBatchClient.java).** Inline `:batchGenerateContent`→Operation; 4 unit tests. **⚠ elevated uncertainty** — the batch envelope/endpoint is the least-stable surface (see class javadoc); confirm against live docs + may need File-API/GCS input. | **done (B1, 2026-05-30)** — **needs shape verification + smoke.** |
| **P4** | Wire `BatchCoordinator`/`BatchPollService`/`BatchResumeService` into `RunOrchestrator` (BATCH `executionMode`) + the human submit-gate. **All 3 provider clients now exist** — this + the live smokes are the only thing between here and a flag flip. | **deferred** |

## §1 — `BatchClient` contract & how to add a provider (P3a/P3b)

`AnthropicBatchClient` is the reference implementation — mirror its shape:

1. `@Component implements BatchClient`, `provider()` returns the enum, constructor takes
   `ObjectMapper` + `ApiKeyResolver` + `PipelineRunRepository` + `@Value` base-url/system-key.
   `BatchClientRouter` auto-discovers it (it injects `List<BatchClient>` and keys by `provider()`).
2. Keep the **pure JSON shaping package-private + unit-tested** (envelope build, status→state map,
   results parse) exactly as `AnthropicBatchClient` does — that's the verifiable surface. The HTTP
   calls stay thin.
3. BYOK key: resolve `runId → PipelineRun.createdByUserId → ApiKeyResolver.resolve(userId)`, fall
   back to the system `@Value` key.

**OpenAI Batch API shape (P3a)** — *file-based*, unlike Anthropic's inline:
- submit: `POST /v1/files` (purpose=`batch`, body = JSONL of `{custom_id, method:"POST",
  url:"/v1/chat/completions", body:{model, messages, tools, max_tokens}}`) → `POST /v1/batches`
  `{input_file_id, endpoint:"/v1/chat/completions", completion_window:"24h"}` → batch id.
- poll: `GET /v1/batches/{id}` → `status` ∈ validating/in_progress/finalizing/completed/failed/
  expired/cancelling/cancelled. Map completed→COMPLETED, failed/expired→FAILED, cancelled→CANCELLED,
  else IN_PROGRESS.
- fetch: download `output_file_id` (JSONL of `{custom_id, response:{body:{choices[0].message,
  usage}}}`).
- cancel: `POST /v1/batches/{id}/cancel`.
- Reuse `OpenAiNativeToolClient`'s message/tool serialization for the per-line `body`.

**Gemini batch shape (P3b)** — AI Studio / Vertex batch mode (inline requests or a GCS-backed
file). Heavier (often GCS staging); lowest priority. Reuse `GeminiNativeToolClient`'s
schema-sanitisation (`sanitizeSchemaForGemini`) for tools.

## §2 — `BatchRequest` (serialization input)

`BatchRequest(taskId, model, List<Message>, List<ToolCallback> tools, temperature, maxTokens)`.
`taskId` is the **correlation key** — batch APIs return out-of-order; match results back by it
(Anthropic `custom_id`, OpenAI `custom_id`). See `AnthropicBatchClient.buildBatchBody` for the
canonical mapping (SystemMessage → top-level `system`; USER/ASSISTANT → `messages`; TOOL skipped;
tools → name/description/input_schema).

## §3 — `BatchJob` (in-flight state)

`BatchJob(runId, provider, providerJobId, state, submittedAt, lastPolledAt)` with
`V2BatchJobState{SUBMITTED, IN_PROGRESS, COMPLETED, FAILED, CANCELLED}`. Persist `providerJobId`
onto `PipelineRun.batchProviderJobId` + the `batch*` columns (V61) for crash recovery — the
`BatchPollService` 30s cron (`@SchedulerLock`) re-reads in-flight jobs after a restart.

## P4 — wiring + the human gate (the part that needs the confirm)

`BatchCoordinator.submit(runId)` throws today. To wire:
1. In `RunOrchestrator`, when `executionMode=BATCH` AND `harness.v2.batch_enabled` AND
   `router.hasClient(provider)`, build `BatchRequest`s from the plan's tasks → `client.submit`.
2. **Human gate (product requirement):** actual submission must be behind an explicit confirm — do
   NOT auto-submit a batch on plan approval. Surface a "Submit as batch (saves ~50%, async)"
   confirm; only on confirm does `submit` fire. (This is the user's standing condition for B1.)
3. `BatchPollService` → on COMPLETED, `client.fetchResults` → `BatchResumeService.resume(runId,
   results)` → `SequentialExecutor.continueFromBatch` / `WorkerAgent.resumeFromBatch` (both still
   to be written — they take the first-turn `AssistantMessage` and continue the realtime loop).
4. Cost: record the batch call through `RunCostTracker` at the discounted rate (add a batch
   price tier to `application.yml model-matrix.model-pricing`).

## Verification before any flip
- Live smoke each provider client against the real batch API (submit a 1-task batch, poll to
  ended, fetch, assert the parsed `BatchTaskResult`). This is the step the sandbox **cannot** do.
- Keep `harness.v2.batch_enabled` OFF until P4 + smokes are green.

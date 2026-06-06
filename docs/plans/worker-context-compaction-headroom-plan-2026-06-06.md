# Worker context compaction plan

> Created: 2026-06-06. Status: proposal only, pending founder confirmation.
> Scope: reduce ExecuteSpec worker token usage using the useful Headroom patterns without adding a
> proxy dependency. Do not implement code or migrations from this document until explicitly approved.

## 1. Problem

The V2 worker sends its accumulated conversation history to the model on every iteration. This is the
right shape for continuity, but it makes token usage grow throughout a task:

- `WorkerAgent` keeps full history and passes it to `LlmRouter` each turn.
- `readFile` can return up to 60,000 chars or 2,000 lines for full reads.
- `shell`, `buildProject`, and test tools budget large logs inline, then keep those previews in
  history forever.
- Existing compaction hooks exist, but they are split across two default-OFF flags:
  `worker.tool-compactor.enabled` and `worker.history-compaction.enabled`.
- The current `ToolOutputCompactor` is LLM-based, so it can save future tokens but also spends tokens
  immediately. In this checkout, the LIGHTWEIGHT tier maps to flagship model ids for Anthropic and
  OpenAI, so LLM compaction is not automatically cheap.

The result is high repeated input-token spend, late-iteration latency, and higher risk of context
window pressure on MEDIUM/HIGH tasks.

## 2. Goals

- Enable worker compaction by default through one common flag: `worker.compaction.enabled=true`.
- Preserve full backward compatibility when that common flag is OFF.
- Reduce worker input tokens without making edit correctness worse.
- Prefer deterministic, provider-free reducers before using LLM summarization.
- Make compression reversible through a retrieval tool so the worker can recover original content.
- Keep provider prompt-cache stability: no system prompt rewrites, no per-turn tool-list toggling.
- Produce measurable token, cost, latency, and verifiedScore deltas before tuning cost estimates.

## 3. Non-goals

- Do not put Headroom's proxy in front of `LlmRouter`.
- Do not compress system prompts, developer instructions, the user task/spec, fresh file reads, or the
  latest tool result.
- Do not use ML/Kompress-style text compression as the default implementation.
- Do not change planner, reviewer, scoring, billing, or PR behavior in the same rollout.
- Do not immediately lower credit/cost estimates. Recalibrate only after production/eval telemetry.

## 4. Headroom findings to port

Headroom's useful ideas are architectural, not a drop-in dependency:

- Safe units only: Headroom extracts provider-neutral `CompressionUnit`s and compresses only mutable
  live-zone content. User/system/developer/cache-hot content is protected.
- Deterministic reducers first: logs, search results, and diffs are parsed and reduced with rules
  rather than summarized by another LLM.
- Reversible CCR: original content is stored under a hash; compressed output includes a retrieval
  marker. The model can call a retrieval tool when it needs detail.
- Stable retrieval tool: once CCR is possible, the retrieval tool should remain in the tool list so
  provider prompt-cache bytes do not flip on and off.
- Fail open: any compaction/store/retrieval problem must fall back to raw output or a typed retrieval
  miss, never fail the worker.

## 5. Proposed design

### 5.1 Common flag

Add a new global feature flag:

```text
worker.compaction.enabled = true
```

Rules:

- If `worker.compaction.enabled=false`, all new behavior is disabled:
  - no deterministic reducers
  - no compact store writes
  - no `retrieveContext` tool binding
  - no read lifecycle compaction
  - no history folding
  - raw tool outputs and full history remain unchanged
- Existing flags become deprecated implementation details:
  - `worker.tool-compactor.enabled`
  - `worker.history-compaction.enabled`
- The new common flag is the rollback switch operators use.
- Use the next free Flyway version when implementing. Do not assume `V230`, because this workspace
  already has pending migration work.

### 5.2 WorkerCompactionPolicy

Create a small policy service to centralize all compaction decisions:

```text
WorkerCompactionPolicy
  enabled(ctx)
  shouldBindRetrieveTool(ctx)
  shouldCompactToolOutput(toolName, rawOutput, isLatestToolResult)
  shouldRunReadLifecycle(history)
  shouldRunHistoryCompaction(lastInputTokens, history)
```

This avoids scattering feature-flag checks across `WorkerAgent`, `ToolOutputCompactor`, and
`WorkerHistoryCompactor`.

### 5.3 Compact context store

Add a per-run/task store for original content:

```text
key: v2:compact:{runId}:{taskId}:{hash}
ttl: 24h
hash: first 24 lowercase hex chars of SHA-256(original)
value:
  original
  compressed
  toolName
  strategy
  createdAt
  originalBytes
  compressedBytes
```

Preferred backend: Redis, matching worker context snapshots. Database persistence is unnecessary for
worker-loop recovery and would create cleanup work.

### 5.4 Retrieval tool

Add a read-only worker tool:

```text
retrieveContext(hash, query?, maxChars?)
```

Behavior:

- Validates `hash` as exactly 24 lowercase hex chars.
- Looks up only under the current `runId/taskId`.
- If `query` is absent, returns a bounded full preview plus the stored metadata.
- If `query` is present, searches line-aware chunks and returns the best bounded matches.
- Defaults `maxChars` to a safe value, for example 8,000, with a hard cap.
- Never shells out, never reads arbitrary files, never crosses run/task scope.

Binding rule:

- When `worker.compaction.enabled=true`, bind `retrieveContext` on every worker request.
- When the flag is OFF, do not bind it. This preserves byte compatibility for rollback.

### 5.5 Tool output reducer router

Replace the current LLM-first tool compaction path with:

```text
Tool output -> deterministic reducer -> token/char shrink gate -> compact store -> marker output
                         |
                         v
              optional LLM compactor only for old generic text
```

Acceptance gate:

- Return the compressed output only if it is smaller by chars and estimated tokens.
- Store the original only after the compressed output passes the shrink gate.
- If no reducer applies, return raw output.

Marker format:

```text
[compressed {originalShape} to {compressedShape}; retrieveContext hash={hash}]
```

The marker should be terse, stable, and easy for the model to copy into the retrieval tool.

## 6. Reducers

### 6.1 Build/test log reducer

Targets: `buildProject`, `shell`, `runTests`, `runTests` in patch mode, and workspace test tools.

Keep:

- first failure/error block
- last failure/error block
- stack traces with a small context window
- test summary lines
- compiler diagnostics with file/line references
- a small warning sample with dedupe
- command exit code and persisted full-output path when present

Drop:

- repeated download/install progress
- repeated passing test lines
- duplicate warnings
- long dependency logs with no error signal

Initial defaults:

```text
maxErrors = 10
errorContextLines = 3
maxStackTraces = 3
stackTraceMaxLines = 20
maxWarnings = 5
maxTotalLines = 120
minInputLines = 50
```

### 6.2 Search result reducer

Targets: old/large `searchFiles` output.

Keep:

- total match count and pagination note
- first and last match per important file
- file groups with strongest relevance to the task/search query
- file/line/content format exactly enough to call `readFile`

Initial defaults:

```text
maxFiles = 15
maxMatchesPerFile = 5
maxTotalMatches = 40
minMatchesForStore = 10
```

Note: `searchFiles` is already paged to 50 by default and 200 max, so the largest win is old-history
replacement, not single-call output shrink.

### 6.3 Diff reducer

Targets: old/non-latest `gitDiff` output only.

Keep:

- file headers
- all `+` and `-` lines
- up to 2 nearby context lines
- first/last/high-change hunks when there are too many
- summary of dropped files/hunks

Do not compress the latest diff the model just requested. It is often the immediate verification
signal.

### 6.4 List-files reducer

Targets: large `listFiles` outputs.

Keep:

- root directories
- source/test/config files
- nearby files relevant to the task
- omitted counts per directory

Drop:

- dependency/build/vendor/generated directories already ignored elsewhere
- repeated deep tree detail when a directory summary is enough

### 6.5 Read-file lifecycle reducer

Targets: old `readFile` results only when provably safe.

States:

- Fresh: latest read for a file and no later edit. Keep verbatim.
- Stale: file was edited after this read. Replace with marker.
- Superseded: file was re-read later with a range that covers the earlier read. Replace with marker.

Default:

- Compress stale reads.
- Keep superseded compression OFF at first unless eval shows it is safe with prompt caching.

Rationale:

- Fresh reads are exact edit anchors and must not be compressed.
- Stale reads are factually wrong after an edit, so keeping them verbatim can hurt the worker.

## 7. History compaction

Keep the current high-level shape, but make it subordinate to the common flag and run it after
deterministic reducers:

- Trigger after previous prompt tokens exceed 60,000.
- Keep system prompt and task text.
- Keep the most recent 8 messages verbatim.
- Align cuts to assistant/tool boundaries so no tool call/result pair is split.
- Summarize only older middle turns.

Improvement over the current implementation:

- Do not run history LLM summarization on raw bulky logs if deterministic reducers have not had a
  chance to shrink them first.
- Include compact-store markers in the summary when older raw detail has been replaced.

## 8. Cache and provider discipline

- Do not rewrite the worker system prompt per turn.
- Do not append dynamic retrieval instructions into the system prompt.
- Keep tool schemas stable while compaction is ON.
- Do not mutate provider cache-control blocks or provider-specific thought/signature metadata.
- Keep compaction inside the run provider boundary if an LLM compactor is used.
- Prefer deterministic reducers to avoid cache misses caused by stochastic summaries.

## 9. Observability

Add metrics and logs before broad rollout:

```text
executespec.worker.compaction.attempts
executespec.worker.compaction.applied
executespec.worker.compaction.skipped
executespec.worker.compaction.tokens_before
executespec.worker.compaction.tokens_after
executespec.worker.compaction.bytes_before
executespec.worker.compaction.bytes_after
executespec.worker.compaction.retrieve.calls
executespec.worker.compaction.retrieve.misses
executespec.worker.compaction.fail_open
```

Log fields:

- runId
- taskId
- provider
- model
- toolName
- strategy
- hash
- bytesBefore
- bytesAfter
- estimatedTokensBefore
- estimatedTokensAfter
- skipReason

Do not log full original content. Retrieval logs should redact token/secret-shaped values.

## 10. Tests

Unit tests:

- `WorkerCompactionPolicyTest`
- `CompactContextStoreTest`
- `RetrieveContextToolTest`
- `BuildLogReducerTest`
- `SearchResultReducerTest`
- `DiffReducerTest`
- `ReadLifecycleCompactorTest`
- `WorkerHistoryCompactorCommonFlagTest`

Required compatibility tests:

- With `worker.compaction.enabled=false`, worker tool list is unchanged.
- With `worker.compaction.enabled=false`, `maybeCompact` returns raw content.
- With `worker.compaction.enabled=false`, history compactor returns the same list reference.
- Fresh `readFile` output is never compacted.
- Latest tool result is never compacted.
- `writeFile`, `editFile`, `completeTask`, and denied git tools are never compacted.

Integration/eval tests:

- One Java build failure loop.
- One test failure loop.
- One broad search task.
- One large file read/edit task.
- One stale-read-after-edit task.
- SWE-bench subset or existing engine eval with verifiedScore comparison.

## 11. Rollout phases

### Phase 0: baseline and fixtures

- Capture current token usage on a small eval set:
  - simple run
  - MEDIUM run
  - HIGH run
  - build-failure run
  - large-read run
- Save representative raw outputs as reducer fixtures.
- No behavior change.

### Phase 1: common flag and policy

- Add `worker.compaction.enabled=true`.
- Add `WorkerCompactionPolicy`.
- Rewire existing compactors to consult the common flag.
- Keep deterministic reducers disabled until Phase 3.
- Add OFF compatibility tests.

### Phase 2: compact store and retrieval tool

- Add Redis-backed compact store.
- Add `retrieveContext`.
- Bind `retrieveContext` whenever common flag is ON.
- Add retrieval tool tests and scoped hash validation.

### Phase 3: deterministic tool-output reducers

- Add reducer interface and router.
- Implement build/test log reducer.
- Implement search reducer.
- Implement list-files reducer.
- Implement old diff reducer.
- Store originals and emit retrieval markers.
- Keep LLM tool-output summarization as a fallback only, not the first path.

### Phase 4: read lifecycle compaction

- Add stale-read replacement using the existing read/edit state.
- Keep fresh reads verbatim.
- Keep superseded-read replacement OFF until eval.
- Add tests around edit correctness and read-before-write behavior.

### Phase 5: history compaction cleanup

- Move `WorkerHistoryCompactor` behind common policy.
- Ensure deterministic reducers run before history summarization.
- Keep the 60k trigger initially.
- Add metrics for before/after prompt tokens.

### Phase 6: eval and default confirmation

- Run eval with common flag ON and OFF.
- Compare:
  - verifiedScore
  - terminal outcome
  - prompt tokens
  - cache-read tokens
  - wall-clock
  - retrieval rate
  - fail-open rate
- Only after data, recalibrate `RunCostTracker` estimate bands.

## 12. Acceptance criteria

- OFF mode is byte-compatible for worker requests and tool outputs.
- ON mode reduces worker input tokens by at least 25 percent on MEDIUM/HIGH debug-heavy evals.
- ON mode does not lower verifiedScore on the selected eval set.
- Retrieval miss rate is near zero for hashes emitted in the same run/task.
- No fresh read is compacted.
- No latest tool result is compacted.
- No compaction failure can fail a run.
- Metrics show per-strategy savings and skip reasons.

## 13. Risks and mitigations

| Risk | Mitigation |
| --- | --- |
| Lost detail hurts coding accuracy | Store originals and expose `retrieveContext`; preserve latest outputs verbatim. |
| `editFile` cannot match old strings | Never compact fresh `readFile`; only lifecycle-compact stale/superseded reads. |
| Prompt cache instability | Stable retrieval tool binding under common flag; no system prompt rewriting. |
| LLM compaction costs more than it saves | Deterministic reducers first; LLM fallback only for old generic text. |
| Security leak through retrieval | Scope by run/task, validate hashes, cap output, redact logs. |
| Default ON causes production surprise | One common rollback flag; OFF compatibility tests; phased eval before merge. |

## 14. Open decisions

- Exact retrieval tool name: proposed `retrieveContext`.
- Whether superseded reads should be enabled after stale reads prove safe.
- Whether old `worker.tool-compactor.enabled` and `worker.history-compaction.enabled` remain as hidden
  sub-gates for one release, or become deprecated no-ops immediately.
- The exact eval set and minimum run count before recalibrating cost estimates.

## 15. Confirmation gate

This document is the proposed plan. Implementation should wait for explicit confirmation.

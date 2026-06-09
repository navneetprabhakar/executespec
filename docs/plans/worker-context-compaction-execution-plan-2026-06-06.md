# Worker context compaction — execution plan

> ## ⛔ SUPERSEDED (2026-06-07)
> This standalone plan has been **inlined verbatim as Appendix A** of the single source of truth:
> [`vix-analysis-and-leverage-plan-2026-06-07.md`](vix-analysis-and-leverage-plan-2026-06-07.md)
> (workstream **W2.1**). The canonical copy there has the corrected migration number (**V232**, since
> V231 shipped as `worker.critic-pass.enabled`). **Edit the canonical doc, not this one.** Retained
> only for git history; do not consult or update.

> Created: 2026-06-06. Status: **proposal only, pending founder confirmation.** Do NOT implement code
> or migrations from this document until explicitly approved.
>
> Builds on and supersedes the design in
> [`worker-context-compaction-headroom-plan-2026-06-06.md`](worker-context-compaction-headroom-plan-2026-06-06.md).
> That doc is the *why*; this is the *how* — grounded in the actual code seams, with the five review
> concerns folded in and the reducer priorities corrected against what the tools actually emit.

---

## 0. What changed from the proposal (decisions locked in)

| # | Proposal said | This plan does | Why (verified) |
|---|---------------|----------------|----------------|
| D1 | `worker.compaction.enabled = true` (default ON) | **Seed default OFF** (V231). Flip to ON only via a follow-up migration after Phase 6 eval clears the acceptance bar. | Every sibling worker flag (V216/217/219/220/221/222) is seeded `false` pending eval; default-ON at introduction would turn on un-validated, flagship-priced LLM compaction before the safe reducers exist. |
| D2 | §6.5 "use the existing read/edit state" | **Read-lifecycle is a net-new history-correlation pass** (explicit algorithm in §6). Shipped LAST (Phase 4), stale-only, superseded OFF. | `ReadFileStateStore` keeps only the *latest* SHA per file ([ReadFileStateStore.java:57-65,97-100](../../src/main/java/com/navneet/executespec/tool/ReadFileStateStore.java#L57)) — no per-message timeline, no "superseded" notion. The edit gate is disk-vs-ledger ([EditFileTool.java:141-159](../../src/main/java/com/navneet/executespec/tool/EditFileTool.java#L141)), independent of history. |
| D3 | `retrieveContext` × remote-tool mode unaddressed | **Do not bind `retrieveContext` on remote runs.** | The worker's remote branch returns *before* `maybeCompact` ([WorkerAgent.java:1393-1395](../../src/main/java/com/navneet/executespec/engine/WorkerAgent.java#L1393)) — so no compact-store entries exist on remote runs; a bound retrieval tool would be wrongly proxied to the Harbor client and always miss. |
| D4 | `retrieveContext` × read-before-write gate unspecified | Retrieved content is **explicitly not an edit anchor**; the tool output carries a "re-read before editing" note. | Edit gate validates disk-vs-ledger, so a retrieved (possibly stale) blob can't satisfy `editFile`; STALE forces a re-read regardless ([EditFileTool.java:151-159](../../src/main/java/com/navneet/executespec/tool/EditFileTool.java#L151)). |
| D5 | New `Tool` bean silently widens the default tool list | `bindForWorker` **filters** `retrieveContext` when the master flag is OFF or the run is remote; pinned by an OFF-mode byte-compat test. | `@Component implements Tool` is auto-collected into `allTools` and bound on every run ([ToolRegistry.java:96-104](../../src/main/java/com/navneet/executespec/tool/ToolRegistry.java#L96)). |
| D6 | "Build/test log reducer" is the lead reducer (§6.1) | **Reprioritized.** `runTests` is *excluded* (gate-critical + already structured-small); `buildProject` reduces only its `output:` body and preserves the header verbatim; the high-value/safe single-call targets are `shell` / `searchFiles` / `listFiles` / old `gitDiff`. History compaction + read-lifecycle are the dominant token levers. | Every tool output except `readFile` (60k) is already budget-capped: `shell`/`buildProject` at 6k via `ToolOutput.budget` ([ToolOutput.java:28](../../src/main/java/com/navneet/executespec/tool/ToolOutput.java#L28)), `listFiles` at 500 entries, `searchFiles` paged 50/200, `runTests` a small JSON envelope. The completeTask gates inspect `buildProject`/`runTests` envelopes by exact substring (§3). |
| D7 | LLM `ToolOutputCompactor` is the tool-output path | **Demote it to an opt-in fallback** behind its existing sub-flag (default OFF). Default-ON behavior is deterministic-only (zero added LLM tokens). | LIGHTWEIGHT tier resolves to flagship `claude-sonnet-4-6` / `gpt-5.4` in this checkout ([application.yml:86,94](../../src/main/resources/application.yml#L86) via [ModelResolver.java:60-70](../../src/main/java/com/navneet/executespec/router/ModelResolver.java#L60)) — LLM compaction is not cheap here. (Known temporary tier state, §11.) |

---

## 1. Verified integration map (the seams this plan touches)

All line numbers are against the current worktree (migration head **V230**, so the new flag is **V231**).

| Seam | File:line | Role in this plan |
|------|-----------|-------------------|
| Worker loop, full history kept | [`engine/WorkerAgent.java`](../../src/main/java/com/navneet/executespec/engine/WorkerAgent.java) | History list: `[0]=SystemMessage`, `[1]=UserMessage(task)`, then `Assistant(toolCalls)`→`ToolResponse` pairs. |
| Tool-output compaction hook | `WorkerAgent.java:1413` (`maybeCompact` at :1435) | Wraps `ToolResult.Ok.content()` before `okEnvelope`. **The single insertion point for the reducer router.** |
| History-compaction hook | `WorkerAgent.java:489-493` | Top-of-loop, before building the request, gated by `lastInputTokens`. **The insertion point for read-lifecycle + history fold.** |
| Latest-result protection | `WorkerAgent.java:643-646` | `isImmediatelyPriorToolCall = (callIdx == lastIdx)` — already passed into `maybeCompact`. |
| **Build gate marker** | `WorkerAgent.java:801-806` | `envelope.contains("\\nexit=0\\n")` → `lastBuildPassIter`. **Reducer MUST preserve `\nexit=0\n`.** |
| **Test gate marker** | `WorkerAgent.java:788-795` | needs `\"failed\":[]` + `\"errored\":[]` + `\"exit_code\":0`. **`runTests` excluded from reducers.** |
| Remote-mode early return | `WorkerAgent.java:1393-1395` | Returns before `maybeCompact`; reducers naturally skipped on TB runs. |
| Worker tool binding | [`tool/ToolRegistry.java:96-104`](../../src/main/java/com/navneet/executespec/tool/ToolRegistry.java#L96) (`bindForWorker`) + `:114` (`isRemoteRun`) | Gate `retrieveContext` binding here. |
| Redis store template | [`context/ContextStore.java`](../../src/main/java/com/navneet/executespec/context/ContextStore.java) (`StringRedisTemplate` optional, `keyFor`, `deleteAll` at :150) | `CompactContextStore` is modeled on this 1:1. |
| Run-end Redis cleanup | [`harness/SequentialExecutor.java:374-375`](../../src/main/java/com/navneet/executespec/harness/SequentialExecutor.java#L374) (`contextStore.deleteAll(runId)`) | Add `compactContextStore.deleteAll(runId)` beside it. |
| Flag service | [`config/featureflag/FeatureFlagService.java:44`](../../src/main/java/com/navneet/executespec/config/featureflag/FeatureFlagService.java#L44) (`isEnabled`) | In-memory cached → cheap per-iteration. |
| Edit gate (read-before-write) | [`tool/EditFileTool.java:141-159`](../../src/main/java/com/navneet/executespec/tool/EditFileTool.java#L141) | Disk-vs-ledger; read-lifecycle must never compact a *fresh* read. |
| readFile caps + truncation | [`tool/ReadFileTool.java:52-55`](../../src/main/java/com/navneet/executespec/tool/ReadFileTool.java#L52) | 60k chars / 2000 lines, head-first; the only unbounded single output. |

`ExecutionContext` is `(runId, taskId, workspaceRoot)` — **no provider** ([ExecutionContext.java:18](../../src/main/java/com/navneet/executespec/tool/ExecutionContext.java#L18)). So `retrieveContext.execute(args, ctx)` has full run/task scope; the *policy* threads `provider` from `WorkerTaskRequest.provider()` exactly as the existing compactors do.

---

## 2. Target architecture (one master flag, deterministic-first, reversible)

```
                       worker.compaction.enabled  (V231, default OFF — operator rollback switch)
                                     │
        ┌────────────────────────────┼───────────────────────────────┐
        │                            │                                │
  emit-time (per tool output)   pre-call (per iteration)        tool binding
  WorkerAgent.maybeCompact      WorkerAgent top-of-loop         ToolRegistry.bindForWorker
        │                            │                                │
  ToolOutputReducerRouter      ReadLifecycleCompactor (Phase 4)   bind retrieveContext
   ├─ deterministic reducer       + WorkerHistoryCompactor        iff flag ON && !remote
   ├─ shrink gate                  (run AFTER reducers — already
   ├─ CompactContextStore.put       satisfied: reducers ran at
   └─ marker output                 emit-time)
        │
   (opt-in LLM fallback behind worker.tool-compactor.enabled, default OFF)

           retrieveContext(hash[,query,maxChars])  ──reads──▶  CompactContextStore (Redis, 24h)
```

**Staging sub-gates** (temporary, default OFF, folded into the master after their phase clears eval —
not permanent config surface): `worker.tool-compactor.enabled` (LLM fallback, existing V216),
`worker.history-compaction.enabled` (history fold, existing V222). This resolves the proposal's §14
open decision: keep the two legacy flags as hidden sub-gates for one release, then make them no-ops.
**No new sub-flag migrations** — V231 is the only new migration.

---

## 3. Hard invariants (every reducer/compactor MUST hold these; each gets a pinning test)

1. **Master-OFF byte-compatibility.** Flag OFF ⇒ worker tool list unchanged, `maybeCompact` returns
   raw, history compactor returns the same list reference, no Redis writes. (Pinned: §7 OFF tests.)
2. **Gate-marker preservation.** A reduced `buildProject` envelope still contains `\nexit=0\n` when the
   build passed; `runTests` is never reduced. A reducer that can't guarantee this returns raw.
3. **Fresh-read protection.** A `readFile` result that is the latest read of its file with no later
   edit is **never** compacted (it is the model's exact `editFile` anchor).
4. **Latest-result protection.** The most-recent tool result in a batch (`isImmediatelyPriorToolCall`)
   is never reduced (already enforced at the hook).
5. **Skip set.** `writeFile`, `editFile`, `gitCommit`, `completeTask`, `gitStatus`, `runTests` are
   never reduced (short/actionable/gate-critical). `gitDiff`: only old/non-latest.
6. **Shrink gate.** Emit the marker only if it is strictly smaller than the raw by chars **and** by a
   char-based token estimate; store the original only after it passes. Otherwise return raw.
7. **Fail-open.** Any reducer/store/retrieval error ⇒ raw output (or a typed retrieval miss). No
   compaction path can fail a run. (Mirrors `ContextStore` + `ReadFileStateStore` fail-open policy.)
8. **Remote carve-out.** No reducer runs and `retrieveContext` is not bound on remote (Terminal-Bench)
   runs.
9. **Cache discipline.** No per-turn system-prompt rewrite; `retrieveContext` schema is byte-stable
   while bound; deterministic reducers produce identical output for identical input (no stochastic
   cache-busting).

---

## 4. New components (skeletons)

New package: `tool/compact/` (the two existing compactors stay in `tool/`; auto-discovery is
package-agnostic). All token counts use a shared char-based estimator (`chars / 4`, no LLM call).

### 4.1 `WorkerCompactionPolicy` — all decisions in one place (no I/O)

```java
@Service @RequiredArgsConstructor
public class WorkerCompactionPolicy {
  public static final String FLAG_MASTER = "worker.compaction.enabled";          // V231, default OFF
  static final String FLAG_LLM_FALLBACK  = ToolOutputCompactor.FLAG_ENABLED;      // sub-gate, OFF
  static final String FLAG_HISTORY       = WorkerHistoryCompactor.FLAG_ENABLED;   // sub-gate, OFF
  static final int    MIN_REDUCE_BYTES   = 2048;  // below this, not worth a marker+store round-trip

  private final FeatureFlagService flags;

  public boolean enabled() { return flags.isEnabled(FLAG_MASTER); }

  /** retrieveContext is bound iff master ON and the run is not remote (D3/D5). */
  public boolean shouldBindRetrieveTool(boolean isRemoteRun) { return enabled() && !isRemoteRun; }

  /** Gate for deterministic tool-output reduction at emit time. */
  public boolean shouldReduceToolOutput(String toolName, String raw, boolean isLatest) {
    if (!enabled() || isLatest || raw == null || raw.length() < MIN_REDUCE_BYTES) return false;
    return !NEVER_REDUCE.contains(toolName);   // see invariant #5
  }

  /** LLM fallback only when master ON AND the legacy sub-gate is explicitly ON. */
  public boolean shouldRunLlmFallback() { return enabled() && flags.isEnabled(FLAG_LLM_FALLBACK); }

  /** History fold only when master ON AND the legacy sub-gate is explicitly ON. */
  public boolean shouldRunHistoryCompaction() { return enabled() && flags.isEnabled(FLAG_HISTORY); }

  /** Read-lifecycle ships in Phase 4; constant gate until eval clears it. */
  public boolean shouldRunReadLifecycle() { return enabled() && READ_LIFECYCLE_ON; }

  static final Set<String> NEVER_REDUCE =
      Set.of("writeFile","editFile","gitCommit","completeTask","gitStatus","runTests");
  static final boolean READ_LIFECYCLE_ON = false; // flip in Phase 4 after eval
}
```

### 4.2 `CompactContextStore` — Redis original-content store (modeled 1:1 on `ContextStore`)

```java
@Component @Slf4j @RequiredArgsConstructor
public class CompactContextStore {
  public static final String KEY_PREFIX = "v2:compact:";          // v2:compact:{runId}:{taskId}:{hash}
  public static final Duration TTL = Duration.ofHours(24);
  @Autowired(required = false) private StringRedisTemplate redis;  // optional → tests don't crash
  private final ObjectMapper objectMapper;

  /** @return 24-lowercase-hex hash, or empty on any failure (fail-open). */
  public Optional<String> put(ExecutionContext ctx, String toolName, String strategy,
                              String original, String compressed) {
    if (redis == null || original == null) return Optional.empty();
    try {
      String hash = sha256hex24(original);
      ObjectNode v = objectMapper.createObjectNode();
      v.put("original", original); v.put("compressed", compressed);
      v.put("toolName", toolName); v.put("strategy", strategy);
      v.put("originalBytes", original.length()); v.put("compressedBytes", compressed.length());
      redis.opsForValue().set(key(ctx, hash), v.toString(), TTL);
      return Optional.of(hash);
    } catch (Exception e) { log.warn("CompactContextStore put failed run={}: {}", ctx.runId(), e.getMessage()); return Optional.empty(); }
  }

  public Optional<CompactEntry> get(ExecutionContext ctx, String hash) { /* scoped to runId/taskId; empty on miss/down */ }
  public long deleteAll(String runId) { /* redis.keys(KEY_PREFIX+runId+":*") + delete — mirrors ContextStore.deleteAll */ }
  private static String key(ExecutionContext c, String h){ return KEY_PREFIX+c.runId()+":"+c.taskId()+":"+h; }
}
```
Wire `compactContextStore.deleteAll(runId)` beside `contextStore.deleteAll(runId)` at
`SequentialExecutor.java:374-375` (always-on hygiene, like V221's `ContextStore.deleteAll`).

### 4.3 `RetrieveContextTool implements Tool` (read-only, scoped)

```java
@Component @RequiredArgsConstructor
public class RetrieveContextTool implements Tool {
  static final int DEFAULT_MAX_CHARS = 8000, HARD_CAP = 16000;
  private final CompactContextStore store;

  public String name() { return "retrieveContext"; }
  public String description() {
    return "Retrieve the full original content of a previously compacted tool output by its hash "
      + "(from a [compressed … retrieveContext hash=…] marker). Optional `query` searches within it. "
      + "NOTE: retrieved content is a historical snapshot — if you intend to editFile, readFile the "
      + "file again first so your edit matches current disk contents.";  // D4
  }
  public String inputSchema() { /* hash:^[0-9a-f]{24}$ (required), query:string, maxChars:int — additionalProperties:false */ }

  public ToolResult execute(JsonNode args, ExecutionContext ctx) {
    String hash = args.path("hash").asText("");
    if (!hash.matches("[0-9a-f]{24}")) return ToolResult.err(INVALID_ARGS, "hash must be 24 lowercase hex chars");
    var entry = store.get(ctx, hash);                                   // scoped to THIS run/task only
    if (entry.isEmpty()) return ToolResult.ok("(retrieval miss: no stored content for hash " + hash + " in this run/task)");
    String original = entry.get().original();
    int cap = Math.min(args.path("maxChars").asInt(DEFAULT_MAX_CHARS), HARD_CAP);
    String q = args.path("query").asText(null);
    return ToolResult.ok(q == null || q.isBlank() ? headPreview(original, cap) : bestLineMatches(original, q, cap));
  }
}
```
Never shells out, never reads disk, never crosses run/task scope (invariant #7, security row in §9). A
miss is a typed `Ok` string, not an error — the worker treats it as "detail unavailable," never a stall.

### 4.4 Reducer SPI + router

```java
public interface ToolOutputReducer {
  boolean handles(String toolName);
  /** @return reduced content (a body the router will append the marker to), or empty to pass raw. */
  Optional<String> reduce(String toolName, String rawOutput);
}

@Service @RequiredArgsConstructor
public class ToolOutputReducerRouter {
  private final WorkerCompactionPolicy policy;
  private final CompactContextStore store;
  private final List<ToolOutputReducer> reducers;   // auto-collected; first match wins

  /** Called from WorkerAgent.maybeCompact. Returns the content to envelope (reduced+marker, or raw). */
  public String maybeReduce(String toolName, String raw, boolean isLatest, ExecutionContext ctx) {
    if (!policy.shouldReduceToolOutput(toolName, raw, isLatest)) return raw;
    var reducer = reducers.stream().filter(r -> r.handles(toolName)).findFirst().orElse(null);
    if (reducer == null) return raw;
    String reduced = reducer.reduce(toolName, raw).orElse(null);
    if (reduced == null || !passesShrinkGate(raw, reduced)) return raw;   // invariant #6
    String hash = store.put(ctx, toolName, reducer.getClass().getSimpleName(), raw, reduced).orElse(null);
    if (hash == null) return raw;                                         // fail-open: store down → raw
    return reduced + "\n[compressed " + shape(raw) + "→" + shape(reduced)
         + "; retrieveContext hash=" + hash + "]";                        // stable, terse marker
  }
}
```

`WorkerAgent.maybeCompact` becomes (one new nullable field `reducerRouter`, last in ctor — null-guarded
exactly like the existing compactors):
```java
private String maybeCompact(String toolName, String content, boolean isLatest, WorkerTaskRequest req) {
  if (reducerRouter != null) {                                  // deterministic first (default-ON path)
    String reduced = reducerRouter.maybeReduce(toolName, content, isLatest, req.context());
    if (!reduced.equals(content)) return reduced;
  }
  if (toolOutputCompactor != null && policy.shouldRunLlmFallback()) {   // opt-in LLM fallback only
    return toolOutputCompactor.compactIfNeeded(toolName, content, req.userPrompt(), isLatest,
             req.context(), req.provider()).map(Compacted::summary).orElse(content);
  }
  return content;
}
```

---

## 5. The reducers (priority-ordered by value × safety)

> Reminder (D6): all single outputs except `readFile` are already budget-capped, so each reducer's
> per-call win is modest; the value **compounds** across 60–180 iterations. Token wins ranked:
> **history compaction (§6) > read-lifecycle (§6) > shell/search/list/diff reducers (this section)**.

### 5.1 `ShellLogReducer` — `shell` + `buildProject` body (HIGH value, SAFE if header-preserved)
- **`buildProject`**: split on the literal `"\noutput:\n"`. **Keep the `command=…\nsource=…\nexit=<n>\noutput:\n` header verbatim** (this is what preserves the `\nexit=0\n` gate marker — invariant #2), reduce only the body.
- **`shell`**: reduce the whole body (no gate dependency).
- Body keep-rules: first + last error/failure block, stack traces (≤3, ≤20 lines each), compiler
  diagnostics with file:line, a deduped warning sample (≤5), the persisted-full-log `readFile` path that
  `ToolOutput.budget` already appended. Drop: download/install progress, repeated passing lines, dup warnings.
- Defaults: `maxErrors=10, errorContextLines=3, maxStackTraces=3, stackTraceMaxLines=20, maxWarnings=5,
  maxBodyLines=120, minBodyLines=50`.
- **`runTests` is NOT handled here** (excluded; gate-critical + already small structured JSON).

### 5.2 `SearchResultReducer` — `searchFiles` (MEDIUM value, SAFE)
- Output is `"<total> match(es); showing a–b\n<file:line:content>…"` — keep the count/pagination header,
  then first/last match per file, file groups by relevance, `file:line:content` intact enough to `readFile`.
- Defaults: `maxFiles=15, maxMatchesPerFile=5, maxTotalMatches=40`. (Single-call is already paged 50/200,
  so the real win is replacing OLD large search outputs left in history.)

### 5.3 `ListFilesReducer` — `listFiles` (LOW-MEDIUM value, SAFE)
- Keep root dirs, source/test/config files, task-relevant neighbors, per-dir omitted counts. Drop
  vendor/build/generated subtrees and deep repeated detail. (`listFiles` is already capped at 500 entries.)

### 5.4 `DiffReducer` — old/non-latest `gitDiff` only (LOW value, SAFE)
- Keep file headers, all `+`/`-` lines, ≤2 context lines, first/last/high-change hunks, dropped-file
  summary. **Never the latest `gitDiff`** (immediate verification signal — already protected by `isLatest`).

---

## 6. The dominant levers: history compaction + read-lifecycle

### 6.1 History compaction (rewire, mostly built)
`WorkerHistoryCompactor` already triggers at 60k input tokens, keeps recent-8, cuts on an
`AssistantMessage` boundary, folds the task into the summary, fail-safe ([WorkerHistoryCompactor.java](../../src/main/java/com/navneet/executespec/tool/WorkerHistoryCompactor.java)). Changes:
- Gate via `policy.shouldRunHistoryCompaction()` (master AND the V222 sub-gate) instead of the bare flag.
- **Ordering is already correct**: per-output reducers run at *emit-time* (`dispatchOne`), so by the time
  history compaction runs at the next iteration's top, the tool outputs it summarizes are already reduced
  markers. No code reordering needed — the proposal's §7 concern is structurally satisfied by where the
  hooks sit.
- Prompt tweak: instruct the summarizer to **preserve any `retrieveContext hash=…` markers** it sees so
  retrieval still works after a fold (best-effort; markers point at 24h-TTL Redis).

### 6.2 Read-lifecycle compactor (Phase 4 — net-new, the algorithm the proposal lacked)
Runs as a separate pre-call history pass beside history compaction. **Deterministic, no LLM, no
`ReadFileStateStore` dependency** (that store only has the latest SHA; this needs the message timeline):

```
compactStaleReads(history, ctx):
  if !policy.shouldRunReadLifecycle(): return history
  recentCutoff = history.size() - KEEP_RECENT (8)        # never touch the live working set
  for each ToolResponseMessage tr at index i < recentCutoff:
     for each response r in tr where r.name == "readFile":
        path = pathOf(precedingAssistant.toolCalls matching r.id)   # parse the readFile call's `path`
        if path == null: continue
        # STALE := a later writeFile/editFile to the SAME path exists after index i
        if existsLaterEditTo(history, i, path):
            hash = compactStore.put(ctx, "readFile", "read-lifecycle-stale", r.responseData, marker)
            replace r.responseData with "[stale read of {path} — file edited since; retrieveContext hash={hash}]"
        # SUPERSEDED (later readFile covering a wider range) — OFF in v1 (READ_LIFECYCLE_SUPERSEDED=false)
  return history (new list iff anything changed; else same reference)
```
- **Fresh reads are structurally untouched** (no later edit ⇒ skipped) — invariant #3.
- Edit-correctness is unaffected: the edit gate is disk-vs-ledger; a stale read was already useless as an
  anchor (the file changed), so replacing it removes a *wrong* anchor, it doesn't remove a usable one.
- Superseded-read replacement stays OFF until stale-read eval proves safe (proposal §6.5 default).

---

## 7. Test matrix (mirror `ToolOutputCompactorTest` / `WorkerHistoryCompactorTest` — Mockito, `@ExtendWith(MockitoExtension.class)`)

**OFF-mode byte-compat (the rollback guarantee — invariant #1):**
- `WorkerCompactionPolicyTest`: master OFF ⇒ every `should*` returns false.
- `BindForWorkerCompactionOffTest`: flag OFF ⇒ `bindForWorker(ctx)` tool-name set is identical to HEAD; flag ON + remote ⇒ no `retrieveContext`.
- `MaybeCompactOffTest` (WorkerAgent): flag OFF ⇒ `maybeCompact` returns the exact input string.

**Invariant pins:**
- `BuildLogReducerTest`: a passing build envelope still contains `\nexit=0\n` after reduction; header preserved byte-for-byte; body shrunk. A failing build keeps `exit=1` + first/last error.
- `RunTestsNeverReducedTest`: `runTests` content passes through unchanged (gate markers intact).
- `LatestResultNeverReducedTest`: `isLatest=true` ⇒ raw.
- `ShrinkGateTest`: reducer output ≥ raw ⇒ raw returned, nothing stored.
- `FailOpenTest`: store/`Redis` down ⇒ raw returned; no throw.

**Component units:** `CompactContextStoreTest` (put/get/deleteAll, scope isolation across run/task, 24-hex hash), `RetrieveContextToolTest` (hash validation, miss→Ok string, query path, maxChars cap, cross-run isolation), `SearchResultReducerTest`, `ListFilesReducerTest`, `DiffReducerTest`, `ReadLifecycleCompactorTest` (fresh untouched / stale replaced / pairing preserved).

**Integration/eval:** Java build-failure loop · pytest-failure loop (assert the gate still fires through a reduced `buildProject`) · broad-search task · large-read+edit task (assert `editFile` still matches after unrelated reductions) · stale-read-after-edit task · SWE-bench subset with verifiedScore comparison ON vs OFF.

---

## 8. Phase plan (all under master flag default OFF; eval gate before the flip)

| Phase | Deliverable | Exit criteria |
|-------|-------------|---------------|
| **0 — baseline** | Capture token/cost/latency on simple+MEDIUM+HIGH+build-failure+large-read runs (flag OFF). Run the OFF baseline ≥3× to measure the verifiedScore noise band → this sets the Phase-6 non-inferiority margin (§10.1). Save raw tool outputs as reducer fixtures. No code. | Baseline numbers + OFF-variance band recorded in reason-log; fixtures committed. |
| **1 — flag + policy** | V231 `worker.compaction.enabled` (default OFF). `WorkerCompactionPolicy`. Rewire `ToolOutputCompactor` + `WorkerHistoryCompactor` to consult the policy (master + sub-gate). | All OFF-mode byte-compat tests green; `mvn verify` green; production byte-identical (flag OFF). |
| **2 — store + retrieval** | `CompactContextStore` + `deleteAll` wired at `SequentialExecutor:374`. `RetrieveContextTool`. `bindForWorker` gate (D5) + remote exclusion (D3). | Store/tool unit tests green; bind tests green; retrieval scoped + hash-validated. |
| **3 — deterministic reducers** | SPI + router + `ShellLogReducer`/`SearchResultReducer`/`ListFilesReducer`/`DiffReducer`. `maybeCompact` routes reducer-first. LLM compactor demoted to opt-in fallback. | Invariant pins green; reducer units green; with master ON in a test profile, gates still fire. |
| **4 — read-lifecycle** | `ReadLifecycleCompactor` (stale-only; superseded OFF). Hook beside history compaction. | Edit-correctness + read-before-write tests green; fresh-read-never-compacted pinned. |
| **5 — history cleanup** | Confirm reducers-before-summarization (already structural); marker-preservation prompt tweak; before/after prompt-token metric. | History fold runs after reducers; metrics emit. |
| **6 — eval + flip** | Run the eval set (§7) master ON vs OFF. | Acceptance bar (§ below) met → **separate follow-up migration flips the V231 seed to ON**; only then recalibrate `RunCostTracker` bands. |

**Definition of done per phase** (documentation hygiene, mandatory before each commit): root `CLAUDE.md`
(in-flight section), `engine/CLAUDE.md` + a new `tool/compact/CLAUDE.md`, `docs/CODEMAP.md` (new package +
V231), `docs/document_map.md` (this doc), `docs/reason-log/2026-06.md` (status + a new RL id), and the
V231 cross-link from `tool/compact/CLAUDE.md`.

**Acceptance bar (Phase 6, before flipping default ON):** OFF byte-compatible · ON reduces worker input
tokens ≥25% on MEDIUM/HIGH debug-heavy evals · ON does not lower verifiedScore beyond a pre-declared
non-inferiority margin on a pre-declared N (resolve in §10) · retrieval miss rate ≈0 for same-run hashes ·
no fresh read compacted · no latest result compacted · no run failed by compaction · per-strategy metrics
present.

---

## 9. Risks (updated for the verified findings)

| Risk | Mitigation |
|------|------------|
| Reduced `buildProject`/`runTests` envelope breaks the completeTask gate | `runTests` excluded; `buildProject` header (incl. `\nexit=0\n`) preserved verbatim; pinned by `BuildLogReducerTest` + `RunTestsNeverReducedTest`. |
| Compacting a fresh read breaks `editFile` matching | Fresh reads never compacted; read-lifecycle is stale-only and ships last behind its own gate. |
| `retrieveContext` proxied to the Harbor client on a TB run | Not bound on remote runs (compaction is off there anyway). |
| Worker edits against a retrieved (stale) snapshot | Tool output says "re-read before editing"; edit gate independently forces a re-read on STALE. |
| New `Tool` bean changes the default tool list | `bindForWorker` filters it when OFF/remote; OFF-mode tool-list test. |
| LLM fallback costs more than it saves (LIGHTWEIGHT=flagship) | Default-ON path is deterministic-only; LLM fallback stays opt-in behind the V216 sub-gate. |
| Default-ON surprises production | Seeded OFF; flip only via follow-up migration after Phase 6; one master rollback flag. |
| Security leak via retrieval | Scope by run/task, 24-hex hash validation, output cap, redacted logs (no original content logged). |

---

## 10. Open decisions (carry into approval)

1. **Eval N + non-inferiority margin** for "does not lower verifiedScore" — **method agreed
   (2026-06-06): paired flag-ON-vs-OFF experiments on the same eval set.** Still to pick: the eval set,
   N, and the margin. The margin is **derived empirically from the OFF-run variance measured in Phase 0**
   (run the OFF baseline ≥3× to get the run-to-run noise band; the ON arm must stay within it), since
   small-N benchmark noise on this repo is real (see reason-log re ~3-reliable-on-re-run). Proposal:
   SWE-bench Lite subset, N≥20.
2. Retrieval tool name `retrieveContext` (proposed).
3. Whether superseded-read replacement is ever enabled (only after stale proves safe).
4. Whether the two legacy sub-flags become no-ops at the Phase-6 flip or survive one more release.

---

## 11. Appendix — known temporary tier state (context, not an action item)

In this checkout the **LIGHTWEIGHT tier resolves to flagship models** for Anthropic/OpenAI
(`lightweight: claude-sonnet-4-6`, `lightweight: gpt-5.4` — [application.yml:86,94](../../src/main/resources/application.yml#L86), consumed by [ModelResolver.java:60-70](../../src/main/java/com/navneet/executespec/router/ModelResolver.java#L60)), so it currently differs from the CLAUDE.md tier table (Haiku / gpt-5.4-mini / Flash), which is the intended end-state.

**Confirmed by founder (2026-06-06): this is a deliberate temporary state and will be reverted later** —
not a bug, no investigation needed. Two consequences for this plan, both already handled:
- The default-ON path is **deterministic-only**, so it is unaffected by which model LIGHTWEIGHT points at.
- The optional LLM fallback (behind the V216 sub-gate, default OFF) only becomes cost-attractive once the
  tier mapping is reverted to Haiku/mini/Flash — which is exactly why it stays opt-in until then (D7).

## 12. Confirmation gate

This is a plan. Implementation waits for explicit confirmation.

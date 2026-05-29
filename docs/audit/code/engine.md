# Audit: `engine/` — code-gen engine seam + worker loop

Status: **DONE** · Updated 2026-05-29 · 12 files / 1,696 lines · all read in full

## Files
- `WorkerAgent.java` (1,241) — the sequential multi-turn worker loop ✓
- `CodeGenEngine.java` — the pure code-gen seam interface ✓
- `CodeGenEngineImpl.java` (138) — Phase-2 wrap facade over `PatchAgent.run` ✓
- records: `EngineRequest`, `EngineResult`, `EngineBudget`, `EngineCost`, `EngineVerification`, `EngineOutcome`, `VerifyMode`, `WorkerResult`, `WorkerTaskRequest` ✓

## What it does
`WorkerAgent.run` is the heart: send history → LLM (`LlmRouter`) → dispatch tool calls → `completeTask` ends it. The engine seam (`CodeGenEngine`/`Impl`) is a strangler-fig extraction wrapping the same path so code-gen can be measured in isolation.

## Findings

### Accuracy / Efficiency / Cost (the core levers)
- 🔵 **WorkerAgent keeps FULL conversation history — no sliding window** (the loop only appends to `history`; lines 50–63 explicitly defer compaction to "Phase 8"). The ONLY compaction is `maybeCompact` → `ToolOutputCompactor`, which is **default OFF and null-guarded**. On a 120-iter HIGH task this is the dominant input-token + cache-pressure source. **This is the #1 efficiency gap vs Claude Code's `/compact`.**
- 🔵 **`VerifyMode` exists as data** (NONE/SOFT_NUDGE/HARD_GATE) but the Phase-2 facade does NOT pass it (or `EngineBudget`) through — verify behaviour is still hardcoded in the worker/PatchAgent. The seam is the right place to make verify-mode + budget first-class (ties to verification action items).
- 🔵 **The engine is parallel + mostly unused** — `CodeGenEngineImpl` is a `@Service` but only the benchmark routes through it (per design notes); production still calls `SequentialExecutor`→`WorkerAgent` directly. So "extract to improve+measure in isolation" is ~40% realized; `EngineVerification.none()` in Phase 2 means the engine doesn't yet gather build/test evidence.
- 🟢 `EngineCost` is cache-aware (pulls `RunCostTracker` snapshot incl. `cacheReadTokens`) — good cost-per-solve measurement primitive.
- 🟢 Nullable `EngineVerification` (`null` = "not measured" ≠ "failed") is a clean design that the additive scorer relies on.

### WorkerAgent loop mechanics (verified line-by-line)
- 🟢 Budget check every 3 iters; wall-clock check top-of-iter; per-tier iter/wall caps (whichever fires first).
- 🟢 **One progress signal** `itersSinceProgress` (RL-2026-05-005): one-shot `UserMessage` nudge at `progressWarnAfter` (cache-safe — NOT a SystemMessage, preserves Gemini implicit-cache prefix), force-terminate at `progressForceAfter`. `WRITE_CLASS_TOOLS` includes `buildProject` so build attempts count as progress.
- 🟢 **Lever B salvage**: on iter-limit / budget / stagnation-force with `totalWriteCalls==0`, one final forced "emit a write NOW" LLM call — turns empty-patch auto-fail into a best-guess.
- 🟢 **completeTask gates**: test-verification (PATCH; needs `failed:[] errored:[] exit_code:0` within 5 iters), build-verification (reviewer; needs `exit=0` within 5 iters), and the BUILD-mode "verify-before-done" soft nudge (#3, one-shot).
- 🟢 Context snapshot to Redis every 5 iters (`v2:agent-ctx:{runId}:{taskId}`, 24h TTL) for resume; deleted on clean completion.
- 🟢 DeepSeek `reasoning_content` metadata round-trip handled; per-call 120s read-timeout (honoured by Gemini client only).

### Tech-debt
- 🟡 The completeTask path stacks several interacting one-shot flags (`gateOn`, `buildGateOn`, `verifyNudged`, `progressNudged`) — correct but dense; a future edit could easily break a gate interaction. Worth a focused unit-matrix.
- 🟡 `CodeGenEngineImpl.captureDiff` shells `git diff` with hardcoded infra-excludes — likely duplicated in `benchmark/`/`scm`; dedup candidate.

## Cross-refs
Dispatch/caching → [agent.md](agent.md). Tier+caps → [harness.md](harness.md). Compactor → [tool.md](tool.md). Cost snapshot → [cost.md](cost.md). PatchAgent (facade delegate) → agent (pending).

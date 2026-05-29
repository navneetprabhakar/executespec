# Audit: `context/` — worker snapshot + project-context maintainer

Status: **DONE** · Updated 2026-05-29 · 7 files / 948 lines · all read in full

## Files
- `ContextStore.java` (296) — Redis worker-conversation snapshot ✓
- `SavedContext.java` — snapshot record ✓
- `ContextAgent.java` (427) — async maintainer of `.agent-context/CODEBASE_CONTEXT.md` ✓
- `ContextAgentScanner.java` (122) — workspace diff/new-file scan ✓
- `ContextAgentInput.java`, `ContextAgentProperties.java`, `package-info.java` ✓

## What it does — TWO distinct concepts under one package
1. **`ContextStore` (+`SavedContext`)** — persists the worker's in-flight message history to Redis (`v2:agent-ctx:{runId}:{taskId}`, 24h TTL) for crash-resume / retry-with-context. Hand-rolled discriminated-union JSON serialization of Spring AI `Message` subtypes (system/user/assistant+toolCalls/tool). Best-effort (Redis down = no-op). `deleteAll(runId)` cleans per-run keys at run-end (RL-052 T3.14).
2. **`ContextAgent` (+Scanner/Input/Properties)** — an async post-run job that maintains a per-project `.agent-context/CODEBASE_CONTEXT.md` + `decisions.md` via an LLM JSON-patch (KEEP/UPDATE/ADD/REMOVE section ops). Runs on `FallbackLlmRouter` (LIGHTWEIGHT). Auto-commit DISABLED (audit F#45).

## Findings

### Stale-docs / drift
- 📝 **`package-info.java` documents a richer schema than is implemented**: it specifies auxiliary keys `v2:agent-ctx-index:{runId}` (a Set of taskIds) + `v2:agent-ctx-meta:{runId}:{taskId}` (metadata hash) as "Phase 8" — **neither exists in `ContextStore`**. And it calls the saved value a "compacted form" — but the worker saves **full** history (no compaction; consistent with the [engine.md](engine.md) finding). The sizing note (20–40 KB/task) assumes compaction that doesn't happen → real snapshots are likely larger.

### Efficiency / cost
- 🔵 `deleteAll` uses `redis.keys(PREFIX + runId + ":*")` — `KEYS` is O(N) and blocks Redis. Bounded here (~≤20 keys/run) so fine today, but the package-info's own index-set design (never built) was meant to avoid exactly this. If concurrency grows, switch to `SCAN` or build the index set.
- 🔵 Snapshots are full history; combined with no worker compaction, Redis memory + serialize cost scale with task length. A worker compaction step (see engine action items) would shrink these too.

### Tech-debt
- 🟡 `ContextAgent` runs on the **deprecated `FallbackLlmRouter`** (RL-2026-05-041 migration target).
- 🟡 **Conceptual overlap with `memory/`**: `ContextAgent` maintains `CODEBASE_CONTEXT.md` while `memory/RunLedgerAgent` maintains `project_memory_snapshot`. Two project-context systems — verify whether both are live / which the planner actually reads when auditing [memory] (Tier 4). Possible redundancy.
- 🟢 `commitToMain` is `@Deprecated(forRemoval=true)` and inert (good — audit F#45 closed cleanly).

### Correctness
- 🟢 `ContextStore` deserialization drops unknown message types (logs + returns null, filtered) — safe.
- 🟢 All Redis ops null-safe against missing `StringRedisTemplate` (unit-test friendly).

## Cross-refs
Worker snapshot writer/reader → [engine.md](engine.md) (`WorkerAgent` saves every 5 iters), [harness.md](harness.md) (resume + `deleteAll` at run-end). Project memory overlap → memory (Tier 4, pending). `FallbackLlmRouter` → [agent.md](agent.md).

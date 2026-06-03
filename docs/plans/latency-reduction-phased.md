# Latency reduction — phased plan

> Created 2026-06-01 (RL-2026-05-063 follow-up). Owner: agent-quality hardening branch.
> Consolidates the latency levers. **Phase 0 is low-risk and partly lands on the hardening branch;
> Phase 1 (parallel execution) is the deferred, eval-gated big win already scoped in
> RL-2026-05-006 — this doc is its execution plan.**

## The problem (measured)

Fully-sequential execution makes HIGH runs **36–87 min** (Gemini HIGH = ~87 min on an 11-task
plan), which times out the eval harness and is unusable interactively. Root contributors:

1. **No task parallelism** — `SequentialExecutor.execute` is one strict `for` loop; `dependsOn`-
   independent tasks run one after another even though they could run concurrently. (Biggest factor.)
2. **Full-history worker context** — the V2 worker re-sends its entire conversation every turn, so
   late iterations carry large prompts (Gemini observed at 2.2M input tokens on a CRUD app). Caching
   softens cost but not wall-clock — bigger prompts are slower per turn.
3. **Per-task build smoke** — every task runs `buildProject` even when it touched no build-relevant
   files; on a multi-task plan that's N full builds.
4. **Gemini per-turn latency** — genuinely slow; amplifies (1)–(3).

## Phase 0 — quick wins (low risk, no plan-shape change)

Targets (2)+(3)+(4). Each is independently shippable and reversible.

| Lever | Change | Risk | Status |
|-------|--------|------|--------|
| **0a Context discipline** | Default-ON `worker.tool-compactor.enabled` (V216) + `worker.history-compaction.enabled` (V222) so late-iteration prompts shrink. **Prereq already satisfied:** verified 2026-06-01 that `WorkerAgent` threads `req.provider()` into BOTH `ToolOutputCompactor` (WorkerAgent.java:1161) and `WorkerHistoryCompactor` (WorkerAgent.java:463) — each resolves the run's OWN LIGHTWEIGHT model, so no Anthropic-Haiku hard-route / BYOK-affinity break (the RL-2026-05-052 P0 note was stale). Only the default-flip + a no-regression eval remain. | Medium — changes every run's context; the default-flip is the wide step, gated behind an eval. | **prereq DONE; default-flip parked (wide)** |
| **0b Skip redundant per-task builds** | Only run the per-task `buildProject` smoke when the task wrote build-relevant files (or at phase boundaries), not unconditionally. Final-run build/test gate is unchanged, so correctness signal is preserved. | Medium — touches the per-task verification loop; needs eval to confirm no regression in catch-rate. | parked (wide) |
| **0c Budget re-tune** | Revisit per-tier wall-clock / iteration caps now that context is smaller (0a), so legit long tasks finish without the eval-script timeout. | Low | parked |

**Expected:** ~20–40% wall-clock off HIGH runs without touching plan shape. Measure with
`scripts/v2-eval.sh medium+high × 3 providers`, comparing wall-clock **and** verifiedScore.

## Phase 1 — parallel task execution (the big win, eval-gated)

This is **RL-2026-05-006** — re-introduce concurrency on `dependsOn`-independent tasks. Two blockers
make it its own session (not a tail-end change):

- **Shared-workspace file races** — `dependsOn` is a *logical* graph, not file-level; two independent
  tasks can write the same file. No unit test can prove their absence — only a live multi-task run.
- **Task-count band coupling** — `{{MAX_TASKS}}` is unlimited for `MULTI_TASK`; a blanket cap would
  under-decompose genuine LEGACY specs. Needs the `SmartClassifier → PlanStrategy → Classification`
  split into per-strategy caps.

**Design (from RL-006, ready to execute):**
1. New pure `harness/TaskWaveScheduler.java` — `dependsOn → execution waves` (Kahn topological
   levels). Unit-testable in isolation; the reusable core.
2. Wire into `SequentialExecutor#execute` behind `harness.parallel-tasks.enabled` (default OFF),
   bounded concurrency `min(3, waveSize)` on the existing pipeline pool, **thread-safe outcome
   aggregation**, cancel semantics that don't rely on a single loop-top interrupt check, and
   final-smoke ordering that no longer assumes sequential indices.
3. Split the task-count band per strategy (LARGE vs LEGACY caps).
4. (Already shipped as RL-2026-05-011) planner prompt → "single-responsibility / parallel-safe
   dependsOn" instead of "single-file-per-task".
5. **Validate** on `scripts/v2-eval.sh medium+high × 3 providers`, comparing wall-clock AND
   verifiedScore sequential-vs-parallel **before flipping the flag on**.

**Prerequisite:** RL-2026-05-001 (real test execution — gives a deterministic score to measure
parallel-vs-sequential against). Already shipped.

**Expected:** the ~5× speedup v2 originally had, recovered without the over-fragmentation noise.

## Phase 2 — later

- Streaming / incremental task dispatch (start dependents as soon as their deps finish, rather than
  whole-wave barriers).
- Per-provider latency-aware routing for the worker tier (prefer faster providers on the critical path).
- Speculative parallel build while the next task plans.

## Sequencing

Phase 0a-prereq (compactor provider plumbing) → eval → flip 0a default → 0b → 0c → **then** Phase 1
in its own eval-gated session. Each step gated by a wall-clock + verifiedScore eval; nothing flips
to default-ON without a no-regression run.

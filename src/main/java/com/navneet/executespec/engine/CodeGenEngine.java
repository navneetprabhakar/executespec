package com.navneet.executespec.engine;

/**
 * The code-gen engine seam (RL-2026-05-052, {@code docs/plans/codegen-engine-extraction.md}).
 *
 * <p>A single, provider-agnostic entry — <b>pure code-gen</b>, with no knowledge of credits / PRs /
 * scoring / governance (those are upstream/downstream layers). The engine streams a model, runs
 * tools against a <b>prepared</b> workspace, verifies, and returns a diff + transcript + cost. It
 * does NOT clone/checkout (the caller owns workspace setup) and has no side effects beyond the
 * workspace (no DB writes, no SSE, no push — the caller wires those).
 *
 * <p>Strangler-fig rollout: <b>Phase 1 = this contract</b> (no behaviour change — nothing
 * implements or calls it yet); Phase 2 = a wrap facade delegating to today's {@code
 * SequentialExecutor} / {@code WorkerAgent}; later phases move the worker internals into this
 * package and land the pending harness internals (#5 cache discipline · #7 compaction · #8
 * fork-sub-agents · #9 permission ruleset).
 */
public interface CodeGenEngine {

  /**
   * Run one unit of code-gen against a prepared workspace.
   *
   * @param request the task spec + workspace + model + budget + verify mode
   * @return the captured diff + verification evidence + transcript + cost + outcome
   */
  EngineResult solve(EngineRequest request);
}

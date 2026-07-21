package com.navneet.executespec.state;

import java.util.Locale;

/**
 * Single classifier for a {@code pipeline_run.sourceType} that denotes an operator-controlled,
 * blind benchmark worker run (SWE-bench, ProgramBench, Terminal-Bench, and any future suite).
 *
 * <p>Two independent behaviours must key on exactly the same set, or a benchmark run silently gets
 * one treatment and not the other:
 *
 * <ul>
 *   <li>{@code executioncontext.LlmExecutionPolicyFactory} suppresses V266 cross-provider failover
 *       for these runs — a fixed-route measurement whose provider silently switched mid-run would
 *       be reported under the requested route by the campaign artifacts, invalidating any
 *       cross-provider comparison built on them (the internal Terminal-Bench 2.1 multi-model matrix
 *       in particular).
 *   <li>{@code engine.CodeGenEngineImpl} turns the PATCH test-verification gate OFF for these runs
 *       — the target test is held out by design, so the gate can never be satisfied.
 * </ul>
 *
 * <p>Historically each site carried its own {@code startsWith("swebench") ||
 * startsWith("benchmark")} literal test. Those two drifted: {@code TERMINAL_BENCH} (written by
 * {@code terminalbench.TerminalBenchRunService}) matched neither, so Terminal-Bench runs took the
 * permissive route-v1 policy with failover enabled (RR-1590). Both sites now delegate here so they
 * cannot drift again.
 *
 * <p>Matching is case-insensitive and prefix-based. The prefixes cover the source-type strings the
 * benchmark adapters actually write today ({@code "benchmark"} via {@code
 * BenchmarkGenerationService} for SWE-bench + ProgramBench; {@code "TERMINAL_BENCH"} via {@code
 * TerminalBenchRunService}) plus defensive spellings for historical/future suites. No customer
 * source type ({@code MANUAL}, {@code AGENT_TURN}, {@code finding:*}, inbound-integration types)
 * begins with any of them, so a real customer run is never classified as controlled — its failover
 * behaviour is untouched.
 */
public final class BenchmarkSourceType {

  /** SWE-bench + ProgramBench blind generation ({@code BenchmarkGenerationService}). */
  public static final String BENCHMARK = "benchmark";

  /** Historical/defensive SWE-bench source-type spelling. */
  public static final String SWEBENCH = "swebench";

  /** Terminal-Bench remote-brain/local-hands runs ({@code TerminalBenchRunService}). */
  public static final String TERMINAL_BENCH = "TERMINAL_BENCH";

  private static final String[] CONTROLLED_PREFIXES = {
    "benchmark", "swebench", "terminal_bench", "terminalbench", "program_bench", "programbench"
  };

  private BenchmarkSourceType() {
    // No instances.
  }

  /**
   * True when {@code sourceType} denotes an operator-controlled, blind benchmark worker run whose
   * route must stay pure (no cross-provider failover) and whose PATCH test-verification gate must
   * stay off. Null-safe and case-insensitive.
   */
  public static boolean isControlledWorkerSource(String sourceType) {
    if (sourceType == null || sourceType.isBlank()) return false;
    String normalized = sourceType.trim().toLowerCase(Locale.ROOT);
    for (String prefix : CONTROLLED_PREFIXES) {
      if (normalized.startsWith(prefix)) return true;
    }
    return false;
  }
}

package com.navneet.executespec.benchmark;

/**
 * The benchmark suites ExecuteSpec tracks. The in-app tracking layer ({@code benchmark/}) is
 * <b>suite-agnostic</b>: every suite's external harness publishes the same event shapes ({@code
 * TASK_SCORED} / {@code RUN_*} / {@code SUBMISSION_*}) to the common ingest endpoint, and the
 * {@code /benchmark} dashboard renders any of them. Terminal-Bench is the first consumer; SWE-bench
 * + ProgramBench plug in the same way with no schema change.
 *
 * <p>Stored as a string on {@code benchmark_run.suite} (not a JPA enum) so a new suite is just a
 * new value here — no migration. The set is validated on ingest.
 */
public enum BenchmarkSuite {
  TERMINAL_BENCH,
  SWE_BENCH,
  PROGRAM_BENCH;

  public static boolean isKnown(String s) {
    if (s == null) return false;
    for (BenchmarkSuite v : values()) if (v.name().equals(s)) return true;
    return false;
  }
}

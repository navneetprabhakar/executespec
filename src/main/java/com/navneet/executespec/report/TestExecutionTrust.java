package com.navneet.executespec.report;

/** Shared fail-closed interpretation of persisted pass/skip/total test populations. */
public final class TestExecutionTrust {

  private TestExecutionTrust() {}

  /**
   * True when at least one test actually passed and every remaining reported test was explicitly
   * skipped. Any failure, error, negative count, overflow, or malformed population returns false.
   */
  public static boolean successfulPopulation(Integer passed, Integer skipped, Integer total) {
    if (passed == null || total == null) return false;
    int safeSkipped = skipped == null ? 0 : skipped;
    return passed > 0 && safeSkipped >= 0 && total > 0 && (long) passed + safeSkipped == total;
  }
}

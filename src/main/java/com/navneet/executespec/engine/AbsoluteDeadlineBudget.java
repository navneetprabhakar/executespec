package com.navneet.executespec.engine;

import java.util.OptionalInt;

/** Pure arithmetic for one absolute execution deadline and its protected shutdown reserve. */
public final class AbsoluteDeadlineBudget {

  private AbsoluteDeadlineBudget() {}

  public static long usableMillis(Long deadlineEpochMs, Long reserveMs, long nowEpochMs) {
    if (deadlineEpochMs == null || deadlineEpochMs <= 0) return Long.MAX_VALUE;
    long reserve = reserveMs == null ? 0L : Math.max(0L, reserveMs);
    return deadlineEpochMs - nowEpochMs - reserve;
  }

  /**
   * Return a whole-second timeout which cannot cross the protected reserve. Empty means starting
   * the operation would consume shutdown time and must be refused.
   */
  public static OptionalInt timeoutSeconds(
      Long deadlineEpochMs, Long reserveMs, long nowEpochMs, int configuredMaxSeconds) {
    if (deadlineEpochMs == null || deadlineEpochMs <= 0) {
      return OptionalInt.of(Math.max(1, configuredMaxSeconds));
    }
    long usable = usableMillis(deadlineEpochMs, reserveMs, nowEpochMs);
    if (usable < 1000L) return OptionalInt.empty();
    long seconds = usable / 1000L;
    return OptionalInt.of((int) Math.max(1L, Math.min(configuredMaxSeconds, seconds)));
  }
}

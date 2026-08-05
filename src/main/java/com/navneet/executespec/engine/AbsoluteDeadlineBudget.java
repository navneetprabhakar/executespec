package com.navneet.executespec.engine;

import java.util.OptionalInt;

/** Pure arithmetic for one absolute execution deadline and its protected shutdown reserve. */
public final class AbsoluteDeadlineBudget {

  /**
   * Sentinel {@code configuredMaxSeconds} meaning "no caller-side ceiling": with no deadline the
   * result is this value, which {@link #readTimeoutOverride(OptionalInt)} maps to a null per-call
   * HTTP override so the router's per-provider transport window governs instead (2026-08-03,
   * productionizing the TB campaign's global worker 300s pin).
   */
  public static final int NO_DEADLINE_CAP = Integer.MAX_VALUE;

  private AbsoluteDeadlineBudget() {}

  /**
   * Convert a {@link #timeoutSeconds} result into the per-call HTTP read-timeout override for
   * {@code LlmRequest.readTimeoutSeconds}: null when no deadline constrains the call (router
   * per-provider window applies), otherwise the remaining deadline budget in seconds. The router
   * caps any override at the provider window, so a large remaining budget cannot widen a socket
   * timeout. Callers must check {@link OptionalInt#isEmpty()} (refusal) before calling this.
   */
  public static Integer readTimeoutOverride(OptionalInt timeoutSeconds) {
    int value = timeoutSeconds.orElseThrow();
    return value == NO_DEADLINE_CAP ? null : value;
  }

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

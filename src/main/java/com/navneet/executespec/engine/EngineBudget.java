package com.navneet.executespec.engine;

/**
 * Resource caps for one {@link CodeGenEngine#solve}. Null fields = use the engine/tier default (so
 * a caller can pin only what it cares about).
 *
 * @param iterCap max LLM iterations
 * @param wallClockSeconds max wall-clock for the task
 * @param dollarCap hard $ cap (BYOK callers may pass null to skip)
 * @param absoluteDeadlineEpochMs absolute task deadline shared with the caller/harness
 * @param shutdownReserveMs time reserved before that deadline for cleanup and response delivery
 */
public record EngineBudget(
    Integer iterCap,
    Integer wallClockSeconds,
    Double dollarCap,
    Long absoluteDeadlineEpochMs,
    Long shutdownReserveMs) {

  /** Compatibility constructor for callers that only need the original three caps. */
  public EngineBudget(Integer iterCap, Integer wallClockSeconds, Double dollarCap) {
    this(iterCap, wallClockSeconds, dollarCap, null, null);
  }

  /** All-defaults budget (engine/tier decides every cap). */
  public static EngineBudget defaults() {
    return new EngineBudget(null, null, null, null, null);
  }
}

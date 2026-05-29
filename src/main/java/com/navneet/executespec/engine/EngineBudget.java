package com.navneet.executespec.engine;

/**
 * Resource caps for one {@link CodeGenEngine#solve}. Null fields = use the engine/tier default (so
 * a caller can pin only what it cares about).
 *
 * @param iterCap max LLM iterations
 * @param wallClockSeconds max wall-clock for the task
 * @param dollarCap hard $ cap (BYOK callers may pass null to skip)
 */
public record EngineBudget(Integer iterCap, Integer wallClockSeconds, Double dollarCap) {

  /** All-defaults budget (engine/tier decides every cap). */
  public static EngineBudget defaults() {
    return new EngineBudget(null, null, null);
  }
}

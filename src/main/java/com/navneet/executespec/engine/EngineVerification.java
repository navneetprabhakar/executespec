package com.navneet.executespec.engine;

/**
 * Build / test / repro evidence the engine gathered while solving. Every field is nullable — {@code
 * null} means "not measured" (so an unmeasured signal is distinguishable from a failed one, which
 * the additive scorer downstream relies on).
 *
 * @param buildOk did the project build after the change? (null = not built)
 * @param testsPassed tests that passed (null = no test run resolved)
 * @param testsTotal total tests executed (null = no test run resolved)
 * @param detail short human-readable evidence (e.g. the build error, or the test command)
 */
public record EngineVerification(
    Boolean buildOk, Integer testsPassed, Integer testsTotal, String detail) {

  /** Nothing measured. */
  public static EngineVerification none() {
    return new EngineVerification(null, null, null, null);
  }
}

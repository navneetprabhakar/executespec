package com.navneet.executespec.cost;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * RR-825 (2026-07-20) — one validated money/cap value type for every customer-supplied hard cost
 * cap, regardless of which submission surface carried it.
 *
 * <p>{@code POST /api/runs} constrained {@code userCostCap} with bean validation, but the alternate
 * agent surface {@code POST /api/orchestrator/turn/confirm} did not. Jackson accepts the JSON
 * string scalars {@code "NaN"}, {@code "Infinity"} and {@code "-Infinity"} for a {@code Double},
 * and both values defeat the downstream ceiling: {@code Math.min(platformCap, NaN)} is NaN, an
 * infinite BYOK cap is accepted as a positive voluntary ceiling, and the worker's {@code hardCapUsd
 * > 0 && spent >= hardCapUsd} test is false for NaN and never true for infinity. The result was an
 * authenticated caller disabling the platform hard cost stop.
 *
 * <p>This validator is the fail-closed boundary: a present cap must be finite and inside the same
 * documented band the ordinary run DTO advertises. It is applied at the persistence chokepoint
 * ({@code StateStore.setUserCostCap}) so no submission surface — present or future — can persist a
 * cap the enforcement code cannot honour.
 */
public final class CostCapValues {

  /** Matches {@code RunRequest.userCostCap}'s {@code @DecimalMin}. */
  public static final double MIN_USD = 0.10d;

  /** Matches {@code RunRequest.userCostCap}'s {@code @DecimalMax}. */
  public static final double MAX_USD = 1000.00d;

  private CostCapValues() {}

  /**
   * Validates an optional customer-supplied hard cost cap.
   *
   * @return the same value when absent or valid
   * @throws ResponseStatusException 400 when the value is non-finite or outside the documented band
   */
  public static Double requireValidOptional(Double userCostCap) {
    if (userCostCap == null) return null;
    double value = userCostCap;
    if (!Double.isFinite(value)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "userCostCap must be a finite number; NaN and infinite caps cannot bound provider"
              + " spend.");
    }
    if (value < MIN_USD || value > MAX_USD) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "userCostCap must be between $"
              + MIN_USD
              + " and $"
              + MAX_USD
              + " (received "
              + value
              + ").");
    }
    return userCostCap;
  }
}

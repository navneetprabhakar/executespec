package com.navneet.executespec.cost;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import com.navneet.executespec.cost.LlmUsageReconciliation.UsageSide;

import org.junit.jupiter.api.Test;

class LlmUsageReconciliationTest {

  @Test
  void matchesCostWithinRelativeToleranceAndExactTokens() {
    var result =
        LlmUsageReconciliation.calculate(
            "EMPTY",
            "INCOMPLETE",
            side("10.0000", 100),
            side("9.9601", 100),
            2,
            0,
            0,
            0,
            true,
            "matched",
            "incomplete",
            "empty");

    assertThat(result.status()).isEqualTo("MATCH");
    assertThat(result.appliedToleranceUsd()).isEqualByComparingTo("0.05");
    assertThat(result.tokensCompared()).isTrue();
  }

  @Test
  void unknownFailedCostMakesKnownSpendAnIndeterminateLowerBound() {
    var result =
        LlmUsageReconciliation.calculate(
            "EMPTY",
            "INDETERMINATE",
            side("1.25", 0),
            side("1.25", 0),
            1,
            0,
            0,
            1,
            false,
            "matched",
            "unknown provider spend",
            "empty");

    assertThat(result.status()).isEqualTo("INDETERMINATE");
    assertThat(result.unknownCostAttempts()).isOne();
    assertThat(result.explanation()).contains("unknown provider spend");
  }

  @Test
  void exactCostStillMismatchesWhenComparedTokensDiffer() {
    var result =
        LlmUsageReconciliation.calculate(
            "EMPTY",
            "INCOMPLETE",
            side("1", 100),
            side("1", 99),
            1,
            0,
            0,
            0,
            true,
            "matched",
            "incomplete",
            "empty");

    assertThat(result.status()).isEqualTo("MISMATCH");
  }

  private static UsageSide side(String cost, long input) {
    return new UsageSide(new BigDecimal(cost), input, 10, 2, 3, 4);
  }
}

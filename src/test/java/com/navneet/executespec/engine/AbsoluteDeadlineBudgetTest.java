package com.navneet.executespec.engine;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AbsoluteDeadlineBudgetTest {

  @Test
  void timeoutIsCappedByRemainingBudgetMinusShutdownReserve() {
    assertThat(AbsoluteDeadlineBudget.timeoutSeconds(20_000L, 5_000L, 10_000L, 120)).hasValue(5);
  }

  @Test
  void impossibleCallIsRefusedInsteadOfConsumingReserve() {
    assertThat(AbsoluteDeadlineBudget.timeoutSeconds(15_999L, 5_000L, 10_000L, 120)).isEmpty();
  }

  @Test
  void noAbsoluteDeadlinePreservesConfiguredTimeout() {
    assertThat(AbsoluteDeadlineBudget.timeoutSeconds(null, null, 10_000L, 120)).hasValue(120);
  }
}

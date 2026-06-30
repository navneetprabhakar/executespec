package com.navneet.executespec.billing.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ManagedAiCreditCalculatorTest {

  private final ManagedAiCreditCalculator calculator = new ManagedAiCreditCalculator();

  @Test
  void convertsRawUsdToAiCreditsAtConfiguredMultiplier() {
    assertThat(calculator.creditsFor(0.10, 1.25, 125)).isEqualTo(16);
    assertThat(calculator.creditsFor(1.00, 1.00, 125)).isEqualTo(125);
    assertThat(calculator.creditsFor(0.0, 1.25, 125)).isZero();
  }
}

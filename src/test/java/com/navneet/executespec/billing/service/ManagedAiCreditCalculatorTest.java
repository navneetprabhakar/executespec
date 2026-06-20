package com.navneet.executespec.billing.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ManagedAiCreditCalculatorTest {

  private final ManagedAiCreditCalculator calculator = new ManagedAiCreditCalculator();

  @Test
  void convertsRawUsdToEsAiCreditsAtConfiguredMultiplier() {
    assertThat(calculator.creditsFor(0.10, 1.25)).isEqualTo(13);
    assertThat(calculator.creditsFor(1.00, 1.00)).isEqualTo(100);
    assertThat(calculator.creditsFor(0.0, 1.25)).isZero();
  }
}

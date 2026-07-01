package com.navneet.executespec.billing.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ManagedAiCreditCalculatorTest {

  private final ManagedAiCreditCalculator calculator = new ManagedAiCreditCalculator();

  @Test
  void convertsRawUsdToAiCreditsAtConfiguredMultiplier() {
    assertThat(calculator.creditsFor(0.10, 1.25, 80)).isEqualTo(10);
    assertThat(calculator.creditsFor(1.00, 1.00, 80)).isEqualTo(80);
    assertThat(calculator.creditsFor(0.0, 1.25, 80)).isZero();
  }
}

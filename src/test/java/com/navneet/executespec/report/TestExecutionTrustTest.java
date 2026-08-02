package com.navneet.executespec.report;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TestExecutionTrustTest {

  @Test
  void explicitSkipsDoNotTurnAGreenPopulationIntoFailure() {
    assertThat(TestExecutionTrust.successfulPopulation(82, 6, 88)).isTrue();
  }

  @Test
  void failuresAllSkippedAndMalformedCountsStayFailClosed() {
    assertThat(TestExecutionTrust.successfulPopulation(81, 6, 88)).isFalse();
    assertThat(TestExecutionTrust.successfulPopulation(0, 88, 88)).isFalse();
    assertThat(TestExecutionTrust.successfulPopulation(82, -1, 81)).isFalse();
    assertThat(TestExecutionTrust.successfulPopulation(null, 0, 1)).isFalse();
  }
}

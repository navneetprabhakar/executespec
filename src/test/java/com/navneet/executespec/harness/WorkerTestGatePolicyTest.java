package com.navneet.executespec.harness;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class WorkerTestGatePolicyTest {

  @Test
  void autoVerifyAlsoRequiresTestsAtTheFinalSmokeBoundary() {
    assertThat(WorkerTestGatePolicy.shouldRunTests(false, true)).isTrue();
    assertThat(WorkerTestGatePolicy.shouldRunTests(true, false)).isTrue();
    assertThat(WorkerTestGatePolicy.shouldRunTests(false, false)).isFalse();
  }
}

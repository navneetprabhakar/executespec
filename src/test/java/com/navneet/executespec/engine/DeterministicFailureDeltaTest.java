package com.navneet.executespec.engine;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DeterministicFailureDeltaTest {

  @Test
  void distinguishesImprovementRegressionChangeRepetitionAndPass() {
    var first = DeterministicFailureDelta.snapshot(1, 2, 0, 3, "A failed\nB failed in 0.12s");
    var same = DeterministicFailureDelta.snapshot(1, 2, 0, 3, "A failed\nB failed in 9.8s");
    var changed = DeterministicFailureDelta.snapshot(1, 2, 0, 3, "A failed\nC failed");
    var improved = DeterministicFailureDelta.snapshot(2, 1, 0, 3, "C failed");
    var regressed = DeterministicFailureDelta.snapshot(0, 3, 0, 3, "A B C failed");
    var passed = DeterministicFailureDelta.snapshot(3, 0, 0, 3, "3 passed");

    assertThat(DeterministicFailureDelta.compare(null, first))
        .isEqualTo(DeterministicFailureDelta.Kind.INITIAL_FAILURE);
    assertThat(DeterministicFailureDelta.compare(first, same))
        .isEqualTo(DeterministicFailureDelta.Kind.UNCHANGED);
    assertThat(DeterministicFailureDelta.compare(first, changed))
        .isEqualTo(DeterministicFailureDelta.Kind.CHANGED);
    assertThat(DeterministicFailureDelta.compare(first, improved))
        .isEqualTo(DeterministicFailureDelta.Kind.IMPROVED);
    assertThat(DeterministicFailureDelta.compare(first, regressed))
        .isEqualTo(DeterministicFailureDelta.Kind.REGRESSED);
    assertThat(DeterministicFailureDelta.compare(first, passed))
        .isEqualTo(DeterministicFailureDelta.Kind.PASSED);
  }
}

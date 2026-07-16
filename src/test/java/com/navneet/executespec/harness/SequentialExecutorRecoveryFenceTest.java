package com.navneet.executespec.harness;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Pins the generation comparison used to fence stale JVM task writers. */
class SequentialExecutorRecoveryFenceTest {

  @Test
  void legacyNullGenerationIsEquivalentToZero() {
    assertThat(SequentialExecutor.recoveryAttemptMatches(0, null)).isTrue();
    assertThat(SequentialExecutor.recoveryAttemptMatches(1, null)).isFalse();
  }

  @Test
  void takeoverGenerationFencesTheOldWorker() {
    assertThat(SequentialExecutor.recoveryAttemptMatches(0, 1)).isFalse();
    assertThat(SequentialExecutor.recoveryAttemptMatches(1, 1)).isTrue();
  }
}

package com.navneet.executespec.execution.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SolutionExecutionAttemptGenerationTest {

  @Test
  void explicitRetryCountAdvancesTheDurableAttemptGeneration() {
    assertThat(SolutionExecutionService.attemptGeneration(0)).isEqualTo(1);
    assertThat(SolutionExecutionService.attemptGeneration(1)).isEqualTo(2);
    assertThat(SolutionExecutionService.attemptGeneration(3)).isEqualTo(4);
  }

  @Test
  void invalidRetryStateFailsClosed() {
    assertThatThrownBy(() -> SolutionExecutionService.attemptGeneration(-1))
        .isInstanceOf(IllegalArgumentException.class);
  }
}

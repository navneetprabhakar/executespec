package com.navneet.executespec.benchmark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class BenchmarkGenerationHardCapTest {

  @Test
  void operatorCapTightensConfiguredServerCap() {
    assertThat(BenchmarkGenerationService.effectiveHardCapUsd(8.0, 2.0)).isEqualTo(2.0);
  }

  @Test
  void operatorCannotRaiseConfiguredServerCap() {
    assertThat(BenchmarkGenerationService.effectiveHardCapUsd(8.0, 20.0)).isEqualTo(8.0);
  }

  @Test
  void absentOperatorCapKeepsConfiguredServerCap() {
    assertThat(BenchmarkGenerationService.effectiveHardCapUsd(8.0, null)).isEqualTo(8.0);
  }

  @Test
  void rejectsInvalidOperatorCap() {
    assertThatThrownBy(() -> BenchmarkGenerationService.effectiveHardCapUsd(8.0, 0.0))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("finite positive");
  }
}

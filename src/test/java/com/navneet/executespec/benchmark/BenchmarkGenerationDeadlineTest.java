package com.navneet.executespec.benchmark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class BenchmarkGenerationDeadlineTest {

  private static final long NOW = 1_000_000L;

  @Test
  void operatorDeadlineCanTightenButNotExtendServerMaximum() {
    var tightened =
        BenchmarkGenerationService.effectiveDeadlineBudget(NOW + 900_000L, 180_000L, 60, NOW);
    var clamped =
        BenchmarkGenerationService.effectiveDeadlineBudget(NOW + 7_200_000L, 180_000L, 60, NOW);

    assertThat(tightened.deadlineEpochMs()).isEqualTo(NOW + 900_000L);
    assertThat(tightened.wallClockSeconds()).isEqualTo(900);
    assertThat(clamped.deadlineEpochMs()).isEqualTo(NOW + 3_600_000L);
    assertThat(clamped.wallClockSeconds()).isEqualTo(3_600);
  }

  @Test
  void operatorCannotWeakenServerShutdownReserve() {
    var budget =
        BenchmarkGenerationService.effectiveDeadlineBudget(NOW + 900_000L, 1_000L, 60, NOW);

    assertThat(budget.shutdownReserveMs())
        .isEqualTo(BenchmarkGenerationService.DEFAULT_SHUTDOWN_RESERVE_MS);
  }

  @Test
  void rejectsDeadlineThatCannotLeaveCleanupReserve() {
    assertThatThrownBy(
            () ->
                BenchmarkGenerationService.effectiveDeadlineBudget(
                    NOW + 120_000L, 120_000L, 60, NOW))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("must leave more time");
  }
}

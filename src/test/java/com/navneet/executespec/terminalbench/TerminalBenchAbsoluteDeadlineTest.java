package com.navneet.executespec.terminalbench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class TerminalBenchAbsoluteDeadlineTest {

  @Test
  void adapterDeadlineIsAcceptedButTightenedByServerMaximum() {
    long now = 1_000_000L;
    assertThat(TerminalBenchRunService.resolveAbsoluteDeadline(now + 2_000_000L, now, 1410, 45))
        .isEqualTo(now + 1_410_000L);
    assertThat(TerminalBenchRunService.resolveAbsoluteDeadline(now + 900_000L, now, 1410, 45))
        .isEqualTo(now + 900_000L);
  }

  @Test
  void requestLeavingNoShutdownAndExecutionBudgetIsRejected() {
    long now = 1_000_000L;
    assertThatThrownBy(
            () -> TerminalBenchRunService.resolveAbsoluteDeadline(now + 45_999L, now, 1410, 45))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("no executable budget");
  }
}

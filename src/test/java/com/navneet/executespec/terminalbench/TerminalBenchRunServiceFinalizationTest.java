package com.navneet.executespec.terminalbench;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TerminalBenchRunServiceFinalizationTest {

  @Test
  void lateWorkerFinallyDoesNotOverwriteTerminalStatus() {
    assertThat(TerminalBenchRunService.shouldFinalize("CANCELLED")).isFalse();
    assertThat(TerminalBenchRunService.shouldFinalize("COMPLETE")).isFalse();
    assertThat(TerminalBenchRunService.shouldFinalize("FAILED")).isFalse();
    assertThat(TerminalBenchRunService.shouldFinalize("PAUSED")).isFalse();
  }

  @Test
  void activeStatusesCanBeFinalized() {
    assertThat(TerminalBenchRunService.shouldFinalize("WORKING")).isTrue();
    assertThat(TerminalBenchRunService.shouldFinalize(null)).isTrue();
  }
}

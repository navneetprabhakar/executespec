package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RunOrchestratorLocalExecutionPolicyTest {

  @Test
  void localChangesWithoutServerEvidenceArePartial() {
    RunOrchestrator.StatusAndReason result = RunOrchestrator.localExecutionTerminalStatus();

    assertThat(result.status()).isEqualTo("PARTIAL");
    assertThat(result.reason()).isEqualTo("LOCAL_EXECUTION");
  }
}

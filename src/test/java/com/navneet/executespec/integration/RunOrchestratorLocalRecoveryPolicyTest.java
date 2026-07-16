package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RunOrchestratorLocalRecoveryPolicyTest {

  @Test
  void localIntentRequiresOperatorFlag() {
    assertThat(RunOrchestrator.shouldResumeLocalHands(true, true)).isTrue();
    assertThat(RunOrchestrator.shouldResumeLocalHands(true, false)).isFalse();
    assertThat(RunOrchestrator.shouldResumeLocalHands(false, true)).isFalse();
  }
}

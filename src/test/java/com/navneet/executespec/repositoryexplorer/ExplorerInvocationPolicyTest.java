package com.navneet.executespec.repositoryexplorer;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ExplorerInvocationPolicyTest {

  private final ExplorerInvocationPolicy policy = new ExplorerInvocationPolicy();

  @Test
  void staysInertWhenDisabledOrAssignedToControl() {
    assertThat(policy.decide(signals(false, ExplorerExperimentArm.PAIRED, true, 100_000, 4)))
        .isEqualTo(
            new ExplorerInvocationPolicy.Decision(false, "DISABLED", ExplorerExperimentArm.PAIRED));
    assertThat(policy.decide(signals(true, ExplorerExperimentArm.CONTROL, true, 100_000, 4)))
        .isEqualTo(
            new ExplorerInvocationPolicy.Decision(
                false, "CONTROL_ARM", ExplorerExperimentArm.CONTROL));
  }

  @Test
  void invokesOnlyForConfiguredSelectiveSignalsOrPairedArm() {
    assertThat(policy.decide(signals(true, ExplorerExperimentArm.SELECTIVE, true, 1, 0)).reason())
        .isEqualTo("AMBIGUOUS_LOCALIZATION");
    assertThat(
            policy.decide(signals(true, ExplorerExperimentArm.SELECTIVE, false, 1_000, 0)).reason())
        .isEqualTo("LARGE_REPOSITORY");
    assertThat(policy.decide(signals(true, ExplorerExperimentArm.SELECTIVE, false, 1, 2)).reason())
        .isEqualTo("UNKNOWN_IDENTIFIERS");
    assertThat(policy.decide(signals(true, ExplorerExperimentArm.PAIRED, false, 1, 0)).reason())
        .isEqualTo("PAIRED_ARM");
    assertThat(policy.decide(signals(true, ExplorerExperimentArm.SELECTIVE, false, 1, 0)))
        .isEqualTo(
            new ExplorerInvocationPolicy.Decision(
                false, "DETERMINISTIC_EVIDENCE_SUFFICIENT", ExplorerExperimentArm.SELECTIVE));
  }

  private static ExplorerInvocationPolicy.Signals signals(
      boolean enabled,
      ExplorerExperimentArm arm,
      boolean ambiguous,
      long repositoryFiles,
      int unknownIdentifiers) {
    return new ExplorerInvocationPolicy.Signals(
        enabled, arm, ambiguous, repositoryFiles, 1_000, unknownIdentifiers);
  }
}

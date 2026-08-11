package com.navneet.executespec.workspacegeneration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AcceptanceProofPolicyTest {

  @Test
  void compileAndTestsCannotSatisfyAnHttpRuntimeContract() {
    var decision =
        AcceptanceProofPolicy.evaluate(
            AcceptanceProofStrength.RUNTIME_CONTRACT, true, true, true, false, false);

    assertThat(decision.achieved()).isEqualTo(AcceptanceProofStrength.TEST);
    assertThat(decision.sufficient()).isFalse();
  }

  @Test
  void runtimeExecutionCanSatisfyRuntimeButNotHeldOutProof() {
    var runtime =
        AcceptanceProofPolicy.evaluate(
            AcceptanceProofStrength.RUNTIME_CONTRACT, true, true, true, true, false);
    var heldOut =
        AcceptanceProofPolicy.evaluate(
            AcceptanceProofStrength.HELD_OUT, true, true, true, true, false);

    assertThat(runtime.achieved()).isEqualTo(AcceptanceProofStrength.RUNTIME_CONTRACT);
    assertThat(runtime.sufficient()).isTrue();
    assertThat(heldOut.achieved()).isEqualTo(AcceptanceProofStrength.RUNTIME_CONTRACT);
    assertThat(heldOut.sufficient()).isFalse();
  }

  @Test
  void weakerEvidenceNeverSkipsAProofLevel() {
    assertThat(
            AcceptanceProofPolicy.evaluate(
                    AcceptanceProofStrength.BUILD, false, true, true, true, true)
                .achieved())
        .isEqualTo(AcceptanceProofStrength.STATIC_STRUCTURE);
  }
}

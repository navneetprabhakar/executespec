package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.navneet.executespec.contractsurface.BrownfieldHttpContractGuard;

import org.junit.jupiter.api.Test;

class RunFailureDiagnosticTest {

  @Test
  void preservesExactBrownfieldGateAcrossOuterPipelineUnwind() {
    var failure =
        new IllegalStateException(
            "wrapped",
            new BrownfieldHttpContractGuard.ContractCompatibilityException(
                "BROWNFIELD_HTTP_CONTRACT_REMOVED", "POST /simulation/casino-game was removed"));

    assertThat(RunFailureDiagnostic.from(failure, "INTERNAL_ERROR"))
        .extracting(RunFailureDiagnostic::code, RunFailureDiagnostic::reason)
        .containsExactly(
            "BROWNFIELD_HTTP_CONTRACT_REMOVED", "POST /simulation/casino-game was removed");
  }

  @Test
  void unrelatedUnexpectedFailureRemainsGeneric() {
    assertThat(
            RunFailureDiagnostic.from(new IllegalStateException("secret detail"), "INTERNAL_ERROR"))
        .extracting(RunFailureDiagnostic::code, RunFailureDiagnostic::reason)
        .containsExactly("INTERNAL_ERROR", "INTERNAL_ERROR");
  }
}

package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.navneet.executespec.contractsurface.BrownfieldHttpContractGuard;
import com.navneet.executespec.contractsurface.SpringHttpSurfaceExtractor;

import org.junit.jupiter.api.Test;

class RunFailureDiagnosticTest {

  @Test
  void unsupportedRuntimeHasAStableCustomerSafeReason() {
    var failure =
        new com.navneet.executespec.workspacegeneration.BuildPlanCapabilityPolicy
            .UnsupportedRuntimeException("BUILD_PLAN_RUNTIME_UNSUPPORTED:T1:secret-path");
    var diagnostic = RunFailureDiagnostic.from(failure, "INTERNAL_ERROR");
    assertThat(diagnostic.code()).isEqualTo("BUILD_PLAN_RUNTIME_UNSUPPORTED");
    assertThat(diagnostic.reason()).doesNotContain("secret-path");
  }

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

  @Test
  void mapsWorkspaceBoundaryFailuresWithoutLeakingInternalDetails() {
    IllegalStateException failure =
        new IllegalStateException("WORKSPACE_GENERATION_DISPATCH_AUTHORITY_LOST:secret-path");

    assertThat(RunFailureDiagnostic.from(failure, "INTERNAL_ERROR"))
        .extracting(RunFailureDiagnostic::code, RunFailureDiagnostic::reason)
        .containsExactly(
            "WORKSPACE_GENERATION_FAILED",
            "Generated changes did not cross the BUILD acceptance boundary; no unverified"
                + " generation was accepted or published.");
  }

  @Test
  void suppressedRollbackProofFailureOutranksGenericOuterFailure() {
    IllegalStateException failure = new IllegalStateException("unclassified outer failure");
    failure.addSuppressed(
        new IllegalStateException("WORKSPACE_IGNORED_ARTIFACT_CLASSIFICATION_CHANGED"));

    assertThat(RunFailureDiagnostic.from(failure, "INTERNAL_ERROR"))
        .extracting(RunFailureDiagnostic::code, RunFailureDiagnostic::reason)
        .containsExactly(
            "WORKSPACE_GENERATION_RECOVERY_FAILED",
            "Generated changes were rejected, but the workspace could not be proven restored;"
                + " publication was blocked.");
  }

  @Test
  void classifiesSourceExtractionFailureWithoutLeakingWorkspaceDetails() {
    var failure =
        new IllegalStateException(
            "wrapped",
            new SpringHttpSurfaceExtractor.SurfaceExtractionException(
                "could not parse /private/workspace/ClinicSummary.java"));

    assertThat(RunFailureDiagnostic.from(failure, "INTERNAL_ERROR"))
        .extracting(RunFailureDiagnostic::code, RunFailureDiagnostic::reason)
        .containsExactly(
            "BROWNFIELD_HTTP_SURFACE_UNPARSABLE",
            "Brownfield HTTP contract verification could not parse generated Java source");
  }
}

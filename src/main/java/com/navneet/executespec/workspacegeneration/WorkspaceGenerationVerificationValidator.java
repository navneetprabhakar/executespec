package com.navneet.executespec.workspacegeneration;

import java.util.List;

/** Pure structural validation for immutable workspace-generation verification receipts. */
final class WorkspaceGenerationVerificationValidator {

  private WorkspaceGenerationVerificationValidator() {}

  static void validate(WorkspaceGenerationLifecycle.Verification result) {
    if (!List.of("BUILD_AND_TEST", "BUILD_ONLY", "TEST_ONLY").contains(result.verifierKind())) {
      throw new IllegalArgumentException("WORKSPACE_GENERATION_VERIFIER_KIND_INVALID");
    }
    if (!List.of("PASSED", "FAILED", "UNAVAILABLE").contains(result.outcome())) {
      throw new IllegalArgumentException("WORKSPACE_GENERATION_VERIFIER_OUTCOME_INVALID");
    }
    if (!"DISPOSABLE_EXACT_GENERATION".equals(result.executionBoundary())) {
      throw new IllegalArgumentException("WORKSPACE_GENERATION_EXECUTION_BOUNDARY_INVALID");
    }
    if ("PASSED".equals(result.outcome())) {
      if (result.failureCategory() != null || result.environmentCode() != null) {
        throw new IllegalArgumentException("WORKSPACE_GENERATION_PASSING_FAILURE_CAUSE_INVALID");
      }
    } else if ("FAILED".equals(result.outcome())) {
      if (!List.of("PRODUCT", "INTERNAL_PROTOCOL").contains(result.failureCategory())) {
        throw new IllegalArgumentException("WORKSPACE_GENERATION_PRODUCT_FAILURE_CAUSE_INVALID");
      }
    } else if (!List.of("ENVIRONMENT", "INTERNAL_PROTOCOL").contains(result.failureCategory())
        || result.environmentCode() == null
        || result.environmentCode().isBlank()) {
      throw new IllegalArgumentException("WORKSPACE_GENERATION_UNAVAILABLE_CAUSE_INVALID");
    }
    boolean buildOnly = "BUILD_ONLY".equals(result.verifierKind());
    if (buildOnly == result.testsExecuted()) {
      throw new IllegalArgumentException("WORKSPACE_GENERATION_VERIFIER_KIND_INCONSISTENT");
    }
    if (!result.testsExecuted()
        && (result.testsPassed() != 0
            || result.testsFailed() != 0
            || result.testsErrors() != 0
            || result.testsSkipped() != 0)) {
      throw new IllegalArgumentException("WORKSPACE_GENERATION_VERIFIER_COUNTS_INCONSISTENT");
    }
    if ("PASSED".equals(result.outcome())
        && (!result.buildPassed()
            || result.testsFailed() != 0
            || result.testsErrors() != 0
            || (result.testsExecuted() && result.testsPassed() <= 0))) {
      throw new IllegalArgumentException("WORKSPACE_GENERATION_PASSING_RECEIPT_INVALID");
    }
    AcceptanceProofStrength required = proofStrength(result.requiredProofStrength());
    AcceptanceProofStrength achieved = proofStrength(result.achievedProofStrength());
    if ("PASSED".equals(result.outcome()) && !achieved.satisfies(required)) {
      throw new IllegalArgumentException("WORKSPACE_GENERATION_PROOF_STRENGTH_INSUFFICIENT");
    }
    if (result.startedAt() == null
        || result.completedAt() == null
        || result.completedAt().isBefore(result.startedAt())) {
      throw new IllegalArgumentException("WORKSPACE_GENERATION_VERIFIER_TIME_INVALID");
    }
    if (result.serverResolvedCommandContract() == null
        || result.serverResolvedCommandContract().isBlank()) {
      throw new IllegalArgumentException("WORKSPACE_GENERATION_SERVER_COMMAND_CONTRACT_INVALID");
    }
  }

  private static AcceptanceProofStrength proofStrength(String value) {
    try {
      return AcceptanceProofStrength.valueOf(value);
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException("WORKSPACE_GENERATION_PROOF_STRENGTH_INVALID", invalid);
    }
  }
}

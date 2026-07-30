package com.navneet.executespec.integration;

import com.navneet.executespec.harness.ExecutionResult;
import com.navneet.executespec.harness.TaskOutcome;
import com.navneet.executespec.report.model.WorkflowConfigEvidence;
import com.navneet.executespec.reviewer.ReviewerResult;

/**
 * Deterministic promotion policy for a workspace-mutating correction.
 *
 * <p>A correction is not accepted merely because its worker stopped cleanly. Promotion requires the
 * target task to finish successfully, its concrete smoke build to pass, the final workspace to be
 * quiescent, the reviewer to finish without unresolved issues, the final build to pass, and any
 * concrete final test suite to remain green. Missing proof is a rejection, not an implicit pass.
 */
final class CorrectionAcceptancePolicy {

  private CorrectionAcceptancePolicy() {}

  static Decision evaluate(
      String targetTaskId, ExecutionResult execution, ReviewerResult reviewerResult) {
    return evaluate(
        targetTaskId, execution, reviewerResult, WorkflowConfigEvidence.notApplicable());
  }

  static Decision evaluate(
      String targetTaskId,
      ExecutionResult execution,
      ReviewerResult reviewerResult,
      WorkflowConfigEvidence workflowConfigEvidence) {
    return evaluate(targetTaskId, execution, reviewerResult, workflowConfigEvidence, null, null);
  }

  static Decision evaluate(
      String targetTaskId,
      ExecutionResult execution,
      ReviewerResult reviewerResult,
      WorkflowConfigEvidence workflowConfigEvidence,
      String beforeWorkspaceGeneration,
      String afterWorkspaceGeneration) {
    if (execution == null) {
      return Decision.reject("CORRECTION_EXECUTION_MISSING", "Correction produced no execution");
    }
    if (execution.perTask() == null) {
      return Decision.reject(
          "CORRECTION_TARGET_MISSING", "Correction produced no approved target task evidence");
    }
    TaskOutcome target =
        execution.perTask().stream()
            .filter(task -> task != null && sameTask(targetTaskId, task.taskId()))
            .findFirst()
            .orElse(null);
    if (target == null) {
      return Decision.reject(
          "CORRECTION_TARGET_MISSING", "Correction did not produce the approved target task");
    }
    if (target.status() != TaskOutcome.Status.SUCCESS
        && target.status() != TaskOutcome.Status.SUCCESS_AFTER_RETRY) {
      return Decision.reject(
          "CORRECTION_TASK_NOT_SUCCESSFUL",
          "Correction target ended with " + String.valueOf(target.status()));
    }
    if (!Boolean.TRUE.equals(target.smokeTestPassed())) {
      return Decision.reject(
          "CORRECTION_SMOKE_UNPROVEN", "Correction target did not prove a passing smoke build");
    }
    if (reviewerResult == null) {
      return Decision.reject(
          "CORRECTION_REVIEW_MISSING", "Correction produced no final reviewer evidence");
    }
    if (!reviewerResult.isWorkspaceQuiescent()) {
      return Decision.reject(
          "CORRECTION_WORKSPACE_NOT_QUIESCENT",
          "Correction workspace may still be mutating after review");
    }
    if (reviewerResult.overall() != ReviewerResult.Overall.CLEAN
        && reviewerResult.overall() != ReviewerResult.Overall.FIXED_ISSUES) {
      return Decision.reject(
          "CORRECTION_REVIEW_NOT_ACCEPTED",
          "Correction reviewer ended with " + String.valueOf(reviewerResult.overall()));
    }
    if (!Boolean.TRUE.equals(reviewerResult.finalBuildPassed())) {
      return Decision.reject(
          "CORRECTION_FINAL_BUILD_UNPROVEN", "Correction did not prove a passing final build");
    }
    if (reviewerResult.concreteTestsRed()) {
      return Decision.reject(
          "CORRECTION_FINAL_TESTS_FAILED", "Correction left the final concrete test suite red");
    }
    if (reviewerResult.finalTestsTotal() == null || reviewerResult.finalTestsTotal() <= 0) {
      return Decision.reject(
          "CORRECTION_FINAL_TESTS_UNPROVEN", "Correction did not execute a real final test suite");
    }
    if (reviewerResult.finalTestsPassed() == null
        || reviewerResult.finalTestsPassed() < reviewerResult.finalTestsTotal()) {
      return Decision.reject(
          "CORRECTION_FINAL_TESTS_UNPROVEN",
          "Correction did not prove every executed final test passed");
    }
    if (workflowConfigEvidence == null
        || workflowConfigEvidence.status() == WorkflowConfigEvidence.Status.UNAVAILABLE) {
      return Decision.reject(
          "CORRECTION_WORKFLOW_EVIDENCE_UNAVAILABLE",
          "Correction did not produce deterministic workflow/config evidence");
    }
    if (workflowConfigEvidence.status() == WorkflowConfigEvidence.Status.FAILED) {
      return Decision.reject(
          "CORRECTION_WORKFLOW_CONFIG_INVALID",
          workflowConfigEvidence.reason() == null
              ? "Correction left invalid workflow/config files"
              : workflowConfigEvidence.reason());
    }
    if (beforeWorkspaceGeneration != null
        && beforeWorkspaceGeneration.equals(afterWorkspaceGeneration)) {
      return Decision.reject(
          "CORRECTION_NO_CHANGE", "Correction produced no workspace content change");
    }
    return new Decision(true, "CORRECTION_ACCEPTED", "Correction passed every promotion gate");
  }

  /**
   * Rejects a correction whose freshly regenerated deterministic evidence is weaker than the
   * evidence bound to the pre-correction generation. Green-but-narrower test execution is a
   * regression: dropping tests cannot manufacture a passing correction.
   */
  static Decision evaluateRegression(CorrectionResolutionEvidence.ValidationDelta delta) {
    if (delta == null || delta.after() == null) {
      return Decision.reject(
          "CORRECTION_COMPARATIVE_EVIDENCE_MISSING",
          "Correction did not produce comparable before/after validation evidence");
    }
    if (delta.before() == null) {
      return new Decision(
          true,
          "CORRECTION_EVIDENCE_BASELINE_NOT_APPLICABLE",
          "No prior published validation evidence existed to regress");
    }
    var before = delta.before();
    var after = delta.after();
    if (passed(before.build()) && !passed(after.build())) {
      return regression("build proof changed from passing to unproven or failing");
    }
    if (passed(before.tests())) {
      if (!passed(after.tests())) {
        return regression("test proof changed from passing to unproven or failing");
      }
      int beforeTotal = value(before.tests().total());
      int afterTotal = value(after.tests().total());
      int beforePassed = value(before.tests().passed());
      int afterPassed = value(after.tests().passed());
      if (afterTotal < beforeTotal || afterPassed < beforePassed) {
        return regression(
            "executed test evidence narrowed from "
                + beforePassed
                + "/"
                + beforeTotal
                + " to "
                + afterPassed
                + "/"
                + afterTotal);
      }
    }
    if (compatible(before.contracts()) && !compatible(after.contracts())) {
      return regression("protected brownfield contract evidence regressed");
    }
    if (validWorkflow(before.workflow()) && !validWorkflow(after.workflow())) {
      return regression("deterministic workflow/config evidence regressed");
    }
    return new Decision(
        true,
        "CORRECTION_EVIDENCE_NON_REGRESSIVE",
        "Protected validation evidence did not regress");
  }

  private static Decision regression(String detail) {
    return Decision.reject("CORRECTION_PROTECTED_EVIDENCE_REGRESSION", detail);
  }

  private static boolean passed(CorrectionResolutionEvidence.BuildValidation evidence) {
    return evidence != null && "PASSED".equals(evidence.verdict());
  }

  private static boolean passed(CorrectionResolutionEvidence.TestValidation evidence) {
    return evidence != null && "PASSED".equals(evidence.verdict());
  }

  private static boolean compatible(CorrectionResolutionEvidence.ContractValidation evidence) {
    return evidence != null && "COMPATIBLE".equals(evidence.status());
  }

  private static boolean validWorkflow(CorrectionResolutionEvidence.WorkflowValidation evidence) {
    return evidence != null
        && ("PASSED".equals(evidence.status()) || "NOT_APPLICABLE".equals(evidence.status()));
  }

  private static int value(Integer value) {
    return value == null ? 0 : value;
  }

  private static boolean sameTask(String expected, String actual) {
    if (expected == null || actual == null) {
      return false;
    }
    if (expected.equals(actual)) {
      return true;
    }
    return expected.endsWith("_" + actual) || actual.endsWith("_" + expected);
  }

  record Decision(boolean accepted, String code, String reason) {
    static Decision reject(String code, String reason) {
      return new Decision(false, code, reason);
    }
  }
}

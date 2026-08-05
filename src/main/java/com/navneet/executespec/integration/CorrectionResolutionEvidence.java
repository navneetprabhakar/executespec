package com.navneet.executespec.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.navneet.executespec.contractsurface.BrownfieldHttpContractGuard;
import com.navneet.executespec.harness.ExecutionResult;
import com.navneet.executespec.report.entity.ValidationReport;
import com.navneet.executespec.report.model.WorkflowConfigEvidence;
import com.navneet.executespec.reviewer.ReviewerResult;

/** Canonical JSON contract for durable automatic-correction resolution evidence. */
final class CorrectionResolutionEvidence {

  private static final int MAX_REASON_CHARS = 500;

  private CorrectionResolutionEvidence() {}

  static ObjectNode append(
      ObjectMapper objectMapper,
      ArrayNode actions,
      String status,
      String code,
      String reason,
      String beforeGeneration,
      String afterGeneration,
      CorrectionWorkspaceTransaction.DiffEvidence diff,
      ValidationDelta validationDelta,
      boolean evidenceInvalidated) {
    return append(
        objectMapper,
        actions,
        status,
        code,
        reason,
        null,
        null,
        beforeGeneration,
        afterGeneration,
        diff,
        validationDelta,
        evidenceInvalidated);
  }

  static ObjectNode append(
      ObjectMapper objectMapper,
      ArrayNode actions,
      String status,
      String code,
      String reason,
      String approvalId,
      String proposalDigest,
      String beforeGeneration,
      String afterGeneration,
      CorrectionWorkspaceTransaction.DiffEvidence diff,
      ValidationDelta validationDelta,
      boolean evidenceInvalidated) {
    ObjectNode resolution = actions.addObject();
    resolution.put("status", status);
    resolution.put("decision", "CORRECTION_RESOLUTION");
    resolution.put("code", code == null ? "CORRECTION_UNKNOWN" : code);
    resolution.put("reason", bounded(reason));
    if (approvalId != null && !approvalId.isBlank()) {
      resolution.put("approvalId", approvalId);
    }
    if (proposalDigest != null && !proposalDigest.isBlank()) {
      resolution.put("proposalDigest", proposalDigest);
    }
    resolution.put("beforeWorkspaceGeneration", beforeGeneration);
    resolution.put("afterWorkspaceGeneration", afterGeneration);
    if (diff != null) {
      resolution.put("diffDigest", diff.diffDigest());
      ArrayNode changedFiles = resolution.putArray("changedFiles");
      diff.changedFiles().forEach(changedFiles::add);
      resolution.put("unifiedPatch", diff.unifiedPatch());
      ObjectNode metadata = resolution.putObject("patchMetadata");
      metadata.put("capturedBytes", diff.patchCapturedBytes());
      metadata.put("fullBytes", diff.patchFullBytes());
      metadata.put("truncated", diff.patchTruncated());
      metadata.put("redacted", diff.patchRedacted());
      metadata.put("maxBytes", CorrectionWorkspaceTransaction.MAX_PATCH_BYTES);
    }
    if (validationDelta != null) {
      resolution.set("validationDelta", objectMapper.valueToTree(validationDelta));
    }
    resolution.put("evidenceInvalidated", evidenceInvalidated);
    resolution.put(
        "validationRegenerated",
        validationDelta != null
            && validationDelta.after() != null
            && (validationDelta.after().status() == null
                || !validationDelta.after().status().startsWith("CORRECTION_ATTEMPT")));
    resolution.put("attempt", actions.size());
    return resolution;
  }

  static ValidationSnapshot snapshot(ObjectMapper objectMapper, ValidationReport report) {
    return snapshot(objectMapper, report, ContractValidation.unknown());
  }

  static ValidationSnapshot snapshot(
      ObjectMapper objectMapper, ValidationReport report, ContractValidation contracts) {
    if (report == null) return null;
    WorkflowValidation workflow = workflow(objectMapper, report.getWorkflowConfigEvidenceJson());
    return new ValidationSnapshot(
        report.getStatus(),
        report.getWorkspaceGeneration(),
        new BuildValidation(
            verdict(report.getFinalBuildPassed()),
            report.getFinalBuildPassed(),
            report.getBuildExitCode()),
        new TestValidation(
            testVerdict(report),
            report.getTestsPassed(),
            report.getTestsTotal(),
            report.getTestsSkipped(),
            report.getTestExecutionVerified(),
            report.getTestEvidenceScope(),
            report.getTestExitCode()),
        workflow,
        new ReviewerValidation(report.getReviewerVerdict()),
        contracts == null ? ContractValidation.unknown() : contracts,
        new ScoreValidation(report.getVerifiedScore(), report.getRubricScore()),
        report.getPipelineResult() == null ? null : report.getPipelineResult().name(),
        report.getExecutableVerificationStatus(),
        report.getExecutionFailureStage());
  }

  static ContractValidation contracts(BrownfieldHttpContractGuard.CompatibilityReport report) {
    if (report == null) return ContractValidation.notApplicable();
    return new ContractValidation(
        report.compatible() ? "COMPATIBLE" : "BREAKING",
        report.baselineDigest(),
        report.currentDigest(),
        report.missing() == null ? 0 : report.missing().size(),
        report.added() == null ? 0 : report.added().size());
  }

  static ValidationDelta delta(ValidationSnapshot before, ValidationSnapshot after) {
    if (before == null && after == null) return null;
    return new ValidationDelta(
        before,
        after,
        numericDelta(score(before, true), score(after, true)),
        numericDelta(score(before, false), score(after, false)));
  }

  static ValidationSnapshot attemptSnapshot(
      String workspaceGeneration,
      ExecutionResult execution,
      ReviewerResult review,
      WorkflowConfigEvidence workflow,
      ContractValidation contracts) {
    WorkflowValidation workflowValidation =
        workflow == null
            ? WorkflowValidation.unknown()
            : new WorkflowValidation(
                workflow.status().name(),
                workflow.files() == null ? 0 : workflow.files().size(),
                bounded(workflow.reason()));
    Boolean buildPassed = review == null ? null : review.finalBuildPassed();
    Integer testsPassed = review == null ? null : review.finalTestsPassed();
    Integer testsTotal = review == null ? null : review.finalTestsTotal();
    Integer testsSkipped = review == null ? null : review.finalTestsSkipped();
    String testsVerdict =
        testsTotal == null || testsTotal <= 0 || testsPassed == null
            ? "UNPROVEN"
            : com.navneet.executespec.report.TestExecutionTrust.successfulPopulation(
                    testsPassed, testsSkipped, testsTotal)
                ? "PASSED"
                : "FAILED";
    return new ValidationSnapshot(
        "CORRECTION_ATTEMPT",
        workspaceGeneration,
        new BuildValidation(
            verdict(buildPassed), buildPassed, review == null ? null : review.finalBuildExitCode()),
        new TestValidation(
            testsVerdict,
            testsPassed,
            testsTotal,
            testsSkipped,
            testsTotal != null && testsTotal > 0,
            "WORKSPACE_TEST_SUITE",
            null),
        workflowValidation,
        new ReviewerValidation(
            review == null || review.overall() == null ? null : review.overall().name()),
        contracts == null ? ContractValidation.unknown() : contracts,
        new ScoreValidation(null, null),
        execution == null || execution.overall() == null ? null : execution.overall().name(),
        "NOT_REGENERATED",
        null);
  }

  static ValidationSnapshot failedAttemptSnapshot(
      String workspaceGeneration, String failureStage, ContractValidation contracts) {
    return new ValidationSnapshot(
        "CORRECTION_ATTEMPT_FAILED",
        workspaceGeneration,
        new BuildValidation("UNAVAILABLE", null, null),
        new TestValidation("UNAVAILABLE", null, null, null, null, null, null),
        new WorkflowValidation("UNAVAILABLE", 0, null),
        new ReviewerValidation("UNAVAILABLE"),
        contracts == null ? ContractValidation.unknown() : contracts,
        new ScoreValidation(null, null),
        null,
        "NOT_REGENERATED",
        bounded(failureStage));
  }

  private static Integer score(ValidationSnapshot snapshot, boolean verified) {
    if (snapshot == null || snapshot.score() == null) return null;
    return verified ? snapshot.score().verifiedScore() : snapshot.score().rubricScore();
  }

  private static Integer numericDelta(Integer before, Integer after) {
    return before == null || after == null ? null : after - before;
  }

  private static String verdict(Boolean passed) {
    return passed == null ? "UNPROVEN" : passed ? "PASSED" : "FAILED";
  }

  private static String testVerdict(ValidationReport report) {
    if (report.getTestsTotal() == null || report.getTestsTotal() <= 0) return "UNPROVEN";
    if (report.getTestsPassed() == null) return "UNPROVEN";
    return com.navneet.executespec.report.TestExecutionTrust.successfulPopulation(
            report.getTestsPassed(), report.getTestsSkipped(), report.getTestsTotal())
        ? "PASSED"
        : "FAILED";
  }

  private static WorkflowValidation workflow(ObjectMapper objectMapper, String json) {
    if (json == null || json.isBlank()) return WorkflowValidation.unknown();
    try {
      JsonNode node = objectMapper.readTree(json);
      String status = node.path("status").asText("UNKNOWN");
      String reason =
          node.path("reason").isTextual() ? bounded(node.path("reason").asText()) : null;
      int files = node.path("files").isArray() ? node.path("files").size() : 0;
      return new WorkflowValidation(status, files, reason);
    } catch (Exception malformed) {
      return new WorkflowValidation("MALFORMED", 0, "Stored workflow evidence was not valid JSON");
    }
  }

  private static String bounded(String value) {
    if (value == null) return "";
    return value.length() <= MAX_REASON_CHARS ? value : value.substring(0, MAX_REASON_CHARS);
  }

  record ValidationDelta(
      ValidationSnapshot before,
      ValidationSnapshot after,
      Integer verifiedScoreDelta,
      Integer rubricScoreDelta) {}

  record ValidationSnapshot(
      String status,
      String workspaceGeneration,
      BuildValidation build,
      TestValidation tests,
      WorkflowValidation workflow,
      ReviewerValidation reviewer,
      ContractValidation contracts,
      ScoreValidation score,
      String pipelineResult,
      String executableVerificationStatus,
      String executionFailureStage) {}

  record BuildValidation(String verdict, Boolean passed, Integer exitCode) {}

  record TestValidation(
      String verdict,
      Integer passed,
      Integer total,
      Integer skipped,
      Boolean executionVerified,
      String evidenceScope,
      Integer exitCode) {}

  record WorkflowValidation(String status, int fileCount, String reason) {
    static WorkflowValidation unknown() {
      return new WorkflowValidation("UNKNOWN", 0, null);
    }
  }

  record ReviewerValidation(String verdict) {}

  record ContractValidation(
      String status,
      String baselineDigest,
      String currentDigest,
      int missingCount,
      int addedCount) {
    static ContractValidation unknown() {
      return new ContractValidation("UNKNOWN", null, null, 0, 0);
    }

    static ContractValidation notApplicable() {
      return new ContractValidation("NOT_APPLICABLE", null, null, 0, 0);
    }
  }

  record ScoreValidation(Integer verifiedScore, Integer rubricScore) {}
}

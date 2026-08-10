package com.navneet.executespec.workspacegeneration;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;

/** Operator-only immutable evidence read used by the separately authorized BUILD qualification. */
@Service
@RequiredArgsConstructor
public class WorkspaceGenerationQualificationEvidenceService {

  private final WorkspaceGenerationAttemptRepository attempts;
  private final WorkspaceGenerationVerificationReceiptRepository receipts;

  @Transactional(readOnly = true)
  public QualificationEvidence read(UUID attemptId) {
    WorkspaceGenerationAttempt attempt =
        attempts
            .findById(attemptId)
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Workspace generation attempt not found"));
    WorkspaceGenerationVerificationReceipt receipt =
        receipts.findFirstByAttemptIdOrderByCreatedAtDesc(attemptId).orElse(null);
    return new QualificationEvidence(
        attempt.getId(),
        attempt.getRunId(),
        attempt.getTaskId(),
        attempt.getState(),
        attempt.getBeforeGeneration(),
        attempt.getCandidateGeneration(),
        attempt.getRejectedDiffDigest(),
        attempt.getRejectedDiffJson(),
        attempt.getFailureCode(),
        attempt.getFailureReason(),
        attempt.getRequestedProvider(),
        attempt.getRequestedModel(),
        attempt.getEffectiveProvider(),
        attempt.getEffectiveModel(),
        attempt.getWorkerIterations(),
        attempt.getWorkerToolCalls(),
        attempt.getModelCalls(),
        attempt.getCostUsd(),
        attempt.getCreatedAt(),
        attempt.getResolvedAt(),
        receipt == null
            ? null
            : new VerificationEvidence(
                receipt.getId(),
                receipt.getCandidateGeneration(),
                receipt.getOutcome(),
                receipt.isBuildPassed(),
                receipt.isTestsExecuted(),
                receipt.getTestsPassed(),
                receipt.getTestsFailed(),
                receipt.getTestsErrors(),
                receipt.getTestsSkipped(),
                receipt.getEvidenceDigest()));
  }

  public record QualificationEvidence(
      UUID attemptId,
      String runId,
      String taskId,
      WorkspaceGenerationState state,
      String priorAcceptedGeneration,
      String candidateGeneration,
      String rejectedDiffDigest,
      String rejectedDiffJson,
      String failureCode,
      String failureReason,
      String requestedProvider,
      String requestedModel,
      String effectiveProvider,
      String effectiveModel,
      Integer workerIterations,
      Integer workerToolCalls,
      Long modelCalls,
      BigDecimal costUsd,
      Instant createdAt,
      Instant resolvedAt,
      VerificationEvidence latestVerification) {}

  public record VerificationEvidence(
      UUID receiptId,
      String candidateGeneration,
      String outcome,
      boolean buildPassed,
      boolean testsExecuted,
      int testsPassed,
      int testsFailed,
      int testsErrors,
      int testsSkipped,
      String evidenceDigest) {}
}

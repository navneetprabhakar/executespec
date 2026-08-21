package com.navneet.executespec.workspacegeneration;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
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
  private final BuildEnvironmentReceiptStore environmentReceipts;
  private final WorkspaceGenerationStepStore generationSteps;

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
        attempt.getRecoveryFailureCode(),
        attempt.getRecoveryFailureReason(),
        attempt.getRecoveryFailureAt(),
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
                receipt.getCommandReceiptIds(),
                receipt.getEvidenceDigest()),
        environmentReceipts.readAll(attemptId).stream()
            .map(
                receiptEvidence ->
                    new EnvironmentEvidence(
                        receiptEvidence.receiptId(),
                        receiptEvidence.phase(),
                        receiptEvidence.receiptKind(),
                        receiptEvidence.planDigest(),
                        receiptEvidence.runtimeImage(),
                        receiptEvidence.networkPolicy(),
                        receiptEvidence.manifestDigest(),
                        receiptEvidence.disposition(),
                        receiptEvidence.removedRootsJson(),
                        receiptEvidence.removedRootsDigest(),
                        receiptEvidence.evidenceJson(),
                        receiptEvidence.evidenceDigest(),
                        receiptEvidence.createdAt()))
            .toList(),
        generationSteps.readAll(attemptId).stream()
            .map(
                step ->
                    new FilesystemStepEvidence(
                        step.id(),
                        step.kind().name(),
                        step.stepKey(),
                        step.idempotencyKey(),
                        step.state().name(),
                        step.expectedGeneration(),
                        step.expectedTree(),
                        step.objectKey(),
                        step.artifactDigest(),
                        step.storageGeneration(),
                        step.sizeBytes(),
                        step.crc32c(),
                        step.etag(),
                        step.evidenceJson()))
            .toList());
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
      String recoveryFailureCode,
      String recoveryFailureReason,
      Instant recoveryFailureAt,
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
      VerificationEvidence latestVerification,
      List<EnvironmentEvidence> environmentReceipts,
      List<FilesystemStepEvidence> filesystemSteps) {
    public QualificationEvidence {
      environmentReceipts =
          environmentReceipts == null ? List.of() : List.copyOf(environmentReceipts);
      filesystemSteps = filesystemSteps == null ? List.of() : List.copyOf(filesystemSteps);
    }
  }

  public record FilesystemStepEvidence(
      UUID stepId,
      String kind,
      String stepKey,
      String idempotencyKey,
      String state,
      String expectedGeneration,
      String expectedTree,
      String objectKey,
      String artifactDigest,
      String storageGeneration,
      Long sizeBytes,
      String crc32c,
      String etag,
      String evidenceJson) {}

  public record EnvironmentEvidence(
      UUID receiptId,
      String phase,
      String receiptKind,
      String planDigest,
      String runtimeImage,
      String networkPolicy,
      String manifestDigest,
      String disposition,
      String removedRootsJson,
      String removedRootsDigest,
      String evidenceJson,
      String evidenceDigest,
      Instant createdAt) {}

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
      String commandReceiptIds,
      String evidenceDigest) {}
}

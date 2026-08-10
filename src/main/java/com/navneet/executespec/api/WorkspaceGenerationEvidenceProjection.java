package com.navneet.executespec.api;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.api.dto.RunDetail;
import com.navneet.executespec.workspacegeneration.TaskAcceptanceContract;
import com.navneet.executespec.workspacegeneration.WorkspaceGenerationAttempt;
import com.navneet.executespec.workspacegeneration.WorkspaceGenerationAttemptRepository;
import com.navneet.executespec.workspacegeneration.WorkspaceGenerationVerificationReceipt;
import com.navneet.executespec.workspacegeneration.WorkspaceGenerationVerificationReceiptRepository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Projects the bounded customer-safe BUILD generation ledger for run detail. */
@Component
public class WorkspaceGenerationEvidenceProjection {

  private final WorkspaceGenerationAttemptRepository attempts;
  private final WorkspaceGenerationVerificationReceiptRepository receipts;
  private final ObjectMapper objectMapper;

  public WorkspaceGenerationEvidenceProjection(WorkspaceGenerationAttemptRepository attempts) {
    this(attempts, null, new ObjectMapper());
  }

  @Autowired
  public WorkspaceGenerationEvidenceProjection(
      WorkspaceGenerationAttemptRepository attempts,
      WorkspaceGenerationVerificationReceiptRepository receipts,
      ObjectMapper objectMapper) {
    this.attempts = attempts;
    this.receipts = receipts;
    this.objectMapper = objectMapper;
  }

  public List<RunDetail.WorkspaceGenerationAttemptEvidence> forRun(String runId) {
    if (runId == null) return List.of();
    return attempts.findTop20ByRunIdOrderByCreatedAtDesc(runId).stream()
        .map(this::project)
        .toList();
  }

  private RunDetail.WorkspaceGenerationAttemptEvidence project(WorkspaceGenerationAttempt attempt) {
    TaskAcceptanceContract contract = contract(attempt.getTaskContractJson());
    WorkspaceGenerationVerificationReceipt receipt =
        receipts == null
            ? null
            : receipts.findFirstByAttemptIdOrderByCreatedAtDesc(attempt.getId()).orElse(null);
    return new RunDetail.WorkspaceGenerationAttemptEvidence(
        attempt.getId().toString(),
        attempt.getTaskId(),
        attempt.getTaskIndex(),
        attempt.getAttemptNumber(),
        attempt.getAttemptKind().name(),
        attempt.getState().name(),
        attempt.getBeforeGeneration(),
        attempt.getCandidateGeneration(),
        attempt.getTaskContractDigest(),
        contract == null ? null : contract.runtimeSurface(),
        contract == null ? null : contract.baselineKind(),
        contract == null || contract.buildCommand() == null
            ? null
            : contract.buildCommand().command(),
        contract == null || contract.testCommand() == null
            ? null
            : contract.testCommand().command(),
        contract == null ? List.of() : contract.requiredPaths(),
        contract == null
            ? List.of()
            : java.util.stream.Stream.concat(
                    contract.prefixChecks().stream(), contract.finalChecks().stream())
                .map(
                    check ->
                        new RunDetail.WorkspaceGenerationAcceptanceCheck(
                            check.kind(), check.target(), check.hidden()))
                .toList(),
        attempt.getRequestedProvider(),
        attempt.getRequestedModel(),
        attempt.getEffectiveProvider(),
        attempt.getEffectiveModel(),
        attempt.getWorkerIterations(),
        attempt.getWorkerToolCalls(),
        attempt.getModelCalls(),
        attempt.getInputTokens(),
        attempt.getOutputTokens(),
        attempt.getCacheReadTokens(),
        attempt.getCacheWriteTokens(),
        attempt.getReasoningTokens(),
        attempt.getCostUsd(),
        receipt == null ? null : receipt.getId().toString(),
        receipt == null ? null : receipt.getOutcome(),
        receipt == null ? null : receipt.isBuildPassed(),
        receipt == null ? null : receipt.isTestsExecuted(),
        receipt == null ? null : receipt.getTestsPassed(),
        receipt == null ? null : receipt.getTestsFailed(),
        receipt == null ? null : receipt.getEvidenceDigest(),
        attempt.getRejectedDiffDigest(),
        attempt.getFailureCode(),
        attempt.getRecoveryFailureCode(),
        attempt.getRecoveryFailureAt(),
        attempt.getCreatedAt(),
        attempt.getResolvedAt());
  }

  private TaskAcceptanceContract contract(String json) {
    if (json == null || json.isBlank()) return null;
    try {
      return objectMapper.readValue(json, TaskAcceptanceContract.class);
    } catch (Exception malformedHistoricalEvidence) {
      return null;
    }
  }
}

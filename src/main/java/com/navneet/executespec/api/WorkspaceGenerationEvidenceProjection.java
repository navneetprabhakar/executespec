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
    java.util.Map<String, CheckResult> checkResults =
        receipt == null ? java.util.Map.of() : checkResults(receipt.getEvidenceJson());
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
        initialFailure(attempt),
        contract == null ? null : contract.runtimeSurface(),
        contract == null ? null : contract.baselineKind(),
        contract == null || contract.buildCommand() == null
            ? null
            : contract.buildCommand().command(),
        contract == null || contract.testCommand() == null
            ? null
            : contract.testCommand().command(),
        contract == null ? List.of() : contract.requiredPaths(),
        acceptanceChecks(contract, checkResults),
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

  private RunDetail.WorkspaceGenerationInitialFailureEvidence initialFailure(
      WorkspaceGenerationAttempt attempt) {
    String json = attempt.getObservedInitialFailureEvidenceJson();
    if (json == null || json.isBlank()) return null;
    try {
      com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(json);
      String code = node.path("code").asText(null);
      return new RunDetail.WorkspaceGenerationInitialFailureEvidence(
          node.path("outcome").asText(null),
          code,
          customerSafeInitialFailureDetail(code),
          node.path("command").asText(null),
          List.of(),
          attempt.getInitialFailureObservedAt());
    } catch (Exception malformedHistoricalEvidence) {
      return null;
    }
  }

  private static RunDetail.WorkspaceGenerationAcceptanceCheck projectedCheck(
      TaskAcceptanceContract.AcceptanceCheck check, CheckResult result) {
    return new RunDetail.WorkspaceGenerationAcceptanceCheck(
        check.kind(),
        check.hidden() ? "hidden" : check.target(),
        check.hidden(),
        result == null ? null : result.outcome(),
        result == null || check.hidden() ? null : result.detail());
  }

  private static String customerSafeInitialFailureDetail(String code) {
    if (code == null) return null;
    return switch (code) {
      case "INITIAL_BUILD_FAILED" -> "The frozen build command failed before generation.";
      case "INITIAL_TESTS_FAILED" -> "The frozen test command failed before generation.";
      case "REQUIRED_PATH_MISSING" -> "A required contract path was absent before generation.";
      case "ENDPOINT_CONTRACT_UNSATISFIED" ->
          "The required endpoint contract was absent before generation.";
      case "INITIAL_COMMAND_CONTRACT_DRIFT" ->
          "The frozen command contract could not be reproduced before generation.";
      case "NO_INITIAL_FAILURE" ->
          "No deterministic initial failure was available for this generation attempt.";
      default -> "A deterministic initial failure was observed before generation.";
    };
  }

  private static List<RunDetail.WorkspaceGenerationAcceptanceCheck> acceptanceChecks(
      TaskAcceptanceContract contract, java.util.Map<String, CheckResult> results) {
    if (contract == null) return List.of();
    java.util.Map<String, TaskAcceptanceContract.AcceptanceCheck> definitions =
        new java.util.LinkedHashMap<>();
    java.util.stream.Stream.concat(
            contract.prefixChecks().stream(), contract.finalChecks().stream())
        .forEach(check -> definitions.put(check.kind(), check));
    results.forEach(
        (kind, ignored) ->
            definitions.putIfAbsent(
                kind, new TaskAcceptanceContract.AcceptanceCheck(kind, "contract", true)));
    return definitions.values().stream()
        .map(check -> projectedCheck(check, results.get(check.kind())))
        .toList();
  }

  private java.util.Map<String, CheckResult> checkResults(String evidenceJson) {
    if (evidenceJson == null || evidenceJson.isBlank()) return java.util.Map.of();
    try {
      com.fasterxml.jackson.databind.JsonNode rows =
          objectMapper.readTree(evidenceJson).path("acceptanceCheckResults");
      if (!rows.isArray()) return java.util.Map.of();
      java.util.Map<String, CheckResult> results = new java.util.LinkedHashMap<>();
      for (com.fasterxml.jackson.databind.JsonNode row : rows) {
        String kind = row.path("kind").asText(null);
        if (kind != null) {
          results.put(
              kind,
              new CheckResult(row.path("status").asText(null), row.path("detail").asText(null)));
        }
      }
      return java.util.Map.copyOf(results);
    } catch (Exception malformedHistoricalEvidence) {
      return java.util.Map.of();
    }
  }

  private record CheckResult(String outcome, String detail) {}

  private TaskAcceptanceContract contract(String json) {
    if (json == null || json.isBlank()) return null;
    try {
      return objectMapper.readValue(json, TaskAcceptanceContract.class);
    } catch (Exception malformedHistoricalEvidence) {
      return null;
    }
  }
}

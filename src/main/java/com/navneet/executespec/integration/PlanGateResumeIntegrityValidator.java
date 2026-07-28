package com.navneet.executespec.integration;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Objects;

import com.navneet.executespec.agent.requirement.ExactPlanFingerprint;
import com.navneet.executespec.agent.requirement.entity.RunRequirementContract;
import com.navneet.executespec.agent.requirement.repository.RunRequirementContractRepository;
import com.navneet.executespec.harness.entity.GateRecord;
import com.navneet.executespec.harness.entity.PlanGateResumeOutbox;
import com.navneet.executespec.state.entity.PipelineRun;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/** Validates the immutable cross-row identity before a plan-gate continuation can be delivered. */
@Component
@RequiredArgsConstructor
class PlanGateResumeIntegrityValidator {
  private final RunRequirementContractRepository requirementContractRepository;

  void validate(PipelineRun run, GateRecord gate, PlanGateResumeOutbox outbox) {
    validateContinuationIdentity(run, gate, outbox);
    validateAdmissionReceipt(run, gate, outbox);
  }

  private static void validateContinuationIdentity(
      PipelineRun run, GateRecord gate, PlanGateResumeOutbox outbox) {
    if (!Objects.equals(gate.getRunId(), run.getRunId())
        || !Objects.equals(gate.getGateKey(), outbox.getGateKey())
        || !Objects.equals(gate.getComment(), outbox.getPayload())
        || !Objects.equals(gate.getContinuationSha256(), outbox.getPayloadSha256())
        || outbox.getPayload() == null
        || !Objects.equals(
            outbox.getPayloadSha256(), DecisionEvidenceV1.sha256(outbox.getPayload()))
        || !Objects.equals(
            gate.getContinuationSchemaVersion(), outbox.getHandlerContractVersion())) {
      throw poison("PLAN_GATE_RESUME_IDENTITY_MISMATCH");
    }
    try {
      PlanGateContinuation continuation = PlanGateContinuation.parse(outbox.getPayload());
      if (outbox.getHandlerContractVersion() != PlanGateContinuation.SCHEMA_VERSION
          || continuation.schemaVersion() != outbox.getHandlerContractVersion()) {
        throw new IllegalArgumentException("unsupported continuation schema");
      }
    } catch (IllegalArgumentException invalid) {
      throw poison("PLAN_GATE_CONTINUATION_INVALID");
    }
    if (!"PLAN".equals(gate.getGateType()) || !"APPROVED".equals(gate.getStatus())) {
      throw poison("PLAN_GATE_NOT_APPROVED");
    }
  }

  private void validateAdmissionReceipt(
      PipelineRun run, GateRecord gate, PlanGateResumeOutbox outbox) {
    if (gate.getAdmissionContractVersion() == null
        || gate.getApprovalEvidenceJson() == null
        || gate.getApprovalEvidenceDigest() == null
        || gate.getAdmittedAt() == null) {
      throw poison("PLAN_GATE_ADMISSION_RECEIPT_INCOMPLETE");
    }
    if (!Objects.equals(gate.getAdmissionContractVersion(), DecisionEvidenceV1.SCHEMA_VERSION)) {
      throw poison("PLAN_GATE_RECEIPT_SCHEMA_UNSUPPORTED");
    }
    if (run.getPlanJson() == null || run.getPlanJson().isBlank()) {
      throw poison("PLAN_GATE_RESUME_PLAN_MISSING");
    }
    String currentPlanFingerprint = ExactPlanFingerprint.sha256(run.getPlanJson());
    if (gate.getBindingExactPlanFingerprint() != null
        && !Objects.equals(gate.getBindingExactPlanFingerprint(), currentPlanFingerprint)) {
      throw poison("PLAN_GATE_RESUME_PLAN_CHANGED");
    }
    if (!Objects.equals(
        gate.getApprovalEvidenceDigest(),
        PlanGateOpenService.sha256(gate.getApprovalEvidenceJson()))) {
      throw poison("PLAN_GATE_APPROVAL_EVIDENCE_DIGEST_MISMATCH");
    }
    try {
      DecisionEvidenceV1 evidence = DecisionEvidenceV1.parse(gate.getApprovalEvidenceJson());
      validateReceiptSchemaAndDecision(evidence, gate);
      validateReceiptCrossRow(evidence, run, gate, currentPlanFingerprint);
      validateReceiptIdentity(evidence, gate);
    } catch (PlanGateResumeStore.PoisonMessageException poison) {
      throw poison;
    } catch (Exception invalid) {
      throw poison("PLAN_GATE_APPROVAL_EVIDENCE_INVALID");
    }
  }

  private static void validateReceiptSchemaAndDecision(
      DecisionEvidenceV1 evidence, GateRecord gate) {
    if (evidence.schemaVersion() != gate.getAdmissionContractVersion()
        || evidence.schemaVersion() != DecisionEvidenceV1.SCHEMA_VERSION
        || !DecisionEvidenceV1.POLICY_CONTRACT.equals(evidence.policyContract())) {
      throw poison("PLAN_GATE_RECEIPT_SCHEMA_UNSUPPORTED");
    }
    if (!"APPROVE".equals(evidence.decision()) || !"APPROVE".equals(gate.getDecisionKind())) {
      throw poison("PLAN_GATE_RECEIPT_NOT_APPROVE");
    }
  }

  private static void validateReceiptCrossRow(
      DecisionEvidenceV1 evidence,
      PipelineRun run,
      GateRecord gate,
      String currentPlanFingerprint) {
    if (!Objects.equals(evidence.runId(), run.getRunId())
        || !Objects.equals(evidence.gateKey(), gate.getGateKey())
        || !Objects.equals(evidence.billingOwnerUserId(), gate.getBillingOwnerUserId())
        || !Objects.equals(evidence.billingOwnerUserId(), run.getCreatedByUserId())
        || !Objects.equals(evidence.billingOrgId(), gate.getBillingOrgId())
        || !Objects.equals(evidence.billingOrgId(), run.getOrgId())
        || !Objects.equals(evidence.executionMode(), normalizedExecutionMode(run))
        || !Objects.equals(evidence.provider(), normalizedProvider(run.getAiProvider()))
        || !Objects.equals(evidence.estimateHighUsd(), estimateHighUsd(run))
        || !Objects.equals(evidence.hardCapUsd(), hardCapUsd(run))
        || !Objects.equals(
            evidence.expectedCredits(),
            run.getCreditsCharged() == null ? 1 : run.getCreditsCharged())
        || (evidence.creditsConfirmed() == null && evidence.creditsMatched() != null)
        || (evidence.creditsConfirmed() != null
            && (!Objects.equals(evidence.creditsConfirmed(), evidence.expectedCredits())
                || !Boolean.TRUE.equals(evidence.creditsMatched())))
        || !Objects.equals(
            evidence.contractGateReportDigest(), gate.getContractGateReportDigest())) {
      throw poison("PLAN_GATE_RECEIPT_CROSS_ROW_MISMATCH");
    }
    if (!Objects.equals(evidence.reviewedPlanFingerprint(), currentPlanFingerprint)) {
      throw poison("PLAN_GATE_RECEIPT_PLAN_CHANGED");
    }
  }

  private void validateReceiptIdentity(DecisionEvidenceV1 evidence, GateRecord gate) {
    if (!Objects.equals(gate.getDecisionRequestFingerprint(), evidence.decisionRequestFingerprint())
        || !Objects.equals(gate.getResolvedActorType(), evidence.actorType())
        || !Objects.equals(gate.getResolvedAuthorizationSource(), evidence.authorizationBasis())
        || !Objects.equals(gate.getResolvedAuthorizationOrgId(), evidence.authorizationOrgId())
        || !Objects.equals(gate.getResolvedByUserId(), evidence.actorUserId())
        || !Objects.equals(gate.getResolvedByRole(), evidence.effectiveRole())
        || !Objects.equals(
            gate.getContinuationSchemaVersion(), evidence.continuationSchemaVersion())
        || !Objects.equals(gate.getContinuationSha256(), evidence.continuationSha256())
        || !requirementReceiptMatches(evidence, gate)) {
      throw poison("PLAN_GATE_RECEIPT_IDENTITY_MISMATCH");
    }
  }

  private boolean requirementReceiptMatches(DecisionEvidenceV1 evidence, GateRecord gate) {
    if (!Objects.equals(evidence.bindingMode(), gate.getBindingMode())
        || !Objects.equals(evidence.bindingStatus(), gate.getBindingStatus())
        || !Objects.equals(
            evidence.requirementContractId(),
            gate.getRequirementContractId() == null
                ? null
                : gate.getRequirementContractId().toString())
        || !Objects.equals(evidence.bindingSchemaVersion(), gate.getBindingSchemaVersion())
        || !Objects.equals(
            evidence.bindingCompilerFingerprint(), gate.getBindingCompilerFingerprint())
        || !Objects.equals(evidence.bindingSourceSha256(), gate.getBindingSourceSha256())
        || !Objects.equals(
            evidence.bindingExactPlanFingerprint(), gate.getBindingExactPlanFingerprint())
        || !Objects.equals(evidence.bindingArtifactDigest(), gate.getBindingArtifactDigest())
        || !Objects.equals(evidence.bindingBoundDigest(), gate.getBindingBoundDigest())
        || !Objects.equals(evidence.bindingReason(), gate.getBindingReason())) {
      return false;
    }
    if (gate.getRequirementContractId() == null) {
      return evidence.requirementCompleteness() == null
          && evidence.requirementCount() == null
          && evidence.acceptanceCriterionCount() == null
          && evidence.taskBindingCount() == null
          && evidence.issueCount() == null
          && evidence.blockingIssueCount() == null;
    }
    RunRequirementContract contract =
        requirementContractRepository.findById(gate.getRequirementContractId()).orElse(null);
    return contract != null
        && Objects.equals(contract.getRunId(), gate.getRunId())
        && Objects.equals(evidence.requirementCompleteness(), contract.getCompleteness().name())
        && Objects.equals(evidence.requirementCount(), contract.getRequirementCount())
        && Objects.equals(
            evidence.acceptanceCriterionCount(), contract.getAcceptanceCriterionCount())
        && Objects.equals(evidence.taskBindingCount(), contract.getTaskBindingCount())
        && Objects.equals(evidence.issueCount(), contract.getIssueCount())
        && Objects.equals(evidence.blockingIssueCount(), contract.getBlockingIssueCount());
  }

  private static String normalizedExecutionMode(PipelineRun run) {
    String value = run.getApprovedExecutionMode();
    return value == null || value.isBlank() ? "REALTIME" : value.trim().toUpperCase(Locale.ROOT);
  }

  private static String normalizedProvider(String value) {
    String provider =
        value == null || value.isBlank() ? "openai" : value.trim().toLowerCase(Locale.ROOT);
    return "google".equals(provider) ? "gemini" : provider;
  }

  private static String estimateHighUsd(PipelineRun run) {
    double value =
        run.getCostEstimateHigh() != null
            ? run.getCostEstimateHigh()
            : run.getHardCapUsd() == null ? 0.0 : run.getHardCapUsd();
    return BigDecimal.valueOf(value).setScale(6, java.math.RoundingMode.HALF_UP).toPlainString();
  }

  private static String hardCapUsd(PipelineRun run) {
    return run.getHardCapUsd() == null
        ? null
        : BigDecimal.valueOf(run.getHardCapUsd())
            .setScale(6, java.math.RoundingMode.HALF_UP)
            .toPlainString();
  }

  private static PlanGateResumeStore.PoisonMessageException poison(String reason) {
    return new PlanGateResumeStore.PoisonMessageException(reason);
  }
}

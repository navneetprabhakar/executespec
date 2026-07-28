package com.navneet.executespec.integration;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

import com.navneet.executespec.agent.requirement.ExactPlanFingerprint;
import com.navneet.executespec.agent.requirement.entity.RunRequirementContract;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.billing.service.ManagedAiUsageService;
import com.navneet.executespec.contracts.gate.PlanContractGateReport;
import com.navneet.executespec.governance.GovernanceGuard;
import com.navneet.executespec.harness.entity.GateRecord;
import com.navneet.executespec.state.entity.PipelineRun;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Canonical receipt construction and replay verification for atomic plan-gate decisions. */
final class PlanGateDecisionEvidence {
  private PlanGateDecisionEvidence() {}

  static PlanGateDecisionService.DecisionResult replayOrConflict(
      PipelineRun run,
      GateRecord gate,
      PlanGateDecisionService.DecisionKind requested,
      String actualPlanFingerprint,
      String requestFingerprint,
      PlanContractGateReport contractReport,
      RunRequirementContract requirementContract) {
    if (gate.getAdmissionContractVersion() == null || gate.getApprovalEvidenceJson() == null) {
      throw conflict("The existing plan decision has no versioned admission receipt.");
    }
    if (!Objects.equals(
        gate.getAdmissionContractVersion(), PlanGateDecisionService.ADMISSION_CONTRACT_VERSION)) {
      throw conflict("The existing plan decision receipt version is unsupported.");
    }
    String actualDigest = DecisionEvidenceV1.sha256(gate.getApprovalEvidenceJson());
    if (!actualDigest.equals(gate.getApprovalEvidenceDigest())) {
      throw conflict("The existing plan decision evidence failed digest verification.");
    }
    DecisionEvidenceV1 evidence;
    try {
      evidence = DecisionEvidenceV1.parse(gate.getApprovalEvidenceJson());
    } catch (IllegalArgumentException invalid) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "The existing plan decision evidence is invalid.", invalid);
    }
    if (evidence.schemaVersion() != gate.getAdmissionContractVersion()
        || evidence.schemaVersion() != DecisionEvidenceV1.SCHEMA_VERSION
        || !DecisionEvidenceV1.POLICY_CONTRACT.equals(evidence.policyContract())) {
      throw conflict("The existing plan decision receipt version is unsupported.");
    }
    if (!receiptIdentityMatches(
        evidence, run, gate, actualPlanFingerprint, contractReport, requirementContract)) {
      throw conflict("The existing plan decision evidence identity is inconsistent.");
    }
    if (!requested.name().equals(evidence.decision())) {
      if (requested == PlanGateDecisionService.DecisionKind.CANCEL
          && "APPROVE".equals(evidence.decision())) {
        return new PlanGateDecisionService.DecisionResult(
            run, PlanGateDecisionService.DecisionKind.APPROVE, evidence, true);
      }
      throw conflict("Plan gate was already resolved with the opposite decision.");
    }
    if (!Objects.equals(requestFingerprint, evidence.decisionRequestFingerprint())) {
      throw conflict("Plan gate was already resolved by a different decision request.");
    }
    return new PlanGateDecisionService.DecisionResult(run, requested, evidence, true);
  }

  private static boolean receiptIdentityMatches(
      DecisionEvidenceV1 evidence,
      PipelineRun run,
      GateRecord gate,
      String actualPlanFingerprint,
      PlanContractGateReport contractReport,
      RunRequirementContract requirementContract) {
    return Objects.equals(evidence.runId(), gate.getRunId())
        && Objects.equals(evidence.gateKey(), gate.getGateKey())
        && Objects.equals(evidence.reviewedPlanFingerprint(), actualPlanFingerprint)
        && Objects.equals(evidence.decision(), gate.getDecisionKind())
        && Objects.equals(evidence.actorType(), gate.getResolvedActorType())
        && Objects.equals(evidence.actorUserId(), gate.getResolvedByUserId())
        && Objects.equals(evidence.effectiveRole(), gate.getResolvedByRole())
        && Objects.equals(evidence.authorizationBasis(), gate.getResolvedAuthorizationSource())
        && Objects.equals(evidence.authorizationOrgId(), gate.getResolvedAuthorizationOrgId())
        && Objects.equals(
            evidence.decisionRequestFingerprint(), gate.getDecisionRequestFingerprint())
        && Objects.equals(evidence.continuationSchemaVersion(), gate.getContinuationSchemaVersion())
        && Objects.equals(evidence.continuationSha256(), gate.getContinuationSha256())
        && Objects.equals(evidence.billingOwnerUserId(), gate.getBillingOwnerUserId())
        && Objects.equals(evidence.billingOwnerUserId(), run.getCreatedByUserId())
        && Objects.equals(evidence.billingOrgId(), gate.getBillingOrgId())
        && Objects.equals(
            evidence.executionMode(), normalizedExecutionMode(run.getApprovedExecutionMode()))
        && Objects.equals(evidence.provider(), normalizedProvider(run.getAiProvider()))
        && Objects.equals(evidence.estimateHighUsd(), decimal(estimateHighUsd(run)))
        && Objects.equals(evidence.hardCapUsd(), decimal(run.getHardCapUsd()))
        && Objects.equals(evidence.expectedCredits(), expectedCredits(run))
        && validCreditReplay(evidence)
        && validCredentialReplay(evidence)
        && Objects.equals(evidence.contractGateReportDigest(), gate.getContractGateReportDigest())
        && Objects.equals(
            evidence.contractGateReportDigest(),
            contractReport == null ? null : contractReport.getReportDigest())
        && contractEvidenceMatches(evidence, contractReport)
        && requirementEvidenceMatches(evidence, gate, requirementContract);
  }

  private static boolean validCreditReplay(DecisionEvidenceV1 evidence) {
    if (evidence.creditsConfirmed() == null) return evidence.creditsMatched() == null;
    return Objects.equals(evidence.creditsConfirmed(), evidence.expectedCredits())
        && Boolean.TRUE.equals(evidence.creditsMatched());
  }

  private static boolean validCredentialReplay(DecisionEvidenceV1 evidence) {
    if ("APPROVE".equals(evidence.decision())) {
      return Objects.equals(evidence.credentialAuthorizationOrgId(), evidence.billingOrgId())
          && evidence.credentialAuthorizationSource() != null
          && (evidence.suppliedPlanFingerprint() == null
              || Boolean.TRUE.equals(evidence.planFingerprintMatched()));
    }
    return evidence.credentialAuthorizationOrgId() == null
        && evidence.credentialAuthorizationSource() == null;
  }

  static DecisionEvidenceV1 create(
      PipelineRun run,
      GateRecord gate,
      PlanGateDecisionService.DecisionKind kind,
      PlanApprovalService.Command command,
      ActorEvidence actor,
      AdmissionEvidence admission,
      PlanContractGateReport contractReport,
      RunRequirementContract requirementContract,
      String actualPlanFingerprint,
      String suppliedPlanFingerprint,
      Boolean fingerprintMatches,
      String requestFingerprint,
      String executionMode,
      String resolutionSource,
      String resolutionReason,
      Instant now) {
    return new DecisionEvidenceV1(
        DecisionEvidenceV1.SCHEMA_VERSION,
        DecisionEvidenceV1.POLICY_CONTRACT,
        kind.name(),
        now.toString(),
        run.getRunId(),
        gate.getGateKey(),
        resolutionSource,
        resolutionReason,
        actor.type(),
        actor.userId(),
        actor.globalRole(),
        actor.orgRole(),
        actor.effectiveRole(),
        actor.authorizationBasis(),
        actor.authorizationOrgId(),
        actualPlanFingerprint,
        suppliedPlanFingerprint,
        fingerprintMatches,
        requestFingerprint,
        gate.getContinuationSchemaVersion(),
        gate.getContinuationSha256(),
        gate.getBindingMode(),
        gate.getBindingStatus(),
        gate.getRequirementContractId() == null ? null : gate.getRequirementContractId().toString(),
        gate.getBindingSchemaVersion(),
        gate.getBindingCompilerFingerprint(),
        gate.getBindingSourceSha256(),
        gate.getBindingExactPlanFingerprint(),
        gate.getBindingArtifactDigest(),
        gate.getBindingBoundDigest(),
        gate.getBindingReason(),
        requirementContract == null ? null : requirementContract.getCompleteness().name(),
        requirementContract == null ? null : requirementContract.getRequirementCount(),
        requirementContract == null ? null : requirementContract.getAcceptanceCriterionCount(),
        requirementContract == null ? null : requirementContract.getTaskBindingCount(),
        requirementContract == null ? null : requirementContract.getIssueCount(),
        requirementContract == null ? null : requirementContract.getBlockingIssueCount(),
        executionMode,
        decimal(estimateHighUsd(run)),
        decimal(run.getHardCapUsd()),
        admission.credits().expected(),
        command.creditsConfirmed(),
        admission.credits().matched(),
        run.getCreatedByUserId(),
        kind == PlanGateDecisionService.DecisionKind.APPROVE
            ? admission.credentialAuthorizationOrgId()
            : run.getOrgId(),
        admission.provider(),
        admission.keySourceMode(),
        admission.credentialOrigin(),
        admission.credentialAuthorizationSource(),
        admission.credentialAuthorizationOrgId(),
        admission.ownKeyExempt(),
        admission.managedAi().outcome(),
        admission.managedAi().mode(),
        admission.managedAi().grantCredits(),
        admission.managedAi().remainingCredits(),
        admission.managedAi().estimatedCredits(),
        decimal(admission.managedAi().hardRawUsd()),
        decimal(admission.managedAi().remainingRawUsd()),
        admission.orgGovernance().outcome(),
        decimal(admission.orgGovernance().thresholdUsd()),
        decimal(admission.orgGovernance().estimateUsd()),
        admission.orgGovernance().reason(),
        admission.projectGovernance().outcome(),
        decimal(admission.projectGovernance().capUsd()),
        decimal(admission.projectGovernance().monthToDateUsd()),
        decimal(admission.projectGovernance().projectedUsd()),
        admission.projectGovernance().reason(),
        gate.getContractGateReportDigest(),
        contractReport == null ? null : contractReport.isRequiresTypedAck(),
        contractReport == null || contractReport.getAcknowledgedAt() == null
            ? null
            : contractReport.getAcknowledgedAt().toString(),
        contractReport == null ? null : contractReport.getAcknowledgedByUserId(),
        contractReport == null ? null : contractReport.getAcknowledgementText(),
        admission.contractAcknowledgementOutcome());
  }

  static ActorEvidence actorEvidence(
      AppUserDetails actor, PlanGateDecisionService.DecisionKind kind, PipelineRun run) {
    if (actor == null || actor.getUser() == null) {
      if (kind != PlanGateDecisionService.DecisionKind.CANCEL) {
        throw new ResponseStatusException(
            HttpStatus.FORBIDDEN, "A human actor is required for this plan decision.");
      }
      return new ActorEvidence(
          "SYSTEM", null, null, null, "SYSTEM", "SYSTEM_INTERNAL", run.getOrgId());
    }
    String globalRole = trimToNull(actor.getUser().getGlobalRole());
    String orgRole =
        actor.getOrgMember() == null ? null : trimToNull(actor.getOrgMember().getOrgRole());
    String effectiveRole;
    String authorizationBasis;
    String authorizationOrgId;
    if ("SUPER_ADMIN".equals(globalRole)) {
      effectiveRole = globalRole;
      authorizationBasis = "GLOBAL_SUPER_ADMIN";
      authorizationOrgId = null;
    } else if (Objects.equals(actor.getUser().getId(), run.getCreatedByUserId())) {
      effectiveRole = normalized(orgRole, globalRole);
      authorizationBasis = "RUN_OWNER";
      authorizationOrgId = null;
    } else if (actor.getOrgMember() != null
        && "ORG_ADMIN".equals(orgRole)
        && Objects.equals(actor.getOrgMember().getOrgId(), run.getOrgId())) {
      effectiveRole = orgRole;
      authorizationBasis = "ORG_ADMIN";
      authorizationOrgId = run.getOrgId();
    } else {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN,
          "The human actor has no run-owner, same-org admin, or global approval authority.");
    }
    if (effectiveRole == null || effectiveRole.isBlank()) effectiveRole = authorizationBasis;
    return new ActorEvidence(
        "HUMAN",
        actor.getUser().getId(),
        globalRole,
        orgRole,
        effectiveRole,
        authorizationBasis,
        authorizationOrgId);
  }

  static String requireExactPlan(PipelineRun run) {
    if (run.getPlanJson() == null || run.getPlanJson().isBlank()) {
      throw conflict("The persisted plan is missing; approval cannot continue.");
    }
    return ExactPlanFingerprint.sha256(run.getPlanJson());
  }

  static CreditEvidence validateCredits(PipelineRun run, Integer confirmed) {
    int expected = expectedCredits(run);
    if (confirmed == null) return new CreditEvidence(expected, null);
    if (!confirmed.equals(expected)) {
      throw conflict(
          "Credits mismatch: expected "
              + expected
              + " but confirmed "
              + confirmed
              + ". Cost estimate may have changed — please review.");
    }
    return new CreditEvidence(expected, true);
  }

  static int expectedCredits(PipelineRun run) {
    return run.getCreditsCharged() == null ? 1 : run.getCreditsCharged();
  }

  static String decisionRequestFingerprint(
      PlanGateDecisionService.DecisionKind kind,
      PlanApprovalService.Command command,
      AppUserDetails actor,
      String actualPlanFingerprint,
      String source,
      String reason,
      String executionMode) {
    String actorId =
        actor == null || actor.getUser() == null ? null : trimToNull(actor.getUser().getId());
    return new DecisionRequestBytesV1(
            DecisionRequestBytesV1.SCHEMA_VERSION,
            kind.name(),
            actorId,
            source,
            reason,
            executionMode,
            command.creditsConfirmed(),
            actualPlanFingerprint,
            normalizeSha(command.planFingerprint()),
            normalizeAck(command.breakingChangeAck()),
            normalizeSha(command.breakingChangeReportDigest()))
        .sha256();
  }

  private static boolean requirementEvidenceMatches(
      DecisionEvidenceV1 evidence, GateRecord gate, RunRequirementContract contract) {
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
    if (gate.getBindingExactPlanFingerprint() != null
        && !Objects.equals(
            evidence.reviewedPlanFingerprint(), gate.getBindingExactPlanFingerprint())) {
      return false;
    }
    if (contract == null) {
      return evidence.requirementCompleteness() == null
          && evidence.requirementCount() == null
          && evidence.acceptanceCriterionCount() == null
          && evidence.taskBindingCount() == null
          && evidence.issueCount() == null
          && evidence.blockingIssueCount() == null;
    }
    return Objects.equals(evidence.requirementCompleteness(), contract.getCompleteness().name())
        && Objects.equals(evidence.requirementCount(), contract.getRequirementCount())
        && Objects.equals(
            evidence.acceptanceCriterionCount(), contract.getAcceptanceCriterionCount())
        && Objects.equals(evidence.taskBindingCount(), contract.getTaskBindingCount())
        && Objects.equals(evidence.issueCount(), contract.getIssueCount())
        && Objects.equals(evidence.blockingIssueCount(), contract.getBlockingIssueCount());
  }

  private static boolean contractEvidenceMatches(
      DecisionEvidenceV1 evidence, PlanContractGateReport report) {
    return Objects.equals(
            evidence.contractRequiresTypedAck(),
            report == null ? null : report.isRequiresTypedAck())
        && Objects.equals(
            evidence.contractAcknowledgedAt(),
            report == null || report.getAcknowledgedAt() == null
                ? null
                : report.getAcknowledgedAt().toString())
        && Objects.equals(
            evidence.contractAcknowledgedByUserId(),
            report == null ? null : report.getAcknowledgedByUserId())
        && Objects.equals(
            evidence.contractAcknowledgementText(),
            report == null ? null : report.getAcknowledgementText());
  }

  static double estimateHighUsd(PipelineRun run) {
    if (run.getCostEstimateHigh() != null) return run.getCostEstimateHigh();
    return run.getHardCapUsd() == null ? 0.0 : run.getHardCapUsd();
  }

  static String decimal(double value) {
    return BigDecimal.valueOf(value).setScale(6, java.math.RoundingMode.HALF_UP).toPlainString();
  }

  static String decimal(BigDecimal value) {
    return value == null ? null : value.setScale(6, java.math.RoundingMode.HALF_UP).toPlainString();
  }

  static String decimal(Double value) {
    return value == null ? null : decimal(value.doubleValue());
  }

  static String normalizedExecutionMode(String value) {
    return bounded(normalized(value, "REALTIME").toUpperCase(Locale.ROOT), 16, "execution mode");
  }

  static String normalizedProvider(String value) {
    String provider = normalized(value, "openai").toLowerCase(Locale.ROOT);
    return "google".equals(provider) ? "gemini" : provider;
  }

  static String normalizeSha(String value) {
    String trimmed = trimToNull(value);
    return trimmed == null ? null : trimmed.toLowerCase(Locale.ROOT);
  }

  static String normalizeAck(String value) {
    String trimmed = trimToNull(value);
    return trimmed == null ? null : trimmed.toUpperCase(Locale.ROOT);
  }

  static String normalized(String value, String fallback) {
    String trimmed = trimToNull(value);
    return trimmed == null ? fallback : trimmed;
  }

  static String trimToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  static String bounded(String value, int maxLength, String field) {
    if (value != null && value.length() > maxLength) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, field + " exceeds " + maxLength + " characters.");
    }
    return value;
  }

  static AdmissionEvidence notEvaluated(PipelineRun run) {
    return new AdmissionEvidence(
        normalizedProvider(run.getAiProvider()),
        null,
        null,
        null,
        null,
        null,
        new CreditEvidence(expectedCredits(run), null),
        new ManagedAiUsageService.ApprovalEvaluation(
            "NOT_EVALUATED", "NOT_EVALUATED", null, null, null, null, null),
        new GovernanceGuard.OrgCostEvaluation("NOT_EVALUATED", null, null, "NOT_EVALUATED"),
        new GovernanceGuard.ProjectSpendEvaluation(
            "NOT_EVALUATED", null, null, null, "NOT_EVALUATED"),
        "NOT_EVALUATED");
  }

  private static ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT, message);
  }

  record ActorEvidence(
      String type,
      String userId,
      String globalRole,
      String orgRole,
      String effectiveRole,
      String authorizationBasis,
      String authorizationOrgId) {}

  record AdmissionEvidence(
      String provider,
      String keySourceMode,
      String credentialOrigin,
      String credentialAuthorizationSource,
      String credentialAuthorizationOrgId,
      Boolean ownKeyExempt,
      CreditEvidence credits,
      ManagedAiUsageService.ApprovalEvaluation managedAi,
      GovernanceGuard.OrgCostEvaluation orgGovernance,
      GovernanceGuard.ProjectSpendEvaluation projectGovernance,
      String contractAcknowledgementOutcome) {}

  record CreditEvidence(int expected, Boolean matched) {}
}

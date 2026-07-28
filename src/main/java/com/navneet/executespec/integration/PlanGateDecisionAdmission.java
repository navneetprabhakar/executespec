package com.navneet.executespec.integration;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

import com.navneet.executespec.agent.ApiKeyResolver;
import com.navneet.executespec.agent.requirement.entity.RunRequirementContract;
import com.navneet.executespec.agent.requirement.repository.RunRequirementContractRepository;
import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.Org;
import com.navneet.executespec.auth.entity.OrgMember;
import com.navneet.executespec.auth.entity.Project;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.billing.service.ManagedAiUsageService;
import com.navneet.executespec.contracts.gate.PlanContractGateReport;
import com.navneet.executespec.contracts.gate.PlanContractGateService;
import com.navneet.executespec.governance.GovernanceGuard;
import com.navneet.executespec.harness.entity.GateRecord;
import com.navneet.executespec.state.entity.PipelineRun;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Locked policy evaluation used inside {@link PlanGateDecisionService}'s transaction. */
final class PlanGateDecisionAdmission {
  private PlanGateDecisionAdmission() {}

  static PlanGateDecisionEvidence.AdmissionEvidence admitApproval(
      PipelineRun run,
      PlanApprovalService.Command command,
      AppUserDetails actor,
      String actorUserId,
      PlanContractGateReport contractReport,
      Instant now,
      ApiKeyResolver apiKeyResolver,
      ManagedAiUsageService managedAiUsageService,
      GovernanceGuard governanceGuard) {
    PlanGateDecisionEvidence.CreditEvidence credits =
        PlanGateDecisionEvidence.validateCredits(run, command.creditsConfirmed());
    PlanContractGateService.AckOutcome acknowledgement =
        acknowledgeLocked(
            contractReport,
            command.breakingChangeAck(),
            command.breakingChangeReportDigest(),
            actorUserId,
            now);
    switch (acknowledgement) {
      case MISSING ->
          throw conflict(
              "This plan changes a contract that other repositories consume. Review the"
                  + " breaking-change report and type BREAKING to acknowledge it before"
                  + " approving.");
      case STALE_REPORT ->
          throw conflict(
              "The breaking-change report you acknowledged is not the one currently on this run."
                  + " Reload the plan and review the change again before approving.");
      default -> {
        // NOT_REQUIRED or ACKNOWLEDGED. A later denial rolls this mutation back with the receipt.
      }
    }
    double estimateHighUsd = PlanGateDecisionEvidence.estimateHighUsd(run);
    String billingUserId = PlanGateDecisionEvidence.trimToNull(run.getCreatedByUserId());
    if (billingUserId == null) {
      throw conflict(
          "Run has no billing owner; plan approval cannot safely authorize provider spend.");
    }
    String provider = PlanGateDecisionEvidence.normalizedProvider(run.getAiProvider());
    ApiKeyResolver.CredentialResolution resolution =
        apiKeyResolver.resolveWithProvenance(billingUserId);
    if (resolution == null || resolution.keys() == null) {
      throw conflict(
          "Run credential policy is unavailable; plan approval cannot safely authorize provider"
              + " spend.");
    }
    if (!Objects.equals(
        PlanGateDecisionEvidence.trimToNull(resolution.orgId()),
        PlanGateDecisionEvidence.trimToNull(run.getOrgId()))) {
      throw conflict(
          "The billing owner's credential authorization org does not match the run org.");
    }
    String keySourceMode =
        PlanGateDecisionEvidence.normalized(resolution.keys().keySourceMode(), "HYBRID")
            .toUpperCase(Locale.ROOT);
    ApiKeyResolver.CredentialOrigin origin = resolution.originFor(provider);
    boolean ownKeyExempt = "OWN_KEYS".equals(keySourceMode);
    if (ownKeyExempt && (!resolution.keys().isProviderAllowed(provider) || origin == null)) {
      throw conflict(
          "This run uses "
              + provider
              + ", but the OWN_KEYS billing owner has no enabled customer key for that provider."
              + " Add the provider key or choose a keyed provider before approving.");
    }

    ManagedAiUsageService.ApprovalEvaluation managedAi =
        managedAiUsageService.assertCanApproveEstimatedSpend(
            billingUserId, run.getOrgId(), ownKeyExempt, estimateHighUsd);
    GovernanceGuard.OrgCostEvaluation orgGovernance =
        governanceGuard.evaluateApprovalCost(run.getOrgId(), estimateHighUsd, actor);
    GovernanceGuard.ProjectSpendEvaluation projectGovernance =
        governanceGuard.evaluateProjectSpendCap(run.getProjectId(), estimateHighUsd);

    String credentialOrigin = origin == null ? "SYSTEM" : origin.name();
    String credentialAuthorizationSource =
        origin == null ? "SYSTEM_PLATFORM_KEY" : origin.name() + "_CUSTOMER_KEY";
    return new PlanGateDecisionEvidence.AdmissionEvidence(
        provider,
        keySourceMode,
        credentialOrigin,
        credentialAuthorizationSource,
        resolution.orgId(),
        ownKeyExempt,
        credits,
        managedAi,
        orgGovernance,
        projectGovernance,
        acknowledgement.name());
  }

  static PlanContractGateReport lockContractReport(EntityManager entityManager, String runId) {
    return entityManager.find(PlanContractGateReport.class, runId, LockModeType.PESSIMISTIC_WRITE);
  }

  static void verifyContractReportBinding(GateRecord gate, PlanContractGateReport report) {
    String reportDigest = report == null ? null : report.getReportDigest();
    if (!Objects.equals(gate.getContractGateReportDigest(), reportDigest)) {
      throw conflict("The plan gate is not bound to the current breaking-change report.");
    }
    if (report != null
        && !Objects.equals(
            DecisionEvidenceV1.sha256(report.getReportJson()), report.getReportDigest())) {
      throw conflict("The persisted breaking-change report failed digest verification.");
    }
  }

  static RunRequirementContract lockRequirementContract(
      RunRequirementContractRepository repository, GateRecord gate) {
    if (gate.getRequirementContractId() == null) return null;
    RunRequirementContract contract =
        repository
            .findByIdForUpdate(gate.getRequirementContractId())
            .orElseThrow(() -> conflict("The bound RequirementIR artifact is missing."));
    boolean exact =
        Objects.equals(contract.getRunId(), gate.getRunId())
            && Objects.equals(contract.getSchemaVersion(), gate.getBindingSchemaVersion())
            && Objects.equals(
                contract.getCompilerFingerprint(), gate.getBindingCompilerFingerprint())
            && Objects.equals(contract.getSourceSha256(), gate.getBindingSourceSha256())
            && Objects.equals(
                contract.getExactPlanFingerprint(), gate.getBindingExactPlanFingerprint())
            && Objects.equals(contract.getArtifactDigest(), gate.getBindingArtifactDigest())
            && Objects.equals(contract.getBoundDigest(), gate.getBindingBoundDigest());
    if (!exact) {
      throw conflict("The bound RequirementIR identity does not match the plan gate.");
    }
    return contract;
  }

  /** Locks mutable policy rows after run/gate/outbox/report/RequirementIR in the fixed order. */
  static AppUserDetails lockPolicyRows(
      EntityManager entityManager,
      PipelineRun run,
      AppUserDetails suppliedActor,
      PlanGateDecisionService.DecisionKind kind) {
    if (suppliedActor == null || suppliedActor.getUser() == null) {
      if (kind == PlanGateDecisionService.DecisionKind.CANCEL) return null;
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN, "A human actor is required for this plan decision.");
    }

    String actorId = PlanGateDecisionEvidence.trimToNull(suppliedActor.getUser().getId());
    if (actorId == null) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN, "The plan-decision actor has no durable identity.");
    }
    String billingOwnerId = PlanGateDecisionEvidence.trimToNull(run.getCreatedByUserId());
    java.util.List<String> userIds =
        java.util.stream.Stream.of(
                actorId,
                kind == PlanGateDecisionService.DecisionKind.APPROVE ? billingOwnerId : null)
            .filter(Objects::nonNull)
            .distinct()
            .sorted()
            .toList();
    java.util.Map<String, AppUser> lockedUsers = new java.util.HashMap<>();
    for (String userId : userIds) {
      AppUser locked = entityManager.find(AppUser.class, userId, LockModeType.PESSIMISTIC_WRITE);
      if (locked == null) throw conflict("A plan-decision identity row is missing.");
      lockedUsers.put(userId, locked);
    }

    if (kind == PlanGateDecisionService.DecisionKind.APPROVE && run.getOrgId() != null) {
      if (entityManager.find(Org.class, run.getOrgId(), LockModeType.PESSIMISTIC_WRITE) == null) {
        throw conflict("The run's billing organization is missing.");
      }
    }
    if (kind == PlanGateDecisionService.DecisionKind.APPROVE && run.getProjectId() != null) {
      if (entityManager.find(Project.class, run.getProjectId(), LockModeType.PESSIMISTIC_WRITE)
          == null) {
        throw conflict("The run's governance project is missing.");
      }
    }

    OrgMember lockedMembership = null;
    if (!"SUPER_ADMIN".equals(lockedUsers.get(actorId).getGlobalRole())
        && suppliedActor.getOrgMember() != null
        && PlanGateDecisionEvidence.trimToNull(suppliedActor.getOrgMember().getId()) != null) {
      lockedMembership =
          entityManager.find(
              OrgMember.class,
              suppliedActor.getOrgMember().getId(),
              LockModeType.PESSIMISTIC_WRITE);
      if (lockedMembership != null
          && (!Objects.equals(lockedMembership.getUserId(), actorId)
              || !Objects.equals(lockedMembership.getOrgId(), run.getOrgId()))) {
        throw new ResponseStatusException(
            HttpStatus.FORBIDDEN, "The actor's organization authority is inconsistent.");
      }
    }
    return new AppUserDetails(lockedUsers.get(actorId), lockedMembership);
  }

  private static PlanContractGateService.AckOutcome acknowledgeLocked(
      PlanContractGateReport report,
      String ackText,
      String suppliedDigest,
      String actorUserId,
      Instant now) {
    if (report == null || !report.isRequiresTypedAck()) {
      return PlanContractGateService.AckOutcome.NOT_REQUIRED;
    }
    if (!Objects.equals(
        DecisionEvidenceV1.sha256(report.getReportJson()), report.getReportDigest())) {
      throw conflict("The persisted breaking-change report failed digest verification.");
    }
    if (ackText == null
        || !PlanContractGateService.REQUIRED_ACK_TEXT.equalsIgnoreCase(ackText.trim())) {
      return PlanContractGateService.AckOutcome.MISSING;
    }
    if (suppliedDigest == null
        || !report.getReportDigest().equalsIgnoreCase(suppliedDigest.trim())) {
      return PlanContractGateService.AckOutcome.STALE_REPORT;
    }
    if (report.getAcknowledgedAt() != null) {
      boolean sameActor = Objects.equals(report.getAcknowledgedByUserId(), actorUserId);
      boolean sameText =
          report.getAcknowledgementText() != null
              && PlanContractGateService.REQUIRED_ACK_TEXT.equalsIgnoreCase(
                  report.getAcknowledgementText().trim());
      return sameActor && sameText
          ? PlanContractGateService.AckOutcome.ACKNOWLEDGED
          : PlanContractGateService.AckOutcome.STALE_REPORT;
    }
    report.setAcknowledgedAt(now);
    report.setAcknowledgedByUserId(actorUserId);
    report.setAcknowledgementText(PlanContractGateService.REQUIRED_ACK_TEXT);
    return PlanContractGateService.AckOutcome.ACKNOWLEDGED;
  }

  private static ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT, message);
  }
}

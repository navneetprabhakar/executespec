package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agent.ApiKeyResolver;
import com.navneet.executespec.agent.ResolvedKeys;
import com.navneet.executespec.agent.requirement.ExactPlanFingerprint;
import com.navneet.executespec.agent.requirement.RequirementIrApprovalCompatibilityRegistry;
import com.navneet.executespec.agent.requirement.entity.RunRequirementContract;
import com.navneet.executespec.agent.requirement.repository.RunRequirementContractRepository;
import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.Org;
import com.navneet.executespec.auth.entity.Project;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.billing.service.ManagedAiUsageService;
import com.navneet.executespec.contracts.gate.PlanContractGateReport;
import com.navneet.executespec.governance.GovernanceGuard;
import com.navneet.executespec.harness.entity.GateRecord;
import com.navneet.executespec.harness.entity.PlanGateResumeOutbox;
import com.navneet.executespec.harness.repository.GateRepository;
import com.navneet.executespec.harness.repository.PlanGateResumeOutboxRepository;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class PlanGateDecisionServiceTest {
  @Mock PipelineRunRepository runRepository;
  @Mock GateRepository gateRepository;
  @Mock PlanGateResumeOutboxRepository outboxRepository;
  @Mock RunRequirementContractRepository requirementRepository;
  @Mock EntityManager entityManager;
  @Mock ApiKeyResolver apiKeyResolver;
  @Mock ManagedAiUsageService managedAiUsageService;
  @Mock GovernanceGuard governanceGuard;
  @Mock ApplicationEventPublisher events;

  private PlanGateDecisionService service;
  private PipelineRun run;
  private GateRecord gate;
  private PlanGateResumeOutbox outbox;
  private AppUserDetails actor;
  private final RequirementIrApprovalCompatibilityRegistry requirementIrCompatibilityRegistry =
      new RequirementIrApprovalCompatibilityRegistry(new ObjectMapper());

  @BeforeEach
  void setUp() {
    service =
        new PlanGateDecisionService(
            runRepository,
            gateRepository,
            outboxRepository,
            requirementRepository,
            requirementIrCompatibilityRegistry,
            entityManager,
            apiKeyResolver,
            managedAiUsageService,
            governanceGuard,
            events);
    run =
        PipelineRun.builder()
            .runId("run-1")
            .status("AWAITING_APPROVAL")
            .currentGate("PLAN_APPROVAL")
            .createdByUserId("owner-1")
            .orgId("org-1")
            .projectId("project-1")
            .aiProvider("anthropic")
            .planJson("{\"tasks\":[]}")
            .creditsCharged(3)
            .costEstimateHigh(4.5)
            .hardCapUsd(6.25)
            .build();
    String payload =
        "{\"schemaVersion\":1,\"localRun\":false,"
            + "\"requireContinuationConfirmation\":false,"
            + "\"requireContinuationBenchmarkPrior\":false,"
            + "\"pinnedModel\":null,\"lowComplexityWorkerStartTierOverride\":null}";
    String payloadSha = DecisionEvidenceV1.sha256(payload);
    gate = GateRecord.pending("gate:plan:run-1", "run-1", "PLAN");
    gate.setComment(payload);
    gate.setContinuationSchemaVersion(1);
    gate.setContinuationSha256(payloadSha);
    gate.setBindingMode("LEGACY");
    gate.setBindingStatus("UNBOUND");
    outbox =
        PlanGateResumeOutbox.builder()
            .runId("run-1")
            .gateKey(gate.getGateKey())
            .payload(payload)
            .payloadSha256(payloadSha)
            .handlerContractVersion(1)
            .status("WAITING")
            .maxAttempts(8)
            .nextRetryAt(Instant.now())
            .build();
    actor = new AppUserDetails(AppUser.builder().id("owner-1").globalRole("USER").build(), null);

    lenient().when(runRepository.findByIdForPlanGateOpen("run-1")).thenReturn(Optional.of(run));
    lenient()
        .when(gateRepository.findByIdForPlanGateOpen("gate:plan:run-1"))
        .thenReturn(Optional.of(gate));
    lenient().when(outboxRepository.findByIdForUpdate("run-1")).thenReturn(Optional.of(outbox));
    lenient()
        .when(entityManager.find(AppUser.class, "owner-1", LockModeType.PESSIMISTIC_WRITE))
        .thenReturn(actor.getUser());
    lenient()
        .when(entityManager.find(Org.class, "org-1", LockModeType.PESSIMISTIC_WRITE))
        .thenReturn(Org.builder().id("org-1").build());
    lenient()
        .when(entityManager.find(Project.class, "project-1", LockModeType.PESSIMISTIC_WRITE))
        .thenReturn(Project.builder().id("project-1").orgId("org-1").build());
  }

  @Test
  void approvalPersistsReceiptAndReadyIntentThenReplaySkipsEveryPolicy() {
    stubAllowedAdmission();
    var command = new PlanApprovalService.Command(true, 3, "REALTIME", null, null, null);

    var first = service.decide("run-1", command, actor);
    var replay = service.decide("run-1", command, actor);

    assertThat(first.replayed()).isFalse();
    assertThat(replay.replayed()).isTrue();
    assertThat(replay.evidence().canonicalJson()).isEqualTo(gate.getApprovalEvidenceJson());
    assertThat(gate.getDecisionKind()).isEqualTo("APPROVE");
    assertThat(gate.getResolvedActorType()).isEqualTo("HUMAN");
    assertThat(gate.getResolvedAuthorizationSource()).isEqualTo("RUN_OWNER");
    assertThat(gate.getResolvedAuthorizationOrgId()).isNull();
    assertThat(gate.getDecisionRequestFingerprint()).hasSize(64);
    assertThat(gate.getAdmissionContractVersion()).isEqualTo(DecisionEvidenceV1.SCHEMA_VERSION);
    assertThat(first.evidence().schemaVersion()).isEqualTo(gate.getAdmissionContractVersion());
    assertThat(gate.getStatus()).isEqualTo("APPROVED");
    assertThat(outbox.getStatus()).isEqualTo("READY");
    assertThat(run.getStatus()).isEqualTo("APPROVAL_RESUMING");
    assertThat(first.evidence().estimateHighUsd()).isEqualTo("4.500000");
    assertThat(first.evidence().hardCapUsd()).isEqualTo("6.250000");
    assertThat(first.evidence().suppliedPlanFingerprint()).isNull();
    assertThat(first.evidence().planFingerprintMatched()).isNull();
    assertThat(first.evidence().expectedCredits()).isEqualTo(3);
    assertThat(first.evidence().creditsConfirmed()).isEqualTo(3);
    assertThat(first.evidence().creditsMatched()).isTrue();
    assertThat(first.evidence().managedAiCreditsRemaining()).isEqualTo(1900L);
    verify(apiKeyResolver, times(1)).resolveWithProvenance("owner-1");
    verify(managedAiUsageService, times(1))
        .assertCanApproveEstimatedSpend("owner-1", "org-1", false, 4.5);
    verify(governanceGuard, times(1))
        .evaluateApprovalCost(eq("org-1"), eq(4.5), any(AppUserDetails.class));
    verify(events, times(1)).publishEvent(new PlanGateResumeReadyCommitted("run-1"));
  }

  @Test
  void enforcedApprovalRequiresSuppliedExactPlanFingerprint() {
    gate.setBindingMode("ENFORCED");

    assertThatThrownBy(
            () ->
                service.decide(
                    "run-1",
                    new PlanApprovalService.Command(true, 3, "REALTIME", null, null, null),
                    actor))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            error -> {
              assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
              assertThat(error.getReason()).contains("requires the exact plan fingerprint");
            });

    verifyNoInteractions(apiKeyResolver, managedAiUsageService, governanceGuard, events);
    assertThat(gate.getStatus()).isEqualTo("PENDING");
  }

  @Test
  void rejectionRemainsCompatibleWithoutFingerprintInEnforcedMode() {
    gate.setBindingMode("ENFORCED");

    var result = service.decide("run-1", PlanApprovalService.Command.rejectLegacy(), actor);

    assertThat(result.decision()).isEqualTo(PlanGateDecisionService.DecisionKind.REJECT);
    assertThat(result.evidence().suppliedPlanFingerprint()).isNull();
    assertThat(gate.getStatus()).isEqualTo("REJECTED");
  }

  @Test
  void decisionFailsClosedWhenBoundRequirementIrPairHasNoRetainedAdapter() {
    UUID contractId = UUID.randomUUID();
    RunRequirementContract contract =
        RunRequirementContract.builder()
            .id(contractId)
            .runId("run-1")
            .schemaVersion(2)
            .compilerFingerprint("f".repeat(64))
            .sourceSha256("s".repeat(64))
            .exactPlanFingerprint(ExactPlanFingerprint.sha256(run.getPlanJson()))
            .artifactDigest("a".repeat(64))
            .boundDigest("b".repeat(64))
            .build();
    gate.setBindingMode("ENFORCED");
    gate.setRequirementContractId(contractId);
    gate.setBindingSchemaVersion(2);
    gate.setBindingCompilerFingerprint(contract.getCompilerFingerprint());
    gate.setBindingSourceSha256(contract.getSourceSha256());
    gate.setBindingExactPlanFingerprint(contract.getExactPlanFingerprint());
    gate.setBindingArtifactDigest(contract.getArtifactDigest());
    gate.setBindingBoundDigest(contract.getBoundDigest());
    when(requirementRepository.findByIdForUpdate(contractId)).thenReturn(Optional.of(contract));
    assertThatThrownBy(
            () ->
                service.decide(
                    "run-1",
                    new PlanApprovalService.Command(
                        true,
                        3,
                        "REALTIME",
                        ExactPlanFingerprint.sha256(run.getPlanJson()),
                        null,
                        null),
                    actor))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            error -> {
              assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
              assertThat(error.getReason()).contains("not approval compatible");
            });

    verifyNoInteractions(apiKeyResolver, managedAiUsageService, governanceGuard, events);
    assertThat(gate.getStatus()).isEqualTo("PENDING");
  }

  @Test
  void uppercaseSuppliedPlanFingerprintIsPersistedAsCanonicalLowercaseHex() {
    stubAllowedAdmission();
    String actual = ExactPlanFingerprint.sha256(run.getPlanJson());
    var command =
        new PlanApprovalService.Command(
            true, 3, "REALTIME", actual.toUpperCase(java.util.Locale.ROOT), null, null);

    var result = service.decide("run-1", command, actor);

    assertThat(result.evidence().suppliedPlanFingerprint()).isEqualTo(actual);
    assertThat(result.evidence().planFingerprintMatched()).isTrue();
    assertThat(DecisionEvidenceV1.parse(gate.getApprovalEvidenceJson()).suppliedPlanFingerprint())
        .isEqualTo(actual);
  }

  @Test
  void oppositeDecisionConflictsWithoutReevaluatingPolicy() {
    stubAllowedAdmission();
    service.decide("run-1", PlanApprovalService.Command.approveLegacy(), actor);

    assertThatThrownBy(
            () -> service.decide("run-1", PlanApprovalService.Command.rejectLegacy(), actor))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

    verify(apiKeyResolver, times(1)).resolveWithProvenance("owner-1");
  }

  @Test
  void rejectionIsDistinctAndDoesNotEvaluatePositivePolicies() {
    var result = service.decide("run-1", PlanApprovalService.Command.rejectLegacy(), actor);

    assertThat(result.decision()).isEqualTo(PlanGateDecisionService.DecisionKind.REJECT);
    assertThat(gate.getStatus()).isEqualTo("REJECTED");
    assertThat(gate.getDecisionKind()).isEqualTo("REJECT");
    assertThat(outbox.getStatus()).isEqualTo("CANCELLED");
    assertThat(run.getStatus()).isEqualTo("CANCELLED");
    assertThat(result.evidence().managedAiOutcome()).isEqualTo("NOT_EVALUATED");
    verifyNoInteractions(apiKeyResolver, managedAiUsageService, governanceGuard);
    // A rejection must never queue the resume continuation — but it IS a first-time customer
    // decision, so the post-commit audit/activity event carrying the real actor is published.
    verify(events, never()).publishEvent(any(PlanGateResumeReadyCommitted.class));
    verify(events, times(1))
        .publishEvent(
            new PlanGateDecisionCommitted(
                "run-1",
                "project-1",
                PlanGateDecisionService.DecisionKind.REJECT,
                "owner-1",
                null,
                null,
                null));
  }

  @Test
  void firstTimeApprovalPublishesActorBearingDecisionEventAndReplayDoesNot() {
    stubAllowedAdmission();
    actor.getUser().setEmail("owner@example.com");
    actor.getUser().setFirstName("Ada");
    actor.getUser().setLastName("Lovelace");
    run.setRunTitle("Fixture run");
    var command = new PlanApprovalService.Command(true, 3, "REALTIME", null, null, null);

    var first = service.decide("run-1", command, actor);
    var replay = service.decide("run-1", command, actor);

    assertThat(first.replayed()).isFalse();
    assertThat(replay.replayed()).isTrue();
    // Exactly ONE decision event for the first-time decision; the exact replay publishes nothing.
    verify(events, times(1))
        .publishEvent(
            new PlanGateDecisionCommitted(
                "run-1",
                "project-1",
                PlanGateDecisionService.DecisionKind.APPROVE,
                "owner-1",
                "owner@example.com",
                "Ada Lovelace",
                "Fixture run"));
    verify(events, times(1)).publishEvent(new PlanGateResumeReadyCommitted("run-1"));
  }

  @Test
  void preApprovalCancelPublishesNoCustomerDecisionEvent() {
    service.cancel(
        "run-1", actor, PlanGateDecisionService.ResolutionSource.RUN_CANCEL_API, "user cancelled");

    verify(events, never()).publishEvent(any());
  }

  @Test
  void preApprovalCancelHasIndependentDecisionKind() {
    var result =
        service.cancel(
            "run-1",
            actor,
            PlanGateDecisionService.ResolutionSource.RUN_CANCEL_API,
            "user cancelled");

    assertThat(result.decision()).isEqualTo(PlanGateDecisionService.DecisionKind.CANCEL);
    assertThat(gate.getStatus()).isEqualTo("CANCELLED");
    assertThat(gate.getDecisionKind()).isEqualTo("CANCEL");
    assertThat(gate.getResolutionReason()).isEqualTo("user cancelled");
    assertThat(result.evidence().contractAcknowledgementOutcome()).isEqualTo("NOT_EVALUATED");
  }

  @Test
  void decisionRejectsDigestValidContinuationMissingRequiredProperty() {
    setContinuationPayload(
        "{\"schemaVersion\":1,\"localRun\":false,"
            + "\"requireContinuationConfirmation\":false,"
            + "\"requireContinuationBenchmarkPrior\":false,"
            + "\"pinnedModel\":null}");

    assertInvalidContinuationDecision();
  }

  @Test
  void decisionRejectsDigestValidContinuationWithNullRequiredBoolean() {
    setContinuationPayload(
        "{\"schemaVersion\":1,\"localRun\":null,"
            + "\"requireContinuationConfirmation\":false,"
            + "\"requireContinuationBenchmarkPrior\":false,"
            + "\"pinnedModel\":null,\"lowComplexityWorkerStartTierOverride\":null}");

    assertInvalidContinuationDecision();
  }

  @Test
  void replayRejectsMutatedPlanEvenWhenReceiptDigestIsIntact() {
    stubAllowedAdmission();
    service.decide("run-1", PlanApprovalService.Command.approveLegacy(), actor);
    run.setPlanJson("{\"tasks\":[\"mutated\"]}");

    assertThatThrownBy(
            () -> service.decide("run-1", PlanApprovalService.Command.approveLegacy(), actor))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
  }

  @Test
  void replayRejectsGateAdmissionVersionThatDoesNotMatchSupportedReceiptSchema() {
    stubAllowedAdmission();
    service.decide("run-1", PlanApprovalService.Command.approveLegacy(), actor);
    gate.setAdmissionContractVersion(DecisionEvidenceV1.SCHEMA_VERSION + 1);

    assertThatThrownBy(
            () -> service.decide("run-1", PlanApprovalService.Command.approveLegacy(), actor))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            error -> {
              assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
              assertThat(error.getReason()).contains("version is unsupported");
            });
  }

  @Test
  void replayRejectsHardCapMutationEvenWhenEstimateIsUnchanged() {
    stubAllowedAdmission();
    service.decide("run-1", PlanApprovalService.Command.approveLegacy(), actor);
    run.setHardCapUsd(7.0);

    assertThatThrownBy(
            () -> service.decide("run-1", PlanApprovalService.Command.approveLegacy(), actor))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
  }

  @Test
  void rejectionSnapshotsPreExistingLowercaseAcknowledgementWithoutAdmittingIt() {
    Instant acknowledgedAt = Instant.parse("2026-07-27T23:00:00Z");
    String reportJson = "{\"requiresTypedAck\":true}";
    String reportDigest = DecisionEvidenceV1.sha256(reportJson);
    PlanContractGateReport report =
        PlanContractGateReport.builder()
            .runId("run-1")
            .reportJson(reportJson)
            .reportDigest(reportDigest)
            .requiresTypedAck(true)
            .acknowledgedAt(acknowledgedAt)
            .acknowledgedByUserId("owner-1")
            .acknowledgementText("breaking")
            .build();
    gate.setContractGateReportDigest(reportDigest);
    when(entityManager.find(PlanContractGateReport.class, "run-1", LockModeType.PESSIMISTIC_WRITE))
        .thenReturn(report);

    var result = service.decide("run-1", PlanApprovalService.Command.rejectLegacy(), actor);

    assertThat(result.evidence().contractAcknowledgementOutcome()).isEqualTo("NOT_EVALUATED");
    assertThat(result.evidence().contractAcknowledgedAt()).isEqualTo(acknowledgedAt.toString());
    assertThat(result.evidence().contractAcknowledgedByUserId()).isEqualTo("owner-1");
    assertThat(result.evidence().contractAcknowledgementText()).isEqualTo("breaking");
    assertThat(DecisionEvidenceV1.parse(gate.getApprovalEvidenceJson()).canonicalJson())
        .isEqualTo(gate.getApprovalEvidenceJson());
  }

  @Test
  void cancellationSnapshotsPreExistingAcknowledgementWithoutAdmittingIt() {
    Instant acknowledgedAt = Instant.parse("2026-07-27T23:00:00Z");
    String reportJson = "{\"requiresTypedAck\":true}";
    String reportDigest = DecisionEvidenceV1.sha256(reportJson);
    PlanContractGateReport report =
        PlanContractGateReport.builder()
            .runId("run-1")
            .reportJson(reportJson)
            .reportDigest(reportDigest)
            .requiresTypedAck(true)
            .acknowledgedAt(acknowledgedAt)
            .acknowledgedByUserId("owner-1")
            .acknowledgementText("BREAKING")
            .build();
    gate.setContractGateReportDigest(reportDigest);
    when(entityManager.find(PlanContractGateReport.class, "run-1", LockModeType.PESSIMISTIC_WRITE))
        .thenReturn(report);

    var result =
        service.cancel(
            "run-1",
            actor,
            PlanGateDecisionService.ResolutionSource.RUN_CANCEL_API,
            "user cancelled");

    assertThat(result.evidence().contractAcknowledgementOutcome()).isEqualTo("NOT_EVALUATED");
    assertThat(result.evidence().contractAcknowledgedAt()).isEqualTo(acknowledgedAt.toString());
    assertThat(result.evidence().contractAcknowledgedByUserId()).isEqualTo("owner-1");
    assertThat(result.evidence().contractAcknowledgementText()).isEqualTo("BREAKING");
  }

  @Test
  void approvalReplayAcceptsAndPreservesPreExistingLowercaseAcknowledgement() {
    String reportJson = "{\"requiresTypedAck\":true}";
    String reportDigest = DecisionEvidenceV1.sha256(reportJson);
    PlanContractGateReport report =
        PlanContractGateReport.builder()
            .runId("run-1")
            .reportJson(reportJson)
            .reportDigest(reportDigest)
            .requiresTypedAck(true)
            .acknowledgedAt(Instant.parse("2026-07-27T23:00:00Z"))
            .acknowledgedByUserId("owner-1")
            .acknowledgementText("breaking")
            .build();
    gate.setContractGateReportDigest(reportDigest);
    stubAllowedAdmission();
    when(entityManager.find(PlanContractGateReport.class, "run-1", LockModeType.PESSIMISTIC_WRITE))
        .thenReturn(report);
    var command =
        new PlanApprovalService.Command(true, null, "REALTIME", null, "BREAKING", reportDigest);

    var first = service.decide("run-1", command, actor);
    String exactReceipt = gate.getApprovalEvidenceJson();
    var replay = service.decide("run-1", command, actor);

    assertThat(first.evidence().contractAcknowledgementText()).isEqualTo("breaking");
    assertThat(replay.replayed()).isTrue();
    assertThat(replay.evidence().canonicalJson()).isEqualTo(exactReceipt);
    assertThat(gate.getApprovalEvidenceJson()).isEqualTo(exactReceipt);
  }

  @Test
  void newAcknowledgementsArePersistedInCanonicalUppercase() {
    String reportJson = "{\"requiresTypedAck\":true}";
    String reportDigest = DecisionEvidenceV1.sha256(reportJson);
    PlanContractGateReport report =
        PlanContractGateReport.builder()
            .runId("run-1")
            .reportJson(reportJson)
            .reportDigest(reportDigest)
            .requiresTypedAck(true)
            .build();
    gate.setContractGateReportDigest(reportDigest);
    stubAllowedAdmission();
    when(entityManager.find(PlanContractGateReport.class, "run-1", LockModeType.PESSIMISTIC_WRITE))
        .thenReturn(report);
    var command =
        new PlanApprovalService.Command(true, null, "REALTIME", null, "breaking", reportDigest);

    var result = service.decide("run-1", command, actor);

    assertThat(report.getAcknowledgementText()).isEqualTo("BREAKING");
    assertThat(result.evidence().contractAcknowledgementText()).isEqualTo("BREAKING");
  }

  @Test
  void cancelLosingLockedRaceReturnsApprovalWinnerForLifecycleReload() {
    stubAllowedAdmission();
    service.decide("run-1", PlanApprovalService.Command.approveLegacy(), actor);

    var result =
        service.cancel(
            "run-1",
            actor,
            PlanGateDecisionService.ResolutionSource.RUN_CANCEL_API,
            "Cancelled by user");

    assertThat(result.decision()).isEqualTo(PlanGateDecisionService.DecisionKind.APPROVE);
    assertThat(result.replayed()).isTrue();
    assertThat(run.getStatus()).isEqualTo("APPROVAL_RESUMING");
    assertThat(outbox.getStatus()).isEqualTo("READY");
  }

  @Test
  void externalCollaboratorApprovalKeepsPayerOrgDistinctFromHostTenantOrg() {
    actor.getUser().setOrgId("payer-org");
    when(entityManager.find(Org.class, "payer-org", LockModeType.PESSIMISTIC_WRITE))
        .thenReturn(Org.builder().id("payer-org").build());
    when(entityManager.find(
            com.navneet.executespec.contracts.gate.PlanContractGateReport.class,
            "run-1",
            LockModeType.PESSIMISTIC_WRITE))
        .thenReturn(null);
    when(apiKeyResolver.resolveWithProvenance("owner-1"))
        .thenReturn(
            new ApiKeyResolver.CredentialResolution(
                ResolvedKeys.SYSTEM_ONLY, "payer-org", Map.of()));
    when(managedAiUsageService.assertCanApproveEstimatedSpend("owner-1", "payer-org", false, 4.5))
        .thenReturn(
            new ManagedAiUsageService.ApprovalEvaluation(
                "ALLOWED", "MANAGED", 2000L, 1900L, 100L, 25.0, 23.5));
    when(governanceGuard.evaluateApprovalCost(eq("org-1"), eq(4.5), any(AppUserDetails.class)))
        .thenReturn(new GovernanceGuard.OrgCostEvaluation("ALLOWED", null, 4.5, "NO_THRESHOLD"));
    when(governanceGuard.evaluateProjectSpendCap("project-1", 4.5))
        .thenReturn(
            new GovernanceGuard.ProjectSpendEvaluation(
                "ALLOWED", null, BigDecimal.ZERO, BigDecimal.valueOf(4.5), "NO_CAP"));

    var result = service.decide("run-1", PlanApprovalService.Command.approveLegacy(), actor);

    assertThat(run.getOrgId()).isEqualTo("org-1");
    assertThat(result.evidence().billingOrgId()).isEqualTo("payer-org");
    assertThat(result.evidence().credentialAuthorizationOrgId()).isEqualTo("payer-org");
    assertThat(gate.getBillingOrgId()).isEqualTo("payer-org");
    verify(managedAiUsageService)
        .assertCanApproveEstimatedSpend("owner-1", "payer-org", false, 4.5);
  }

  @Test
  void decisionServiceRefusesAnActorWithoutAConcreteApprovalAuthority() {
    AppUserDetails unrelated =
        new AppUserDetails(AppUser.builder().id("other-user").globalRole("USER").build(), null);
    when(entityManager.find(
            com.navneet.executespec.contracts.gate.PlanContractGateReport.class,
            "run-1",
            LockModeType.PESSIMISTIC_WRITE))
        .thenReturn(null);
    when(entityManager.find(AppUser.class, "other-user", LockModeType.PESSIMISTIC_WRITE))
        .thenReturn(unrelated.getUser());

    assertThatThrownBy(
            () -> service.decide("run-1", PlanApprovalService.Command.rejectLegacy(), unrelated))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));

    verifyNoInteractions(apiKeyResolver, managedAiUsageService, governanceGuard);
  }

  @Test
  void receiptPreservesTheActualApprovalSurface() {
    stubAllowedAdmission();

    var result =
        service.decide(
            "run-1",
            PlanApprovalService.Command.approveLegacy(),
            actor,
            PlanGateDecisionService.ResolutionSource.EXECUTION_VIEW_API);

    assertThat(result.evidence().resolutionSource()).isEqualTo("EXECUTION_VIEW_API");
    assertThat(gate.getResolutionSource()).isEqualTo("EXECUTION_VIEW_API");
  }

  @Test
  void cancellationRefusesANonCancellationResolutionSource() {
    assertThatThrownBy(
            () ->
                service.cancel(
                    "run-1",
                    actor,
                    PlanGateDecisionService.ResolutionSource.EXECUTION_VIEW_API,
                    "user cancelled"))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
  }

  @Test
  void trustedSystemCanCancelAPersonalPendingGateWithoutInventingAnOrg() {
    run.setOrgId(null);

    var result =
        service.cancel(
            "run-1",
            null,
            PlanGateDecisionService.ResolutionSource.SYSTEM_CANCEL,
            "operator recovery cancelled the pending gate");

    assertThat(result.evidence().actorType()).isEqualTo("SYSTEM");
    assertThat(result.evidence().authorizationBasis()).isEqualTo("SYSTEM_INTERNAL");
    assertThat(result.evidence().authorizationOrgId()).isNull();
    assertThat(result.evidence().resolutionSource()).isEqualTo("SYSTEM_CANCEL");
  }

  private void stubAllowedAdmission() {
    lenient()
        .when(
            entityManager.find(
                com.navneet.executespec.contracts.gate.PlanContractGateReport.class,
                "run-1",
                LockModeType.PESSIMISTIC_WRITE))
        .thenReturn(null);
    when(apiKeyResolver.resolveWithProvenance("owner-1"))
        .thenReturn(
            new ApiKeyResolver.CredentialResolution(ResolvedKeys.SYSTEM_ONLY, "org-1", Map.of()));
    when(managedAiUsageService.assertCanApproveEstimatedSpend("owner-1", "org-1", false, 4.5))
        .thenReturn(
            new ManagedAiUsageService.ApprovalEvaluation(
                "ALLOWED", "MANAGED", 2000L, 1900L, 100L, 25.0, 23.5));
    when(governanceGuard.evaluateApprovalCost(eq("org-1"), eq(4.5), any(AppUserDetails.class)))
        .thenReturn(new GovernanceGuard.OrgCostEvaluation("ALLOWED", null, 4.5, "NO_THRESHOLD"));
    when(governanceGuard.evaluateProjectSpendCap("project-1", 4.5))
        .thenReturn(
            new GovernanceGuard.ProjectSpendEvaluation(
                "ALLOWED", null, BigDecimal.ZERO, BigDecimal.valueOf(4.5), "NO_CAP"));
  }

  private void setContinuationPayload(String payload) {
    String digest = DecisionEvidenceV1.sha256(payload);
    gate.setComment(payload);
    gate.setContinuationSha256(digest);
    outbox.setPayload(payload);
    outbox.setPayloadSha256(digest);
  }

  private void assertInvalidContinuationDecision() {
    // APPROVE must refuse with the stable unrecoverable code — never 500, never a gate bypass.
    assertThatThrownBy(
            () -> service.decide("run-1", PlanApprovalService.Command.approveLegacy(), actor))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            error -> {
              assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
              assertThat(error.getReason())
                  .contains("PLAN_GATE_STATE_UNRECOVERABLE[CONTINUATION_PAYLOAD_INVALID]")
                  .contains("continuation payload is invalid")
                  .contains("Cancel the run and resubmit");
            });
    assertThat(run.getStatus()).isEqualTo("AWAITING_APPROVAL");

    // REJECT succeeds by fencing the run terminal so the customer can always stop a broken run.
    var result = service.decide("run-1", PlanApprovalService.Command.rejectLegacy(), actor);

    assertThat(result.decision()).isEqualTo(PlanGateDecisionService.DecisionKind.CANCEL);
    assertThat(result.evidence()).isNull();
    assertThat(run.getStatus()).isEqualTo("CANCELLED");
    assertThat(run.getFailureReason())
        .contains("PLAN_GATE_STATE_UNRECOVERABLE[CONTINUATION_PAYLOAD_INVALID]");
    assertThat(gate.getStatus()).isEqualTo("PENDING");
    assertThat(gate.getApprovalEvidenceJson()).isNull();
    assertThat(outbox.getStatus()).isEqualTo("CANCELLED");
    assertThat(outbox.getLastError())
        .isEqualTo("PLAN_GATE_STATE_UNRECOVERABLE[CONTINUATION_PAYLOAD_INVALID]");
    verifyNoInteractions(apiKeyResolver, managedAiUsageService, governanceGuard, events);
  }

  @Test
  void missingContinuationRowRefusesApproveTypedAndCancelFencesTerminal() {
    when(outboxRepository.findByIdForUpdate("run-1")).thenReturn(Optional.empty());

    assertThatThrownBy(
            () -> service.decide("run-1", PlanApprovalService.Command.approveLegacy(), actor))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            error -> {
              assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
              assertThat(error.getReason())
                  .contains("PLAN_GATE_STATE_UNRECOVERABLE[CONTINUATION_ROW_MISSING]")
                  .contains("Cancel the run and resubmit");
            });
    assertThat(run.getStatus()).isEqualTo("AWAITING_APPROVAL");

    var result =
        service.cancel(
            "run-1",
            actor,
            PlanGateDecisionService.ResolutionSource.RUN_CANCEL_API,
            "Cancelled by user");

    assertThat(result.decision()).isEqualTo(PlanGateDecisionService.DecisionKind.CANCEL);
    assertThat(run.getStatus()).isEqualTo("CANCELLED");
    assertThat(run.getCurrentGate()).isNull();
    assertThat(run.getCompletedAt()).isNotNull();
    assertThat(run.getFailureReason())
        .contains("PLAN_GATE_STATE_UNRECOVERABLE[CONTINUATION_ROW_MISSING]")
        .contains("Cancelled by user");
    // The broken PENDING gate row is untouched: no receipt can be fabricated for corrupt rows.
    assertThat(gate.getStatus()).isEqualTo("PENDING");
    assertThat(gate.getApprovalEvidenceJson()).isNull();
    verifyNoInteractions(apiKeyResolver, managedAiUsageService, governanceGuard, events);
  }

  @Test
  void missingGateRowRefusesApproveTypedAndCancelFencesTerminal() {
    when(gateRepository.findByIdForPlanGateOpen("gate:plan:run-1")).thenReturn(Optional.empty());

    assertThatThrownBy(
            () -> service.decide("run-1", PlanApprovalService.Command.approveLegacy(), actor))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            error -> {
              assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
              assertThat(error.getReason())
                  .contains("PLAN_GATE_STATE_UNRECOVERABLE[GATE_ROW_MISSING]");
            });

    var result =
        service.cancel(
            "run-1",
            actor,
            PlanGateDecisionService.ResolutionSource.RUN_CANCEL_API,
            "Cancelled by user");

    assertThat(result.decision()).isEqualTo(PlanGateDecisionService.DecisionKind.CANCEL);
    assertThat(run.getStatus()).isEqualTo("CANCELLED");
    assertThat(run.getFailureReason()).contains("PLAN_GATE_STATE_UNRECOVERABLE[GATE_ROW_MISSING]");
    // The orphaned continuation row is fenced so the resume dispatcher can never consume it.
    assertThat(outbox.getStatus()).isEqualTo("CANCELLED");
    assertThat(outbox.getLeaseOwner()).isNull();
    verifyNoInteractions(apiKeyResolver, managedAiUsageService, governanceGuard, events);
  }

  @Test
  void missingPlanArtifactRefusesApproveTypedAndRejectFencesTerminal() {
    run.setPlanJson(null);

    assertThatThrownBy(
            () -> service.decide("run-1", PlanApprovalService.Command.approveLegacy(), actor))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            error -> {
              assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
              assertThat(error.getReason())
                  .contains("PLAN_GATE_STATE_UNRECOVERABLE[PLAN_ARTIFACT_MISSING]");
            });

    var result = service.decide("run-1", PlanApprovalService.Command.rejectLegacy(), actor);

    assertThat(result.decision()).isEqualTo(PlanGateDecisionService.DecisionKind.CANCEL);
    assertThat(run.getStatus()).isEqualTo("CANCELLED");
    assertThat(run.getFailureReason())
        .contains("PLAN_GATE_STATE_UNRECOVERABLE[PLAN_ARTIFACT_MISSING]");
    assertThat(gate.getStatus()).isEqualTo("PENDING");
    assertThat(outbox.getStatus()).isEqualTo("CANCELLED");
    verifyNoInteractions(apiKeyResolver, managedAiUsageService, governanceGuard, events);
  }

  @Test
  void corruptContinuationDigestRefusesApproveTypedAndCancelFencesTerminal() {
    outbox.setPayloadSha256("0".repeat(64));

    assertThatThrownBy(
            () -> service.decide("run-1", PlanApprovalService.Command.approveLegacy(), actor))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            error -> {
              assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
              assertThat(error.getReason())
                  .contains("PLAN_GATE_STATE_UNRECOVERABLE[GATE_CONTINUATION_IDENTITY_MISMATCH]");
            });

    var result =
        service.cancel(
            "run-1",
            actor,
            PlanGateDecisionService.ResolutionSource.RUN_CANCEL_API,
            "Cancelled by user");

    assertThat(result.decision()).isEqualTo(PlanGateDecisionService.DecisionKind.CANCEL);
    assertThat(run.getStatus()).isEqualTo("CANCELLED");
    assertThat(run.getFailureReason())
        .contains("PLAN_GATE_STATE_UNRECOVERABLE[GATE_CONTINUATION_IDENTITY_MISMATCH]");
    assertThat(outbox.getStatus()).isEqualTo("CANCELLED");
    verifyNoInteractions(apiKeyResolver, managedAiUsageService, governanceGuard, events);
  }

  @Test
  void brokenGateStateOnNonAwaitingRunStillConflictsInsteadOfFencing() {
    run.setStatus("WORKING");
    when(outboxRepository.findByIdForUpdate("run-1")).thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                service.cancel(
                    "run-1",
                    actor,
                    PlanGateDecisionService.ResolutionSource.RUN_CANCEL_API,
                    "Cancelled by user"))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            error -> {
              assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
              assertThat(error.getReason())
                  .contains("PLAN_GATE_STATE_UNRECOVERABLE[CONTINUATION_ROW_MISSING]")
                  .contains("current state");
            });
    assertThat(run.getStatus()).isEqualTo("WORKING");
  }
}

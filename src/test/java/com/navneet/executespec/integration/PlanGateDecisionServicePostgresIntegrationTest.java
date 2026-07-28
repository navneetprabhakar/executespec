package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import javax.sql.DataSource;

import jakarta.persistence.EntityManager;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agent.ApiKeyResolver;
import com.navneet.executespec.agent.Plan;
import com.navneet.executespec.agent.PlannedTask;
import com.navneet.executespec.agent.RequirementSpec;
import com.navneet.executespec.agent.ResolvedKeys;
import com.navneet.executespec.agent.requirement.ExactPlanFingerprint;
import com.navneet.executespec.agent.requirement.RequirementContractDigests;
import com.navneet.executespec.agent.requirement.RequirementGateBindingService.RequestedMode;
import com.navneet.executespec.agent.requirement.RequirementIrCodec;
import com.navneet.executespec.agent.requirement.RequirementIrCompiler;
import com.navneet.executespec.agent.requirement.RequirementIrDocument;
import com.navneet.executespec.agent.requirement.entity.RunRequirementContract;
import com.navneet.executespec.agent.requirement.repository.RunRequirementContractRepository;
import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.billing.service.ManagedAiUsageService;
import com.navneet.executespec.governance.GovernanceGuard;
import com.navneet.executespec.harness.repository.GateRepository;
import com.navneet.executespec.harness.repository.PlanGateResumeOutboxRepository;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * Real PostgreSQL coverage for the C03C plan-decision transaction.
 *
 * <p>The service is constructed over the application's real Spring Data repositories and shared
 * transactional {@link EntityManager}. Only external policy evaluators and the in-process event
 * publisher are mocks. Each call is wrapped in a real transaction so the service's mutations,
 * pessimistic locks, deferred V348 constraints, commit, and rollback execute against PostgreSQL.
 *
 * <p>There is intentionally no claim that a test-only database trigger is an arbitrary Java
 * instruction-point fault seam. The rollback matrices prove failure at every relation mutated by
 * APPROVE (acknowledgement, gate receipt, continuation outbox, and run), REJECT, and CANCEL (gate
 * receipt, continuation outbox, and run). Proving failure after every individual Java assignment
 * would require an explicit production fault-injection seam, which this test-only change is not
 * authorized to add.
 */
@TestPropertySource(
    properties = {
      "execution.plan-gate-resume.scan-initial-delay-ms=3600000",
      "execution.plan-gate-resume.scan-interval-ms=3600000"
    })
class PlanGateDecisionServicePostgresIntegrationTest extends PostgresIntegrationBase {
  private static final String PREFIX = "c03c-decision-";
  private static final String PLAN_JSON = "{\"planTitle\":\"Exact\",\"tasks\":[]}";
  private static final String CONTINUATION =
      "{\"schemaVersion\":1,\"localRun\":false,"
          + "\"requireContinuationConfirmation\":false,"
          + "\"requireContinuationBenchmarkPrior\":false,\"pinnedModel\":null,"
          + "\"lowComplexityWorkerStartTierOverride\":null}";

  @Autowired PlanGateOpenService opener;
  @Autowired PipelineRunRepository runs;
  @Autowired GateRepository gates;
  @Autowired PlanGateResumeOutboxRepository outbox;
  @Autowired PlanGateResumeStore resumeStore;
  @Autowired RunRequirementContractRepository requirements;
  @Autowired EntityManager entityManager;
  @Autowired PlatformTransactionManager transactionManager;
  @Autowired JdbcTemplate jdbc;
  @Autowired DataSource dataSource;
  @Autowired ObjectMapper objectMapper;

  private ApiKeyResolver apiKeys;
  private ManagedAiUsageService managedAi;
  private GovernanceGuard governance;
  private ApplicationEventPublisher events;
  private PlanGateDecisionService service;
  private TransactionTemplate transactions;

  @BeforeEach
  void setUp() {
    // Runs own gates/outbox/report rows by cascade. Restrict cleanup to this class's namespace.
    jdbc.update("DELETE FROM pipeline_run WHERE run_id LIKE ?", PREFIX + "%");
    jdbc.update("DELETE FROM project WHERE id LIKE ?", PREFIX + "%");
    jdbc.update("DELETE FROM app_user WHERE id LIKE ?", PREFIX + "%");
    jdbc.update("DELETE FROM org WHERE id LIKE ?", PREFIX + "%");
    apiKeys = mock(ApiKeyResolver.class);
    managedAi = mock(ManagedAiUsageService.class);
    governance = mock(GovernanceGuard.class);
    events = mock(ApplicationEventPublisher.class);
    service =
        new PlanGateDecisionService(
            runs,
            gates,
            outbox,
            requirements,
            entityManager,
            apiKeys,
            managedAi,
            governance,
            events);
    transactions = new TransactionTemplate(transactionManager);
  }

  @Test
  void approvalCommitsAcknowledgementReceiptGateRunAndReadyOutboxAtomically() {
    Fixture fixture = openFixture(true);
    allowApproval(fixture);

    PlanGateDecisionService.DecisionResult result =
        inTransaction(() -> service.decide(fixture.runId(), fixture.approve(), fixture.actor()));

    assertThat(result.replayed()).isFalse();
    assertThat(result.decision()).isEqualTo(PlanGateDecisionService.DecisionKind.APPROVE);
    assertThat(result.evidence().contractAcknowledgementOutcome()).isEqualTo("ACKNOWLEDGED");
    assertThat(result.evidence().reviewedPlanFingerprint())
        .isEqualTo(ExactPlanFingerprint.sha256(PLAN_JSON));
    assertThat(result.evidence().authorizationBasis()).isEqualTo("RUN_OWNER");
    assertThat(result.evidence().billingOwnerUserId()).isEqualTo(fixture.ownerId());
    assertThat(result.evidence().billingOrgId()).isEqualTo(fixture.orgId());

    assertCoherentTuple(fixture.runId(), "APPROVE", "APPROVED", "APPROVAL_RESUMING", "READY");
    Map<String, Object> gate = gateRow(fixture.runId());
    assertThat(gate.get("approval_evidence_json")).isEqualTo(result.evidence().canonicalJson());
    assertThat(gate.get("approval_evidence_digest"))
        .isEqualTo(DecisionEvidenceV1.sha256(result.evidence().canonicalJson()));
    assertThat(gate.get("decision_request_fingerprint"))
        .isEqualTo(result.evidence().decisionRequestFingerprint());
    assertThat(
            jdbc.queryForObject(
                "SELECT acknowledgement_text FROM plan_contract_gate_report WHERE run_id=?",
                String.class,
                fixture.runId()))
        .isEqualTo("BREAKING");
    assertThat(
            jdbc.queryForObject(
                "SELECT acknowledged_by_user_id FROM plan_contract_gate_report WHERE run_id=?",
                String.class,
                fixture.runId()))
        .isEqualTo(fixture.ownerId());
    verify(events).publishEvent(new PlanGateResumeReadyCommitted(fixture.runId()));
  }

  @Test
  void unmanagedEnterpriseApprovalCommitsExplicitNullManagedAiLimits() {
    Fixture fixture = openFixture(false);
    when(apiKeys.resolveWithProvenance(fixture.ownerId())).thenReturn(credentials(fixture.orgId()));
    allowGovernance(fixture);
    when(managedAi.assertCanApproveEstimatedSpend(fixture.ownerId(), fixture.orgId(), false, 4.5))
        .thenReturn(
            new ManagedAiUsageService.ApprovalEvaluation(
                "ALLOWED", "UNMANAGED_TIER", null, null, null, null, null));

    PlanGateDecisionService.DecisionResult result =
        inTransaction(() -> service.decide(fixture.runId(), fixture.approve(), fixture.actor()));

    assertThat(result.evidence().managedAiMode()).isEqualTo("UNMANAGED_TIER");
    assertThat(result.evidence().managedAiGrantCredits()).isNull();
    assertThat(result.evidence().managedAiCreditsRemaining()).isNull();
    assertThat(result.evidence().managedAiEstimatedCredits()).isNull();
    assertThat(result.evidence().managedAiHardRawUsd()).isNull();
    assertThat(result.evidence().managedAiRemainingRawUsd()).isNull();
    assertThat(DecisionEvidenceV1.parse(result.evidence().canonicalJson()))
        .isEqualTo(result.evidence());
    assertCoherentTuple(fixture.runId(), "APPROVE", "APPROVED", "APPROVAL_RESUMING", "READY");
  }

  @Test
  void byokApprovalCommitsExplicitNullManagedAiLimitsWithoutPersistingCredentialMaterial() {
    Fixture fixture = openFixture(false);
    String customerKey = "customer-anthropic-key-must-not-be-persisted";
    ResolvedKeys ownKeys =
        new ResolvedKeys(
            customerKey, null, null, null, null, null, "OWN_KEYS", Set.of("anthropic"));
    when(apiKeys.resolveWithProvenance(fixture.ownerId()))
        .thenReturn(
            new ApiKeyResolver.CredentialResolution(
                ownKeys,
                fixture.orgId(),
                Map.of("anthropic", ApiKeyResolver.CredentialOrigin.USER)));
    allowGovernance(fixture);
    when(managedAi.assertCanApproveEstimatedSpend(fixture.ownerId(), fixture.orgId(), true, 4.5))
        .thenReturn(
            new ManagedAiUsageService.ApprovalEvaluation(
                "ALLOWED", "BYOK_EXEMPT", null, null, null, null, null));

    PlanGateDecisionService.DecisionResult result =
        inTransaction(() -> service.decide(fixture.runId(), fixture.approve(), fixture.actor()));

    assertThat(result.evidence().keySourceMode()).isEqualTo("OWN_KEYS");
    assertThat(result.evidence().credentialOrigin()).isEqualTo("USER");
    assertThat(result.evidence().ownKeyExempt()).isTrue();
    assertThat(result.evidence().managedAiMode()).isEqualTo("BYOK_EXEMPT");
    assertThat(result.evidence().managedAiHardRawUsd()).isNull();
    assertThat(result.evidence().managedAiRemainingRawUsd()).isNull();
    assertThat(result.evidence().canonicalJson()).doesNotContain(customerKey);
    assertThat(DecisionEvidenceV1.parse(result.evidence().canonicalJson()))
        .isEqualTo(result.evidence());
    assertCoherentTuple(fixture.runId(), "APPROVE", "APPROVED", "APPROVAL_RESUMING", "READY");
  }

  @ParameterizedTest
  @EnumSource(ApprovalWrite.class)
  void failureAtEveryPersistedApprovalRelationRollsBackTheWholeTuple(ApprovalWrite write) {
    Fixture fixture = openFixture(true);
    allowApproval(fixture);
    String trigger = installFailureTrigger(write, fixture.runId());
    try {
      assertThatThrownBy(
              () ->
                  inTransaction(
                      () -> service.decide(fixture.runId(), fixture.approve(), fixture.actor())))
          .rootCause()
          .hasMessageContaining("injected C03C approval failure");
    } finally {
      dropFailureTrigger(write, trigger);
    }

    assertUndecidedTuple(fixture.runId(), true);
  }

  @Test
  void policyFailureAfterAcknowledgementMutationRollsBackTheAcknowledgementAndDecision() {
    Fixture fixture = openFixture(true);
    when(apiKeys.resolveWithProvenance(fixture.ownerId())).thenReturn(credentials(fixture.orgId()));
    when(managedAi.assertCanApproveEstimatedSpend(fixture.ownerId(), fixture.orgId(), false, 4.5))
        .thenThrow(
            new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED, "injected policy denial"));

    assertThatThrownBy(
            () ->
                inTransaction(
                    () -> service.decide(fixture.runId(), fixture.approve(), fixture.actor())))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("injected policy denial");

    assertUndecidedTuple(fixture.runId(), true);
    verifyNoInteractions(governance);
    verify(events, never()).publishEvent(new PlanGateResumeReadyCommitted(fixture.runId()));
  }

  @Test
  void rejectionAndPreApprovalCancellationAreDistinctAtomicTerminalDecisions() {
    Fixture rejected = openFixture(false);
    PlanGateDecisionService.DecisionResult rejection =
        inTransaction(
            () ->
                service.decide(
                    rejected.runId(),
                    PlanApprovalService.Command.rejectLegacy(),
                    rejected.actor()));
    assertThat(rejection.decision()).isEqualTo(PlanGateDecisionService.DecisionKind.REJECT);
    assertCoherentTuple(rejected.runId(), "REJECT", "REJECTED", "CANCELLED", "CANCELLED");

    Fixture cancelled = openFixture(false);
    PlanGateDecisionService.DecisionResult cancellation =
        inTransaction(
            () ->
                service.cancel(
                    cancelled.runId(),
                    cancelled.actor(),
                    PlanGateDecisionService.ResolutionSource.RUN_CANCEL_API,
                    "cancelled before approval"));
    assertThat(cancellation.decision()).isEqualTo(PlanGateDecisionService.DecisionKind.CANCEL);
    assertCoherentTuple(cancelled.runId(), "CANCEL", "CANCELLED", "CANCELLED", "CANCELLED");
    assertThat(gateRow(cancelled.runId()).get("resolution_reason"))
        .isEqualTo("cancelled before approval");

    verifyNoInteractions(apiKeys, managedAi, governance);
    verify(events, never()).publishEvent(new PlanGateResumeReadyCommitted(rejected.runId()));
    verify(events, never()).publishEvent(new PlanGateResumeReadyCommitted(cancelled.runId()));
  }

  @ParameterizedTest
  @EnumSource(TerminalWrite.class)
  void failureAtEveryPersistedRejectionRelationRollsBackTheWholeTuple(TerminalWrite write) {
    Fixture fixture = openFixture(false);
    String trigger = installFailureTrigger(write, fixture.runId(), "rejection");
    try {
      assertThatThrownBy(
              () ->
                  inTransaction(
                      () ->
                          service.decide(
                              fixture.runId(),
                              PlanApprovalService.Command.rejectLegacy(),
                              fixture.actor())))
          .rootCause()
          .hasMessageContaining("injected C03C rejection failure");
    } finally {
      dropFailureTrigger(write, trigger);
    }

    assertUndecidedTuple(fixture.runId(), false);
  }

  @ParameterizedTest
  @EnumSource(TerminalWrite.class)
  void failureAtEveryPersistedCancellationRelationRollsBackTheWholeTuple(TerminalWrite write) {
    Fixture fixture = openFixture(false);
    String trigger = installFailureTrigger(write, fixture.runId(), "cancellation");
    try {
      assertThatThrownBy(
              () ->
                  inTransaction(
                      () ->
                          service.cancel(
                              fixture.runId(),
                              fixture.actor(),
                              PlanGateDecisionService.ResolutionSource.RUN_CANCEL_API,
                              "cancelled before approval")))
          .rootCause()
          .hasMessageContaining("injected C03C cancellation failure");
    } finally {
      dropFailureTrigger(write, trigger);
    }

    assertUndecidedTuple(fixture.runId(), false);
  }

  @Test
  void exactRejectionReplayReturnsStoredBytesWithoutReevaluatingPolicies() {
    Fixture fixture = openFixture(false);
    PlanGateDecisionService.DecisionResult first =
        inTransaction(
            () ->
                service.decide(
                    fixture.runId(), PlanApprovalService.Command.rejectLegacy(), fixture.actor()));
    clearInvocations(apiKeys, managedAi, governance, events);

    PlanGateDecisionService.DecisionResult replay =
        inTransaction(
            () ->
                service.decide(
                    fixture.runId(), PlanApprovalService.Command.rejectLegacy(), fixture.actor()));

    assertThat(replay.replayed()).isTrue();
    assertThat(replay.decision()).isEqualTo(PlanGateDecisionService.DecisionKind.REJECT);
    assertThat(replay.evidence().canonicalJson()).isEqualTo(first.evidence().canonicalJson());
    assertThat(gateRow(fixture.runId()).get("approval_evidence_json"))
        .isEqualTo(first.evidence().canonicalJson());
    verifyNoInteractions(apiKeys, managedAi, governance, events);
  }

  @Test
  void exactCancellationReplayReturnsStoredBytesWithoutReevaluatingPolicies() {
    Fixture fixture = openFixture(false);
    PlanGateDecisionService.DecisionResult first =
        inTransaction(
            () ->
                service.cancel(
                    fixture.runId(),
                    fixture.actor(),
                    PlanGateDecisionService.ResolutionSource.RUN_CANCEL_API,
                    "cancelled before approval"));
    clearInvocations(apiKeys, managedAi, governance, events);

    PlanGateDecisionService.DecisionResult replay =
        inTransaction(
            () ->
                service.cancel(
                    fixture.runId(),
                    fixture.actor(),
                    PlanGateDecisionService.ResolutionSource.RUN_CANCEL_API,
                    "cancelled before approval"));

    assertThat(replay.replayed()).isTrue();
    assertThat(replay.decision()).isEqualTo(PlanGateDecisionService.DecisionKind.CANCEL);
    assertThat(replay.evidence().canonicalJson()).isEqualTo(first.evidence().canonicalJson());
    assertThat(gateRow(fixture.runId()).get("approval_evidence_json"))
        .isEqualTo(first.evidence().canonicalJson());
    verifyNoInteractions(apiKeys, managedAi, governance, events);
  }

  @Test
  void exactApprovalReplayReturnsStoredBytesWithoutReevaluatingMutablePolicies() {
    Fixture fixture = openFixture(false);
    allowApproval(fixture);
    PlanGateDecisionService.DecisionResult first =
        inTransaction(() -> service.decide(fixture.runId(), fixture.approve(), fixture.actor()));
    clearInvocations(apiKeys, managedAi, governance, events);

    PlanGateDecisionService.DecisionResult replay =
        inTransaction(() -> service.decide(fixture.runId(), fixture.approve(), fixture.actor()));

    assertThat(replay.replayed()).isTrue();
    assertThat(replay.evidence().canonicalJson()).isEqualTo(first.evidence().canonicalJson());
    assertThat(gateRow(fixture.runId()).get("approval_evidence_json"))
        .isEqualTo(first.evidence().canonicalJson());
    verifyNoInteractions(apiKeys, managedAi, governance, events);
  }

  @Test
  void oppositeDecisionConflictsAndCannotRewriteTheCommittedApproval() {
    Fixture fixture = openFixture(false);
    allowApproval(fixture);
    PlanGateDecisionService.DecisionResult approved =
        inTransaction(() -> service.decide(fixture.runId(), fixture.approve(), fixture.actor()));
    clearInvocations(apiKeys, managedAi, governance, events);

    assertThatThrownBy(
            () ->
                inTransaction(
                    () ->
                        service.decide(
                            fixture.runId(),
                            PlanApprovalService.Command.rejectLegacy(),
                            fixture.actor())))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            failure -> assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

    assertCoherentTuple(fixture.runId(), "APPROVE", "APPROVED", "APPROVAL_RESUMING", "READY");
    assertThat(gateRow(fixture.runId()).get("approval_evidence_json"))
        .isEqualTo(approved.evidence().canonicalJson());
    verifyNoInteractions(apiKeys, managedAi, governance, events);
  }

  @Test
  void concurrentApproveAndRejectHaveExactlyOneCoherentWriter() throws Exception {
    Fixture fixture = openFixture(false);
    allowApproval(fixture);

    List<RaceOutcome> outcomes =
        race(
            () -> service.decide(fixture.runId(), fixture.approve(), fixture.actor()),
            () ->
                service.decide(
                    fixture.runId(), PlanApprovalService.Command.rejectLegacy(), fixture.actor()));

    assertThat(outcomes.stream().filter(RaceOutcome::wroteDecision)).hasSize(1);
    assertThat(outcomes.stream().filter(outcome -> outcome.failure() != null)).hasSize(1);
    assertThat(outcomes.stream().map(RaceOutcome::failure).filter(java.util.Objects::nonNull))
        .allSatisfy(this::assertConflict);
    assertPersistedWinnerIsCoherent(fixture.runId());
  }

  @Test
  void concurrentApproveAndCancelHaveOneWriterAndExposeTheApprovalWinnerOnReplay()
      throws Exception {
    Fixture fixture = openFixture(false);
    allowApproval(fixture);

    List<RaceOutcome> outcomes =
        race(
            () -> service.decide(fixture.runId(), fixture.approve(), fixture.actor()),
            () ->
                service.cancel(
                    fixture.runId(),
                    fixture.actor(),
                    PlanGateDecisionService.ResolutionSource.RUN_CANCEL_API,
                    "cancelled before approval"));

    assertThat(outcomes.stream().filter(RaceOutcome::wroteDecision)).hasSize(1);
    assertThat(
            outcomes.stream()
                .filter(outcome -> outcome.result() != null && outcome.result().replayed()))
        .allSatisfy(
            replay ->
                assertThat(replay.result().decision())
                    .isEqualTo(PlanGateDecisionService.DecisionKind.APPROVE));
    assertThat(outcomes.stream().map(RaceOutcome::failure).filter(java.util.Objects::nonNull))
        .allSatisfy(this::assertConflict);
    assertPersistedWinnerIsCoherent(fixture.runId());
  }

  @Test
  void approveWinsDeterministicallyWhenItReachesPersistenceBeforeReject() throws Exception {
    Fixture fixture = openFixture(false);
    allowApproval(fixture);

    List<RaceOutcome> outcomes =
        orderedRace(
            fixture.runId(),
            "APPROVED",
            () -> service.decide(fixture.runId(), fixture.approve(), fixture.actor()),
            () ->
                service.decide(
                    fixture.runId(), PlanApprovalService.Command.rejectLegacy(), fixture.actor()));

    assertOrderedWinner(outcomes, PlanGateDecisionService.DecisionKind.APPROVE, false);
    assertCoherentTuple(fixture.runId(), "APPROVE", "APPROVED", "APPROVAL_RESUMING", "READY");
  }

  @Test
  void rejectWinsDeterministicallyWhenItReachesPersistenceBeforeApprove() throws Exception {
    Fixture fixture = openFixture(false);
    allowApproval(fixture);

    List<RaceOutcome> outcomes =
        orderedRace(
            fixture.runId(),
            "REJECTED",
            () ->
                service.decide(
                    fixture.runId(), PlanApprovalService.Command.rejectLegacy(), fixture.actor()),
            () -> service.decide(fixture.runId(), fixture.approve(), fixture.actor()));

    assertOrderedWinner(outcomes, PlanGateDecisionService.DecisionKind.REJECT, false);
    assertCoherentTuple(fixture.runId(), "REJECT", "REJECTED", "CANCELLED", "CANCELLED");
  }

  @Test
  void approveWinsDeterministicallyWhenItReachesPersistenceBeforeCancel() throws Exception {
    Fixture fixture = openFixture(false);
    allowApproval(fixture);

    List<RaceOutcome> outcomes =
        orderedRace(
            fixture.runId(),
            "APPROVED",
            () -> service.decide(fixture.runId(), fixture.approve(), fixture.actor()),
            () ->
                service.cancel(
                    fixture.runId(),
                    fixture.actor(),
                    PlanGateDecisionService.ResolutionSource.RUN_CANCEL_API,
                    "cancelled before approval"));

    assertOrderedWinner(outcomes, PlanGateDecisionService.DecisionKind.APPROVE, true);
    assertCoherentTuple(fixture.runId(), "APPROVE", "APPROVED", "APPROVAL_RESUMING", "READY");
  }

  @Test
  void cancelWinsDeterministicallyWhenItReachesPersistenceBeforeApprove() throws Exception {
    Fixture fixture = openFixture(false);
    allowApproval(fixture);

    List<RaceOutcome> outcomes =
        orderedRace(
            fixture.runId(),
            "CANCELLED",
            () ->
                service.cancel(
                    fixture.runId(),
                    fixture.actor(),
                    PlanGateDecisionService.ResolutionSource.RUN_CANCEL_API,
                    "cancelled before approval"),
            () -> service.decide(fixture.runId(), fixture.approve(), fixture.actor()));

    assertOrderedWinner(outcomes, PlanGateDecisionService.DecisionKind.CANCEL, false);
    assertCoherentTuple(fixture.runId(), "CANCEL", "CANCELLED", "CANCELLED", "CANCELLED");
  }

  @Test
  void committedApprovalSurvivesWhenNoDispatcherObservesTheAfterCommitHint() {
    Fixture fixture = openFixture(false);
    allowApproval(fixture);

    PlanGateDecisionService.DecisionResult result =
        inTransaction(() -> service.decide(fixture.runId(), fixture.approve(), fixture.actor()));
    assertThat(result.replayed()).isFalse();

    // Simulate process loss after commit and before dispatch by discarding every in-memory object.
    // A fresh transaction reads the durable READY intent and exact receipt without an event bus.
    clearInvocations(apiKeys, managedAi, governance, events);
    Map<String, Object> durable =
        inTransaction(
            () ->
                jdbc.queryForMap(
                    "SELECT g.status AS gate_status,g.approval_evidence_json,o.status AS"
                        + " outbox_status,r.status AS run_status FROM gate_record g JOIN"
                        + " plan_gate_resume_outbox o ON o.run_id=g.run_id JOIN pipeline_run r ON"
                        + " r.run_id=g.run_id WHERE g.run_id=?",
                    fixture.runId()));
    assertThat(durable)
        .containsEntry("gate_status", "APPROVED")
        .containsEntry("outbox_status", "READY")
        .containsEntry("run_status", "APPROVAL_RESUMING")
        .containsEntry("approval_evidence_json", result.evidence().canonicalJson());
    verifyNoInteractions(apiKeys, managedAi, governance, events);
  }

  @Test
  void decisionHoldsBillingUserOrgAndProjectPolicyLocksUntilCommit() throws Exception {
    Fixture fixture = openFixture(false);
    allowApproval(fixture);
    CountDownLatch policyReached = new CountDownLatch(1);
    CountDownLatch releasePolicy = new CountDownLatch(1);
    when(apiKeys.resolveWithProvenance(fixture.ownerId()))
        .thenAnswer(
            ignored -> {
              policyReached.countDown();
              if (!releasePolicy.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("policy lock probe timed out");
              }
              return credentials(fixture.orgId());
            });

    Future<PlanGateDecisionService.DecisionResult> decision;
    try (var pool = Executors.newSingleThreadExecutor()) {
      decision =
          pool.submit(
              () ->
                  inTransaction(
                      () -> service.decide(fixture.runId(), fixture.approve(), fixture.actor())));
      assertThat(policyReached.await(20, TimeUnit.SECONDS)).isTrue();
      try {
        assertUpdateBlocked(
            "UPDATE app_user SET first_name='Blocked mutation' WHERE id=?", fixture.ownerId());
        assertUpdateBlocked("UPDATE org SET name='Blocked mutation' WHERE id=?", fixture.orgId());
        assertUpdateBlocked(
            "UPDATE project SET name='Blocked mutation' WHERE id=?", fixture.projectId());
      } finally {
        releasePolicy.countDown();
      }
      PlanGateDecisionService.DecisionResult result = decision.get(30, TimeUnit.SECONDS);
      assertThat(result.decision()).isEqualTo(PlanGateDecisionService.DecisionKind.APPROVE);
    }

    assertThat(
            jdbc.queryForObject(
                "SELECT first_name FROM app_user WHERE id=?", String.class, fixture.ownerId()))
        .isEqualTo("C03C");
    assertThat(
            jdbc.queryForObject("SELECT name FROM org WHERE id=?", String.class, fixture.orgId()))
        .isEqualTo("C03C Decision Org");
    assertThat(
            jdbc.queryForObject(
                "SELECT name FROM project WHERE id=?", String.class, fixture.projectId()))
        .isEqualTo("C03C Decision Project");
  }

  @Test
  void enforcedDecisionLocksRequirementAfterReportAndBeforePolicyThenResumesExactSnapshot()
      throws Exception {
    Fixture fixture = openEnforcedFixture(true);
    allowApproval(fixture);

    PlanGateDecisionService.DecisionResult result;
    try (var pool = Executors.newSingleThreadExecutor();
        Connection requirementBlocker = dataSource.getConnection()) {
      requirementBlocker.setAutoCommit(false);
      int blockerPid;
      try (var pid = requirementBlocker.prepareStatement("SELECT pg_backend_pid()")) {
        try (var rows = pid.executeQuery()) {
          assertThat(rows.next()).isTrue();
          blockerPid = rows.getInt(1);
        }
      }
      try (var lock =
          requirementBlocker.prepareStatement(
              "SELECT id FROM run_requirement_contract WHERE id=? FOR UPDATE")) {
        lock.setObject(1, fixture.requirementContractId());
        try (var rows = lock.executeQuery()) {
          assertThat(rows.next()).isTrue();
        }
      }

      Future<PlanGateDecisionService.DecisionResult> decision =
          pool.submit(
              () ->
                  inTransaction(
                      () -> service.decide(fixture.runId(), fixture.approve(), fixture.actor())));
      awaitBlockedBy(blockerPid);

      // Reaching the RequirementIR wait proves the mandatory lock prefix is already held.
      assertUpdateBlocked("UPDATE pipeline_run SET status=status WHERE run_id=?", fixture.runId());
      assertUpdateBlocked(
          "UPDATE gate_record SET status=status WHERE gate_key=?", "gate:plan:" + fixture.runId());
      assertUpdateBlocked(
          "UPDATE plan_gate_resume_outbox SET status=status WHERE run_id=?", fixture.runId());
      assertUpdateBlocked(
          "UPDATE plan_contract_gate_report SET report_digest=report_digest WHERE run_id=?",
          fixture.runId());
      // Mutable admission rows are deliberately not locked until RequirementIR is fenced.
      assertPolicyRowsLockable(fixture);
      verifyNoInteractions(apiKeys, managedAi, governance);

      requirementBlocker.commit();
      result = decision.get(30, TimeUnit.SECONDS);
    }

    assertThat(result.evidence().bindingMode()).isEqualTo("ENFORCED");
    assertThat(result.evidence().bindingStatus()).isEqualTo("BOUND");
    assertThat(result.evidence().requirementContractId())
        .isEqualTo(fixture.requirementContractId().toString());
    assertThat(result.evidence().requirementCompleteness()).isEqualTo("READY");
    assertThat(result.evidence().blockingIssueCount()).isZero();

    Instant claimedAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    PlanGateResumeStore.Lease lease =
        resumeStore
            .claimNext("requirement-lock-test", claimedAt, Duration.ofMinutes(1))
            .orElseThrow();
    assertThat(lease.runId()).isEqualTo(fixture.runId());
    resumeStore.preflight(lease, claimedAt.plusSeconds(1));
    PlanGateResumeStore.Handoff handoff = resumeStore.handoff(lease, claimedAt.plusSeconds(2));
    assertThat(handoff.run().getStatus()).isEqualTo("WORKING");
    assertThat(handoff.continuationPayload()).isEqualTo(CONTINUATION);
    assertCoherentTuple(fixture.runId(), "APPROVE", "APPROVED", "WORKING", "COMPLETED");
  }

  @Test
  void legacyLowercasePreAcknowledgementApprovesReplaysAndPassesResumeReceiptValidation() {
    Fixture fixture = openLegacyPreAcknowledgedFixture();
    allowApproval(fixture);
    Map<String, Object> legacyReport = contractReportRow(fixture.runId());

    PlanGateDecisionService.DecisionResult approved =
        inTransaction(() -> service.decide(fixture.runId(), fixture.approve(), fixture.actor()));

    assertThat(approved.evidence().contractAcknowledgementOutcome()).isEqualTo("ACKNOWLEDGED");
    assertExactLegacyAcknowledgementMetadata(approved.evidence(), legacyReport);
    DecisionEvidenceV1 parsed = DecisionEvidenceV1.parse(approved.evidence().canonicalJson());
    assertThat(parsed.canonicalJson()).isEqualTo(approved.evidence().canonicalJson());

    clearInvocations(apiKeys, managedAi, governance, events);
    PlanGateDecisionService.DecisionResult replayed =
        inTransaction(() -> service.decide(fixture.runId(), fixture.approve(), fixture.actor()));
    assertThat(replayed.replayed()).isTrue();
    assertThat(replayed.evidence().canonicalJson()).isEqualTo(approved.evidence().canonicalJson());
    verifyNoInteractions(apiKeys, managedAi, governance, events);

    Instant claimedAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    PlanGateResumeStore.Lease lease =
        resumeStore.claimNext("legacy-ack-test", claimedAt, Duration.ofMinutes(1)).orElseThrow();
    assertThat(lease.runId()).isEqualTo(fixture.runId());
    resumeStore.preflight(lease, claimedAt.plusSeconds(1));
    PlanGateResumeStore.Handoff handoff = resumeStore.handoff(lease, claimedAt.plusSeconds(2));
    assertThat(handoff.run().getStatus()).isEqualTo("WORKING");
    assertThat(handoff.continuationPayload()).isEqualTo(CONTINUATION);
    assertCoherentTuple(fixture.runId(), "APPROVE", "APPROVED", "WORKING", "COMPLETED");
  }

  @Test
  void legacyLowercasePreAcknowledgementDoesNotBlockRejectAndReceiptPreservesReportMetadata() {
    Fixture fixture = openLegacyPreAcknowledgedFixture();
    Map<String, Object> legacyReport = contractReportRow(fixture.runId());

    PlanGateDecisionService.DecisionResult rejected =
        inTransaction(
            () ->
                service.decide(
                    fixture.runId(), PlanApprovalService.Command.rejectLegacy(), fixture.actor()));

    assertThat(rejected.evidence().contractAcknowledgementOutcome()).isEqualTo("NOT_EVALUATED");
    assertExactLegacyAcknowledgementMetadata(rejected.evidence(), legacyReport);
    assertThat(DecisionEvidenceV1.parse(rejected.evidence().canonicalJson()).canonicalJson())
        .isEqualTo(rejected.evidence().canonicalJson());
    assertCoherentTuple(fixture.runId(), "REJECT", "REJECTED", "CANCELLED", "CANCELLED");
    assertContractReportUnchanged(fixture.runId(), legacyReport);
    verifyNoInteractions(apiKeys, managedAi, governance);
  }

  @Test
  void legacyLowercasePreAcknowledgementDoesNotBlockCancelAndReceiptPreservesReportMetadata() {
    Fixture fixture = openLegacyPreAcknowledgedFixture();
    Map<String, Object> legacyReport = contractReportRow(fixture.runId());

    PlanGateDecisionService.DecisionResult cancelled =
        inTransaction(
            () ->
                service.cancel(
                    fixture.runId(),
                    fixture.actor(),
                    PlanGateDecisionService.ResolutionSource.RUN_CANCEL_API,
                    "cancelled before approval"));

    assertThat(cancelled.evidence().contractAcknowledgementOutcome()).isEqualTo("NOT_EVALUATED");
    assertExactLegacyAcknowledgementMetadata(cancelled.evidence(), legacyReport);
    assertThat(DecisionEvidenceV1.parse(cancelled.evidence().canonicalJson()).canonicalJson())
        .isEqualTo(cancelled.evidence().canonicalJson());
    assertCoherentTuple(fixture.runId(), "CANCEL", "CANCELLED", "CANCELLED", "CANCELLED");
    assertContractReportUnchanged(fixture.runId(), legacyReport);
    verifyNoInteractions(apiKeys, managedAi, governance);
  }

  @Test
  void committedApprovalRejectsHardCapMutationWhenEstimateAndHardCapAreBothPopulated() {
    Fixture fixture = openFixture(false);
    jdbc.update("UPDATE pipeline_run SET hard_cap_usd=7.5 WHERE run_id=?", fixture.runId());
    allowApproval(fixture);

    PlanGateDecisionService.DecisionResult approved =
        inTransaction(() -> service.decide(fixture.runId(), fixture.approve(), fixture.actor()));

    assertThat(approved.evidence().estimateHighUsd()).isEqualTo("4.500000");
    assertThat(approved.evidence().hardCapUsd()).isEqualTo("7.500000");
    assertThat(
            jdbc.queryForObject(
                "SELECT hard_cap_usd FROM pipeline_run WHERE run_id=?",
                Double.class,
                fixture.runId()))
        .isEqualTo(7.5);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE pipeline_run SET hard_cap_usd=8.5 WHERE run_id=?", fixture.runId()))
        .rootCause()
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("plan decision");
    assertThat(
            jdbc.queryForObject(
                "SELECT hard_cap_usd FROM pipeline_run WHERE run_id=?",
                Double.class,
                fixture.runId()))
        .isEqualTo(7.5);
  }

  private Fixture openLegacyPreAcknowledgedFixture() {
    Fixture fixture = openFixture(true);
    jdbc.update(
        """
        UPDATE plan_contract_gate_report
           SET acknowledged_at=?, acknowledged_by_user_id=?, acknowledgement_text='breaking'
         WHERE run_id=?
        """,
        Timestamp.from(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS)),
        fixture.ownerId(),
        fixture.runId());
    assertThat(gateRow(fixture.runId()))
        .containsEntry("status", "PENDING")
        .containsEntry("admission_contract_version", null)
        .containsEntry("approval_evidence_json", null);
    return fixture;
  }

  private Map<String, Object> contractReportRow(String runId) {
    return jdbc.queryForMap(
        "SELECT report_digest,requires_typed_ack,acknowledged_at,acknowledged_by_user_id,"
            + " acknowledgement_text FROM plan_contract_gate_report WHERE run_id=?",
        runId);
  }

  private void assertExactLegacyAcknowledgementMetadata(
      DecisionEvidenceV1 evidence, Map<String, Object> legacyReport) {
    assertThat(evidence.contractGateReportDigest()).isEqualTo(legacyReport.get("report_digest"));
    assertThat(evidence.contractRequiresTypedAck())
        .isEqualTo(legacyReport.get("requires_typed_ack"));
    assertThat(evidence.contractAcknowledgedAt())
        .isEqualTo(((Timestamp) legacyReport.get("acknowledged_at")).toInstant().toString());
    assertThat(evidence.contractAcknowledgedByUserId())
        .isEqualTo(legacyReport.get("acknowledged_by_user_id"));
    assertThat(evidence.contractAcknowledgementText())
        .isEqualTo(legacyReport.get("acknowledgement_text"))
        .isEqualTo("breaking");
  }

  private void assertContractReportUnchanged(String runId, Map<String, Object> expected) {
    assertThat(contractReportRow(runId)).containsExactlyInAnyOrderEntriesOf(expected);
  }

  private Fixture openFixture(boolean withBreakingReport) {
    return openFixture(withBreakingReport, RequestedMode.LEGACY);
  }

  private Fixture openEnforcedFixture(boolean withBreakingReport) {
    return openFixture(withBreakingReport, RequestedMode.ENFORCED);
  }

  private Fixture openFixture(boolean withBreakingReport, RequestedMode bindingMode) {
    String runId = PREFIX + UUID.randomUUID();
    String ownerId = PREFIX + "owner-" + UUID.randomUUID();
    String orgId = PREFIX + "org-" + UUID.randomUUID();
    String projectId = PREFIX + "project-" + UUID.randomUUID();
    jdbc.update(
        "INSERT INTO org (id,name,domain_patterns,tier,created_by_user_id) VALUES (?,?,?,?,?)",
        orgId,
        "C03C Decision Org",
        "example.test",
        "ENTERPRISE",
        ownerId);
    jdbc.update(
        "INSERT INTO app_user"
            + " (id,email,first_name,last_name,password_hash,global_role,tier,status,org_id)"
            + " VALUES (?,?,?,?,?,?,?,?,?)",
        ownerId,
        ownerId + "@example.test",
        "C03C",
        "Owner",
        "hash",
        "USER",
        "ENTERPRISE",
        "ACTIVE",
        orgId);
    jdbc.update(
        "INSERT INTO project (id,org_id,name,created_by,ai_provider) VALUES (?,?,?,?,?)",
        projectId,
        orgId,
        "C03C Decision Project",
        ownerId,
        "ANTHROPIC");
    jdbc.update(
        """
        INSERT INTO pipeline_run
          (run_id,status,started_at,user_request,plan_json,credits_charged,cost_estimate_high,
           created_by_user_id,org_id,project_id,ai_provider)
        VALUES (?, 'PLANNING', ?, 'Return 404 when an order is absent.', ?, 3, 4.5, ?, ?, ?,
                'anthropic')
        """,
        runId,
        Timestamp.from(Instant.now()),
        PLAN_JSON,
        ownerId,
        orgId,
        projectId);

    UUID requirementContractId = null;
    if (bindingMode == RequestedMode.ENFORCED) {
      requirementContractId = insertReadyRequirementContract(runId);
    }

    String reportDigest = null;
    if (withBreakingReport) {
      String reportJson = "{\"consumerBreaking\":true}";
      reportDigest = DecisionEvidenceV1.sha256(reportJson);
      jdbc.update(
          """
          INSERT INTO plan_contract_gate_report
            (run_id,report_json,report_digest,requires_typed_ack,contract_count,created_at)
          VALUES (?,?,?,TRUE,1,?)
          """,
          runId,
          reportJson,
          reportDigest,
          Timestamp.from(Instant.now()));
    }
    opener.open(
        new PlanGateOpenService.OpenCommand(
            runId,
            PLAN_JSON,
            ExactPlanFingerprint.sha256(PLAN_JSON),
            CONTINUATION,
            1,
            reportDigest,
            bindingMode));

    AppUserDetails actor =
        new AppUserDetails(
            AppUser.builder()
                .id(ownerId)
                .email(ownerId + "@example.test")
                .globalRole("USER")
                .status("ACTIVE")
                .build(),
            null);
    PlanApprovalService.Command approve =
        new PlanApprovalService.Command(
            true,
            3,
            "REALTIME",
            ExactPlanFingerprint.sha256(PLAN_JSON),
            withBreakingReport ? "BREAKING" : null,
            reportDigest);
    return new Fixture(runId, ownerId, orgId, projectId, actor, approve, requirementContractId);
  }

  private UUID insertReadyRequirementContract(String runId) {
    String source = "Return 404 when an order is absent.";
    Plan plan =
        Plan.builder()
            .requirements(
                List.of(
                    new RequirementSpec("R1", source, RequirementSpec.RequirementKind.FUNCTIONAL)))
            .tasks(
                List.of(
                    PlannedTask.builder()
                        .id("T1")
                        .title("Implement order lookup")
                        .description(source)
                        .meetsRequirements(List.of("R1"))
                        .build()))
            .build();
    RequirementIrDocument document = new RequirementIrCompiler().compile(source, plan);
    RequirementIrCodec codec = new RequirementIrCodec(objectMapper);
    String canonicalArtifact = codec.writeCanonical(document);
    String artifactDigest = RequirementContractDigests.artifactDigest(canonicalArtifact);
    String planFingerprint = ExactPlanFingerprint.sha256(PLAN_JSON);
    long blockingIssues = document.issues().stream().filter(issue -> issue.blocking()).count();
    assertThat(document.completeness()).isEqualTo(RequirementIrDocument.Completeness.READY);
    assertThat(blockingIssues).isZero();
    return requirements
        .saveAndFlush(
            RunRequirementContract.builder()
                .runId(runId)
                .schemaVersion(document.schemaVersion())
                .compilerFingerprint(document.compilerFingerprint())
                .sourceSha256(document.sourceSha256())
                .exactPlanFingerprint(planFingerprint)
                .canonicalArtifactJson(canonicalArtifact)
                .artifactDigest(artifactDigest)
                .boundDigest(
                    RequirementContractDigests.boundDigest(
                        planFingerprint, document.sourceSha256(), artifactDigest))
                .completeness(document.completeness())
                .requirementCount(document.requirements().size())
                .acceptanceCriterionCount(document.acceptanceCriteria().size())
                .taskBindingCount(document.taskBindings().size())
                .issueCount(document.issues().size())
                .blockingIssueCount(Math.toIntExact(blockingIssues))
                .build())
        .getId();
  }

  private void allowApproval(Fixture fixture) {
    when(apiKeys.resolveWithProvenance(fixture.ownerId())).thenReturn(credentials(fixture.orgId()));
    when(managedAi.assertCanApproveEstimatedSpend(fixture.ownerId(), fixture.orgId(), false, 4.5))
        .thenReturn(
            new ManagedAiUsageService.ApprovalEvaluation(
                "ALLOWED", "MANAGED", 2000L, 1900L, 100L, 25.0, 23.0));
    allowGovernance(fixture);
  }

  private void allowGovernance(Fixture fixture) {
    when(governance.evaluateApprovalCost(eq(fixture.orgId()), eq(4.5), any(AppUserDetails.class)))
        .thenReturn(
            new GovernanceGuard.OrgCostEvaluation(
                "ALLOWED", new BigDecimal("10.00"), 4.5, "BELOW_THRESHOLD"));
    when(governance.evaluateProjectSpendCap(fixture.projectId(), 4.5))
        .thenReturn(
            new GovernanceGuard.ProjectSpendEvaluation(
                "ALLOWED",
                new BigDecimal("25.00"),
                BigDecimal.ZERO,
                new BigDecimal("4.5"),
                "WITHIN_CAP"));
  }

  private static ApiKeyResolver.CredentialResolution credentials(String orgId) {
    return new ApiKeyResolver.CredentialResolution(ResolvedKeys.SYSTEM_ONLY, orgId, Map.of());
  }

  private <T> T inTransaction(Supplier<T> work) {
    return transactions.execute(ignored -> work.get());
  }

  private void assertUpdateBlocked(String sql, String id) throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      connection.createStatement().execute("SET lock_timeout='250ms'");
      assertThatThrownBy(
              () -> {
                try (var update = connection.prepareStatement(sql)) {
                  update.setString(1, id);
                  update.executeUpdate();
                }
              })
          .isInstanceOfSatisfying(
              SQLException.class,
              failure -> {
                assertThat(failure.getSQLState()).isEqualTo("55P03");
                assertThat(failure.getMessage()).contains("lock timeout");
              });
    }
  }

  private void assertPolicyRowsLockable(Fixture fixture) throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        connection.createStatement().execute("SET LOCAL lock_timeout='250ms'");
        assertSelectForUpdateSucceeds(
            connection, "SELECT id FROM app_user WHERE id=? FOR UPDATE NOWAIT", fixture.ownerId());
        assertSelectForUpdateSucceeds(
            connection, "SELECT id FROM org WHERE id=? FOR UPDATE NOWAIT", fixture.orgId());
        assertSelectForUpdateSucceeds(
            connection, "SELECT id FROM project WHERE id=? FOR UPDATE NOWAIT", fixture.projectId());
      } finally {
        connection.rollback();
      }
    }
  }

  private static void assertSelectForUpdateSucceeds(Connection connection, String sql, String id)
      throws SQLException {
    try (var statement = connection.prepareStatement(sql)) {
      statement.setString(1, id);
      try (var rows = statement.executeQuery()) {
        assertThat(rows.next()).isTrue();
      }
    }
  }

  private List<RaceOutcome> race(
      Supplier<PlanGateDecisionService.DecisionResult> first,
      Supplier<PlanGateDecisionService.DecisionResult> second)
      throws Exception {
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    List<RaceOutcome> outcomes = java.util.Collections.synchronizedList(new ArrayList<>());
    try (var pool = Executors.newFixedThreadPool(2)) {
      pool.submit(() -> runRacer(first, ready, start, outcomes));
      pool.submit(() -> runRacer(second, ready, start, outcomes));
      assertThat(ready.await(20, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      pool.shutdown();
      assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
    }
    assertThat(outcomes).hasSize(2);
    return List.copyOf(outcomes);
  }

  private List<RaceOutcome> orderedRace(
      String runId,
      String winnerGateStatus,
      Supplier<PlanGateDecisionService.DecisionResult> winner,
      Supplier<PlanGateDecisionService.DecisionResult> loser)
      throws Exception {
    int advisoryKey = Math.floorMod(runId.hashCode(), Integer.MAX_VALUE);
    String trigger = installAdvisoryPauseTrigger(runId, winnerGateStatus, advisoryKey);
    List<RaceOutcome> outcomes = java.util.Collections.synchronizedList(new ArrayList<>());
    // Declare the connection last so exceptional cleanup releases its session advisory lock before
    // ExecutorService.close waits for a deliberately blocked winner.
    try (var pool = Executors.newFixedThreadPool(2);
        Connection blocker = dataSource.getConnection()) {
      try (var lock = blocker.prepareStatement("SELECT pg_advisory_lock(0, ?)")) {
        lock.setInt(1, advisoryKey);
        lock.executeQuery();
      }
      Future<?> winnerFuture = pool.submit(() -> runOperation(winner, outcomes));
      int winnerPid = awaitAdvisoryWaiter(advisoryKey);
      Future<?> loserFuture = pool.submit(() -> runOperation(loser, outcomes));
      awaitBlockedBy(winnerPid);
      try (var unlock = blocker.prepareStatement("SELECT pg_advisory_unlock(0, ?)")) {
        unlock.setInt(1, advisoryKey);
        unlock.executeQuery();
      }
      winnerFuture.get(30, TimeUnit.SECONDS);
      loserFuture.get(30, TimeUnit.SECONDS);
    } finally {
      dropAdvisoryPauseTrigger(trigger);
    }
    assertThat(outcomes).hasSize(2);
    return List.copyOf(outcomes);
  }

  private void runOperation(
      Supplier<PlanGateDecisionService.DecisionResult> operation, List<RaceOutcome> outcomes) {
    try {
      outcomes.add(new RaceOutcome(inTransaction(operation), null));
    } catch (Throwable failure) {
      outcomes.add(new RaceOutcome(null, failure));
    }
  }

  private int awaitAdvisoryWaiter(int advisoryKey) throws InterruptedException {
    Instant deadline = Instant.now().plusSeconds(20);
    while (Instant.now().isBefore(deadline)) {
      List<Integer> waiters =
          jdbc.queryForList(
              "SELECT pid FROM pg_locks WHERE locktype='advisory'"
                  + " AND classid=CAST(0 AS oid) AND objid=CAST(? AS oid) AND NOT granted",
              Integer.class,
              advisoryKey);
      if (!waiters.isEmpty()) return waiters.getFirst();
      Thread.sleep(10);
    }
    throw new IllegalStateException("ordered decision race did not reach the persistence barrier");
  }

  private void awaitBlockedBy(int blockerPid) throws InterruptedException {
    Instant deadline = Instant.now().plusSeconds(20);
    while (Instant.now().isBefore(deadline)) {
      Integer blocked =
          jdbc.queryForObject(
              "SELECT count(*) FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid))",
              Integer.class,
              blockerPid);
      if (blocked != null && blocked > 0) return;
      Thread.sleep(10);
    }
    throw new IllegalStateException("losing decision did not block behind the ordered winner");
  }

  private void assertOrderedWinner(
      List<RaceOutcome> outcomes,
      PlanGateDecisionService.DecisionKind expectedWinner,
      boolean loserReplaysWinner) {
    assertThat(outcomes.stream().filter(RaceOutcome::wroteDecision))
        .singleElement()
        .satisfies(outcome -> assertThat(outcome.result().decision()).isEqualTo(expectedWinner));
    if (loserReplaysWinner) {
      assertThat(outcomes.stream().filter(outcome -> outcome.result() != null))
          .hasSize(2)
          .allSatisfy(
              outcome ->
                  assertThat(outcome.result().decision())
                      .isEqualTo(PlanGateDecisionService.DecisionKind.APPROVE));
      assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome.failure()).isNull());
    } else {
      assertThat(outcomes.stream().map(RaceOutcome::failure).filter(java.util.Objects::nonNull))
          .singleElement()
          .satisfies(this::assertConflict);
    }
  }

  private void runRacer(
      Supplier<PlanGateDecisionService.DecisionResult> operation,
      CountDownLatch ready,
      CountDownLatch start,
      List<RaceOutcome> outcomes) {
    ready.countDown();
    try {
      if (!start.await(20, TimeUnit.SECONDS)) {
        outcomes.add(new RaceOutcome(null, new IllegalStateException("race start timed out")));
        return;
      }
      outcomes.add(new RaceOutcome(inTransaction(operation), null));
    } catch (Throwable failure) {
      outcomes.add(new RaceOutcome(null, failure));
    }
  }

  private void assertConflict(Throwable failure) {
    Throwable cursor = failure;
    while (cursor != null && !(cursor instanceof ResponseStatusException)) {
      cursor = cursor.getCause();
    }
    assertThat(cursor).isInstanceOf(ResponseStatusException.class);
    assertThat(((ResponseStatusException) cursor).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
  }

  private void assertPersistedWinnerIsCoherent(String runId) {
    String decision =
        jdbc.queryForObject(
            "SELECT decision_kind FROM gate_record WHERE run_id=?", String.class, runId);
    if ("APPROVE".equals(decision)) {
      assertCoherentTuple(runId, "APPROVE", "APPROVED", "APPROVAL_RESUMING", "READY");
    } else if ("REJECT".equals(decision)) {
      assertCoherentTuple(runId, "REJECT", "REJECTED", "CANCELLED", "CANCELLED");
    } else {
      assertThat(decision).isEqualTo("CANCEL");
      assertCoherentTuple(runId, "CANCEL", "CANCELLED", "CANCELLED", "CANCELLED");
    }
  }

  private void assertCoherentTuple(
      String runId, String decision, String gateStatus, String runStatus, String outboxStatus) {
    Map<String, Object> tuple =
        jdbc.queryForMap(
            "SELECT g.decision_kind,g.status AS gate_status,r.status AS run_status,o.status AS"
                + " outbox_status FROM gate_record g JOIN pipeline_run r ON r.run_id=g.run_id JOIN"
                + " plan_gate_resume_outbox o ON o.run_id=g.run_id WHERE g.run_id=?",
            runId);
    assertThat(tuple)
        .containsEntry("decision_kind", decision)
        .containsEntry("gate_status", gateStatus)
        .containsEntry("run_status", runStatus)
        .containsEntry("outbox_status", outboxStatus);
  }

  private void assertUndecidedTuple(String runId, boolean reportExpected) {
    Map<String, Object> tuple =
        jdbc.queryForMap(
            "SELECT g.status AS gate_status,g.decision_kind,g.approval_evidence_json,"
                + "g.approval_evidence_digest,r.status AS run_status,o.status AS outbox_status"
                + " FROM gate_record g JOIN pipeline_run r ON r.run_id=g.run_id JOIN"
                + " plan_gate_resume_outbox o ON o.run_id=g.run_id WHERE g.run_id=?",
            runId);
    assertThat(tuple)
        .containsEntry("gate_status", "PENDING")
        .containsEntry("run_status", "AWAITING_APPROVAL")
        .containsEntry("outbox_status", "WAITING");
    assertThat(tuple.get("decision_kind")).isNull();
    assertThat(tuple.get("approval_evidence_json")).isNull();
    assertThat(tuple.get("approval_evidence_digest")).isNull();
    if (reportExpected) {
      Map<String, Object> report =
          jdbc.queryForMap(
              "SELECT acknowledged_at,acknowledged_by_user_id,acknowledgement_text FROM"
                  + " plan_contract_gate_report WHERE run_id=?",
              runId);
      assertThat(report.values()).containsOnlyNulls();
    }
  }

  private Map<String, Object> gateRow(String runId) {
    return jdbc.queryForMap("SELECT * FROM gate_record WHERE run_id=?", runId);
  }

  private String installFailureTrigger(ApprovalWrite write, String runId) {
    String suffix = UUID.randomUUID().toString().replace("-", "");
    String function = "fail_c03c_" + suffix;
    String trigger = "trg_fail_c03c_" + suffix;
    jdbc.execute(
        "CREATE FUNCTION "
            + function
            + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.run_id = '"
            + runId
            + "' AND ("
            + write.predicate
            + ") THEN RAISE EXCEPTION 'injected C03C approval failure at "
            + write.table
            + "'; END IF; RETURN NEW; END; $$");
    jdbc.execute(
        "CREATE TRIGGER "
            + trigger
            + " BEFORE UPDATE ON "
            + write.table
            + " FOR EACH ROW EXECUTE FUNCTION "
            + function
            + "()");
    return trigger + ":" + function;
  }

  private void dropFailureTrigger(ApprovalWrite write, String identity) {
    String[] names = identity.split(":", 2);
    jdbc.execute("DROP TRIGGER IF EXISTS " + names[0] + " ON " + write.table);
    jdbc.execute("DROP FUNCTION IF EXISTS " + names[1] + "()");
  }

  private String installFailureTrigger(TerminalWrite write, String runId, String decisionLabel) {
    String suffix = UUID.randomUUID().toString().replace("-", "");
    String function = "fail_c03c_" + suffix;
    String trigger = "trg_fail_c03c_" + suffix;
    jdbc.execute(
        "CREATE FUNCTION "
            + function
            + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.run_id = '"
            + runId
            + "' AND ("
            + write.predicate
            + ") THEN RAISE EXCEPTION 'injected C03C "
            + decisionLabel
            + " failure at "
            + write.table
            + "'; END IF; RETURN NEW; END; $$");
    jdbc.execute(
        "CREATE TRIGGER "
            + trigger
            + " BEFORE UPDATE ON "
            + write.table
            + " FOR EACH ROW EXECUTE FUNCTION "
            + function
            + "()");
    return trigger + ":" + function;
  }

  private void dropFailureTrigger(TerminalWrite write, String identity) {
    String[] names = identity.split(":", 2);
    jdbc.execute("DROP TRIGGER IF EXISTS " + names[0] + " ON " + write.table);
    jdbc.execute("DROP FUNCTION IF EXISTS " + names[1] + "()");
  }

  private String installAdvisoryPauseTrigger(String runId, String status, int advisoryKey) {
    String suffix = UUID.randomUUID().toString().replace("-", "");
    String function = "pause_c03c_" + suffix;
    String trigger = "trg_pause_c03c_" + suffix;
    jdbc.execute(
        "CREATE FUNCTION "
            + function
            + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.run_id = '"
            + runId
            + "' AND NEW.status = '"
            + status
            + "' THEN PERFORM pg_advisory_xact_lock(0, "
            + advisoryKey
            + "); END IF; RETURN NEW; END; $$");
    jdbc.execute(
        "CREATE TRIGGER "
            + trigger
            + " BEFORE UPDATE ON gate_record FOR EACH ROW EXECUTE FUNCTION "
            + function
            + "()");
    return trigger + ":" + function;
  }

  private void dropAdvisoryPauseTrigger(String identity) {
    String[] names = identity.split(":", 2);
    jdbc.execute("DROP TRIGGER IF EXISTS " + names[0] + " ON gate_record");
    jdbc.execute("DROP FUNCTION IF EXISTS " + names[1] + "()");
  }

  private enum ApprovalWrite {
    ACKNOWLEDGEMENT("plan_contract_gate_report", "NEW.acknowledged_at IS NOT NULL"),
    GATE_RECEIPT("gate_record", "NEW.status = 'APPROVED'"),
    CONTINUATION_OUTBOX("plan_gate_resume_outbox", "NEW.status = 'READY'"),
    RUN_TRANSITION("pipeline_run", "NEW.status = 'APPROVAL_RESUMING'");

    private final String table;
    private final String predicate;

    ApprovalWrite(String table, String predicate) {
      this.table = table;
      this.predicate = predicate;
    }
  }

  private enum TerminalWrite {
    GATE_RECEIPT("gate_record", "NEW.status IN ('REJECTED','CANCELLED')"),
    CONTINUATION_OUTBOX("plan_gate_resume_outbox", "NEW.status = 'CANCELLED'"),
    RUN_TRANSITION("pipeline_run", "NEW.status = 'CANCELLED'");

    private final String table;
    private final String predicate;

    TerminalWrite(String table, String predicate) {
      this.table = table;
      this.predicate = predicate;
    }
  }

  private record Fixture(
      String runId,
      String ownerId,
      String orgId,
      String projectId,
      AppUserDetails actor,
      PlanApprovalService.Command approve,
      UUID requirementContractId) {}

  private record RaceOutcome(PlanGateDecisionService.DecisionResult result, Throwable failure) {
    boolean wroteDecision() {
      return result != null && !result.replayed();
    }
  }
}

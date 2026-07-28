package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import com.navneet.executespec.agent.requirement.ExactPlanFingerprint;
import com.navneet.executespec.agent.requirement.repository.RunRequirementContractRepository;
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
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class PlanGateResumeStoreTest {
  private static final String RUN_ID = "run-1";
  private static final String GATE_KEY = "gate:plan:" + RUN_ID;
  private static final String PAYLOAD =
      "{\"schemaVersion\":1,\"localRun\":false,\"requireContinuationConfirmation\":false,"
          + "\"requireContinuationBenchmarkPrior\":false,\"pinnedModel\":null,"
          + "\"lowComplexityWorkerStartTierOverride\":null}";
  private static final String PAYLOAD_SHA = PlanGateOpenService.sha256(PAYLOAD);

  @Mock PipelineRunRepository runRepository;
  @Mock GateRepository gateRepository;
  @Mock PlanGateResumeOutboxRepository outboxRepository;
  @Mock RunRequirementContractRepository requirementContractRepository;
  @Mock EntityManager entityManager;

  PlanGateResumeStore store;

  @BeforeEach
  void setUp() {
    store =
        new PlanGateResumeStore(
            runRepository,
            gateRepository,
            outboxRepository,
            entityManager,
            new PlanGateResumeIntegrityValidator(requirementContractRepository));
  }

  @Test
  void claimAssignsOwnerTokenExpiryAndIncrementsAttempt() {
    Instant now = Instant.parse("2026-07-28T01:00:00Z");
    PlanGateResumeOutbox outbox = readyOutbox();
    when(outboxRepository.findNextClaimableForUpdate(now)).thenReturn(Optional.of(outbox));

    PlanGateResumeStore.Lease lease =
        store.claimNext("node-a", now, Duration.ofMinutes(2)).orElseThrow();

    assertThat(lease.owner()).isEqualTo("node-a");
    assertThat(lease.token()).isNotNull();
    assertThat(lease.attempt()).isEqualTo(1);
    assertThat(outbox.getStatus()).isEqualTo("PROCESSING");
    assertThat(outbox.getLeaseExpiresAt()).isEqualTo(now.plusSeconds(120));
    verify(outboxRepository).saveAndFlush(outbox);
  }

  @Test
  void expiredLeaseCannotRetryOrDeadLetterAfterAnotherOwnerMayReclaim() {
    Instant now = Instant.parse("2026-07-28T01:00:00Z");
    PlanGateResumeOutbox outbox = processingOutbox(now.minusSeconds(1));
    PlanGateResumeStore.Lease lease = lease(outbox);
    PipelineRun run = approvedRun();
    GateRecord gate = approvedGate(run);
    when(runRepository.findByIdForPlanGateOpen(RUN_ID)).thenReturn(Optional.of(run));
    when(gateRepository.findByIdForPlanGateOpen(GATE_KEY)).thenReturn(Optional.of(gate));
    when(outboxRepository.findByIdForUpdate(RUN_ID)).thenReturn(Optional.of(outbox));

    assertThat(store.retry(lease, "temporary", now.plusSeconds(2), now)).isFalse();
    verify(outboxRepository, never()).saveAndFlush(outbox);
  }

  @Test
  void heartbeatUsesOwnerTokenAndExpiryFence() {
    Instant now = Instant.parse("2026-07-28T01:00:00Z");
    PlanGateResumeOutbox outbox = processingOutbox(now.plusSeconds(30));
    PlanGateResumeStore.Lease lease = lease(outbox);
    when(outboxRepository.heartbeat(RUN_ID, "node-a", lease.token(), now, now.plusSeconds(120)))
        .thenReturn(0);

    assertThat(store.heartbeat(lease, now, Duration.ofMinutes(2))).isFalse();
  }

  @Test
  void preflightValidatesExactLeasedReceiptWithoutMutatingDeliveryState() {
    Instant now = Instant.parse("2026-07-28T01:00:00Z");
    PipelineRun run = approvedRun();
    GateRecord gate = approvedGate(run);
    PlanGateResumeOutbox outbox = processingOutbox(now.plusSeconds(60));
    PlanGateResumeStore.Lease lease = lease(outbox);
    when(runRepository.findByIdForPlanGateOpen(RUN_ID)).thenReturn(Optional.of(run));
    when(gateRepository.findByIdForPlanGateOpen(GATE_KEY)).thenReturn(Optional.of(gate));
    when(outboxRepository.findByIdForUpdate(RUN_ID)).thenReturn(Optional.of(outbox));

    store.preflight(lease, now);

    assertThat(run.getStatus()).isEqualTo("APPROVAL_RESUMING");
    assertThat(outbox.getStatus()).isEqualTo("PROCESSING");
    assertThat(outbox.getLeaseToken()).isEqualTo(lease.token());
    verify(runRepository, never()).save(run);
    verify(outboxRepository, never()).saveAndFlush(outbox);
  }

  @Test
  void handoffAtomicallyMovesRunAndOutboxOnlyForExactApprovalReceipt() {
    Instant now = Instant.parse("2026-07-28T01:00:00Z");
    PipelineRun run = approvedRun();
    GateRecord gate = approvedGate(run);
    PlanGateResumeOutbox outbox = processingOutbox(now.plusSeconds(60));
    PlanGateResumeStore.Lease lease = lease(outbox);
    when(runRepository.findByIdForPlanGateOpen(RUN_ID)).thenReturn(Optional.of(run));
    when(gateRepository.findByIdForPlanGateOpen(GATE_KEY)).thenReturn(Optional.of(gate));
    when(outboxRepository.findByIdForUpdate(RUN_ID)).thenReturn(Optional.of(outbox));

    PlanGateResumeStore.Handoff result = store.handoff(lease, now);

    assertThat(result.continuationPayload()).isEqualTo(PAYLOAD);
    assertThat(run.getStatus()).isEqualTo("WORKING");
    assertThat(run.getCurrentGate()).isNull();
    assertThat(PlanGateResumeDispatcher.isQueuedHandoffOwner(run.getExecutingInstance())).isTrue();
    assertThat(run.getExecutionHeartbeat()).isEqualTo(now);
    assertThat(outbox.getStatus()).isEqualTo("COMPLETED");
    assertThat(outbox.getLeaseToken()).isNull();
    assertThat(outbox.getCompletedAt()).isEqualTo(now);
    verify(runRepository).save(run);
    verify(outboxRepository).saveAndFlush(outbox);
  }

  @Test
  void rejectedExecutorReleasesOnlyTheExactDurableExecutionOwner() {
    Instant now = Instant.parse("2026-07-28T01:00:00Z");
    PipelineRun run = approvedRun();
    GateRecord gate = approvedGate(run);
    PlanGateResumeOutbox outbox = processingOutbox(now.plusSeconds(60));
    PlanGateResumeStore.Lease lease = lease(outbox);
    when(runRepository.findByIdForPlanGateOpen(RUN_ID)).thenReturn(Optional.of(run));
    when(gateRepository.findByIdForPlanGateOpen(GATE_KEY)).thenReturn(Optional.of(gate));
    when(gateRepository.findFirstByRunIdAndGateType(RUN_ID, "PLAN")).thenReturn(Optional.of(gate));
    when(outboxRepository.findByIdForUpdate(RUN_ID)).thenReturn(Optional.of(outbox));
    PlanGateResumeStore.Handoff handoff = store.handoff(lease, queuedOwner("node-a"), now);

    assertThat(
            store.releaseRejectedHandoff(
                handoff,
                "PLAN_GATE_POST_HANDOFF_DISPATCH_FAILED:RejectedExecutionException:stopping",
                now.plusSeconds(2),
                now.plusSeconds(1)))
        .isTrue();

    assertThat(run.getStatus()).isEqualTo("WORKING");
    assertThat(run.getCurrentGate()).isNull();
    assertThat(run.getExecutingInstance()).isEqualTo(handoff.executionOwner());
    assertThat(run.getExecutionHeartbeat()).isNull();
    assertThat(outbox.getStatus()).isEqualTo("COMPLETED");
    assertThat(outbox.getCompletedAt()).isEqualTo(now);
    assertThat(outbox.getNextRetryAt()).isEqualTo(now.plusSeconds(2));
    assertThat(outbox.getLastError()).contains("RejectedExecutionException:stopping");
  }

  @Test
  void rejectedExecutorCannotReleaseAChangedExecutionOwner() {
    Instant now = Instant.parse("2026-07-28T01:00:00Z");
    PipelineRun run = approvedRun();
    GateRecord gate = approvedGate(run);
    PlanGateResumeOutbox outbox = processingOutbox(now.plusSeconds(60));
    PlanGateResumeStore.Lease lease = lease(outbox);
    when(runRepository.findByIdForPlanGateOpen(RUN_ID)).thenReturn(Optional.of(run));
    when(gateRepository.findByIdForPlanGateOpen(GATE_KEY)).thenReturn(Optional.of(gate));
    when(gateRepository.findFirstByRunIdAndGateType(RUN_ID, "PLAN")).thenReturn(Optional.of(gate));
    when(outboxRepository.findByIdForUpdate(RUN_ID)).thenReturn(Optional.of(outbox));
    PlanGateResumeStore.Handoff handoff = store.handoff(lease, queuedOwner("node-a"), now);
    run.setExecutingInstance("node-b");

    assertThat(
            store.releaseRejectedHandoff(
                handoff, "dispatch failed", now.plusSeconds(2), now.plusSeconds(1)))
        .isFalse();
    assertThat(run.getStatus()).isEqualTo("WORKING");
    assertThat(run.getExecutingInstance()).isEqualTo("node-b");
    assertThat(outbox.getStatus()).isEqualTo("COMPLETED");
  }

  @Test
  void staleCompletedHandoffIsClaimedWithoutGenericCrashRecovery() {
    Instant now = Instant.parse("2026-07-28T01:05:00Z");
    PipelineRun run = approvedRun();
    GateRecord gate = approvedGate(run);
    PlanGateResumeOutbox outbox = processingOutbox(now.plusSeconds(60));
    PlanGateResumeStore.Lease lease = lease(outbox);
    when(runRepository.findByIdForPlanGateOpen(RUN_ID)).thenReturn(Optional.of(run));
    when(gateRepository.findByIdForPlanGateOpen(GATE_KEY)).thenReturn(Optional.of(gate));
    when(gateRepository.findFirstByRunIdAndGateType(RUN_ID, "PLAN")).thenReturn(Optional.of(gate));
    when(outboxRepository.findByIdForUpdate(RUN_ID)).thenReturn(Optional.of(outbox));
    PlanGateResumeStore.Handoff staleQueued =
        store.handoff(lease, queuedOwner("dead-node"), now.minusSeconds(180));

    PlanGateResumeStore.Handoff recovered =
        store
            .claimStaleCompletedHandoff(RUN_ID, queuedOwner("new-node"), now.minusSeconds(120), now)
            .orElseThrow();

    assertThat(recovered.executionOwner()).isEqualTo(queuedOwner("new-node"));
    assertThat(run.getStatus()).isEqualTo("WORKING");
    assertThat(run.getExecutingInstance()).isEqualTo(queuedOwner("new-node"));
    assertThat(run.getExecutionHeartbeat()).isEqualTo(now);
    assertThat(outbox.getStatus()).isEqualTo("COMPLETED");
    assertThat(outbox.getAttemptCount()).isEqualTo(2);
    assertThat(outbox.getLastError()).isNull();

    assertThat(store.startRegisteredHandoff(staleQueued, "runtime-a", now.plusSeconds(1)))
        .isFalse();
    assertThat(store.startRegisteredHandoff(recovered, "runtime-b", now.plusSeconds(1))).isTrue();
    assertThat(run.getExecutingInstance()).isEqualTo("runtime-b");
    assertThat(run.getExecutionHeartbeat()).isEqualTo(now.plusSeconds(1));
  }

  @Test
  void runtimeStartedWorkerIsNeverClaimedByCompletedHandoffDispatcher() {
    Instant now = Instant.parse("2026-07-28T01:05:00Z");
    PipelineRun run = approvedRun();
    GateRecord gate = approvedGate(run);
    PlanGateResumeOutbox outbox = processingOutbox(now.plusSeconds(60));
    PlanGateResumeStore.Lease lease = lease(outbox);
    when(runRepository.findByIdForPlanGateOpen(RUN_ID)).thenReturn(Optional.of(run));
    when(gateRepository.findByIdForPlanGateOpen(GATE_KEY)).thenReturn(Optional.of(gate));
    when(gateRepository.findFirstByRunIdAndGateType(RUN_ID, "PLAN")).thenReturn(Optional.of(gate));
    when(outboxRepository.findByIdForUpdate(RUN_ID)).thenReturn(Optional.of(outbox));
    PlanGateResumeStore.Handoff queued =
        store.handoff(lease, queuedOwner("dead-node"), now.minusSeconds(180));
    assertThat(store.startRegisteredHandoff(queued, "runtime-started-node", now.minusSeconds(170)))
        .isTrue();

    assertThat(
            store.claimStaleCompletedHandoff(
                RUN_ID, queuedOwner("new-node"), now.minusSeconds(120), now))
        .isEmpty();
    assertThat(run.getExecutingInstance()).isEqualTo("runtime-started-node");
    assertThat(outbox.getAttemptCount()).isEqualTo(1);
  }

  @Test
  void exhaustedStaleCompletedHandoffFailsInsteadOfStrandingWorkingForever() {
    Instant now = Instant.parse("2026-07-28T01:05:00Z");
    PipelineRun run = approvedRun();
    GateRecord gate = approvedGate(run);
    PlanGateResumeOutbox outbox = processingOutbox(now.plusSeconds(60));
    outbox.setMaxAttempts(1);
    PlanGateResumeStore.Lease lease = lease(outbox);
    when(runRepository.findByIdForPlanGateOpen(RUN_ID)).thenReturn(Optional.of(run));
    when(gateRepository.findByIdForPlanGateOpen(GATE_KEY)).thenReturn(Optional.of(gate));
    when(gateRepository.findFirstByRunIdAndGateType(RUN_ID, "PLAN")).thenReturn(Optional.of(gate));
    when(outboxRepository.findByIdForUpdate(RUN_ID)).thenReturn(Optional.of(outbox));
    store.handoff(lease, queuedOwner("dead-node"), now.minusSeconds(180));

    assertThat(
            store.claimStaleCompletedHandoff(
                RUN_ID, queuedOwner("new-node"), now.minusSeconds(120), now))
        .isEmpty();

    assertThat(run.getStatus()).isEqualTo("FAILED");
    assertThat(run.getFailureReason()).isEqualTo("PLAN_GATE_POST_HANDOFF_ATTEMPTS_EXHAUSTED");
    assertThat(run.getExecutingInstance()).isNull();
    assertThat(run.getExecutionHeartbeat()).isNull();
    assertThat(run.getCompletedAt()).isEqualTo(now);
    assertThat(outbox.getStatus()).isEqualTo("COMPLETED");
    assertThat(outbox.getLastError()).isEqualTo("PLAN_GATE_POST_HANDOFF_ATTEMPTS_EXHAUSTED");
  }

  @Test
  void changedPlanIsPoisonEvenForLegacyRequirementBinding() {
    Instant now = Instant.parse("2026-07-28T01:00:00Z");
    PipelineRun run = approvedRun();
    GateRecord gate = approvedGate(run);
    run.setPlanJson("{\"changed\":true}");
    PlanGateResumeOutbox outbox = processingOutbox(now.plusSeconds(60));
    PlanGateResumeStore.Lease lease = lease(outbox);
    when(runRepository.findByIdForPlanGateOpen(RUN_ID)).thenReturn(Optional.of(run));
    when(gateRepository.findByIdForPlanGateOpen(GATE_KEY)).thenReturn(Optional.of(gate));
    when(outboxRepository.findByIdForUpdate(RUN_ID)).thenReturn(Optional.of(outbox));

    assertThatThrownBy(() -> store.handoff(lease, now))
        .isInstanceOf(PlanGateResumeStore.PoisonMessageException.class)
        .hasMessage("PLAN_GATE_RECEIPT_PLAN_CHANGED");
    assertThat(run.getStatus()).isEqualTo("APPROVAL_RESUMING");
    assertThat(outbox.getStatus()).isEqualTo("PROCESSING");
  }

  @Test
  void changedHardCapIsPoisonEvenWhenEstimateEvidenceIsUnchanged() {
    Instant now = Instant.parse("2026-07-28T01:00:00Z");
    PipelineRun run = approvedRun();
    GateRecord gate = approvedGate(run);
    run.setHardCapUsd(6.0);
    PlanGateResumeOutbox outbox = processingOutbox(now.plusSeconds(60));
    PlanGateResumeStore.Lease lease = lease(outbox);
    when(runRepository.findByIdForPlanGateOpen(RUN_ID)).thenReturn(Optional.of(run));
    when(gateRepository.findByIdForPlanGateOpen(GATE_KEY)).thenReturn(Optional.of(gate));
    when(outboxRepository.findByIdForUpdate(RUN_ID)).thenReturn(Optional.of(outbox));

    assertThatThrownBy(() -> store.preflight(lease, now))
        .isInstanceOf(PlanGateResumeStore.PoisonMessageException.class)
        .hasMessage("PLAN_GATE_RECEIPT_CROSS_ROW_MISMATCH");
  }

  @Test
  void preflightAndHandoffRejectAdmissionVersionThatDoesNotMatchSupportedReceiptSchema() {
    Instant now = Instant.parse("2026-07-28T01:00:00Z");
    PipelineRun run = approvedRun();
    GateRecord gate = approvedGate(run);
    gate.setAdmissionContractVersion(DecisionEvidenceV1.SCHEMA_VERSION + 1);
    PlanGateResumeOutbox outbox = processingOutbox(now.plusSeconds(60));
    PlanGateResumeStore.Lease lease = lease(outbox);
    when(runRepository.findByIdForPlanGateOpen(RUN_ID)).thenReturn(Optional.of(run));
    when(gateRepository.findByIdForPlanGateOpen(GATE_KEY)).thenReturn(Optional.of(gate));
    when(outboxRepository.findByIdForUpdate(RUN_ID)).thenReturn(Optional.of(outbox));

    assertThatThrownBy(() -> store.preflight(lease, now))
        .isInstanceOf(PlanGateResumeStore.PoisonMessageException.class)
        .hasMessage("PLAN_GATE_RECEIPT_SCHEMA_UNSUPPORTED");
    assertThatThrownBy(() -> store.handoff(lease, now))
        .isInstanceOf(PlanGateResumeStore.PoisonMessageException.class)
        .hasMessage("PLAN_GATE_RECEIPT_SCHEMA_UNSUPPORTED");
    assertThat(run.getStatus()).isEqualTo("APPROVAL_RESUMING");
    assertThat(outbox.getStatus()).isEqualTo("PROCESSING");
  }

  @Test
  void preflightRejectsDigestValidContinuationWithMissingOrNullRequiredProperties() {
    assertPreflightRejectsPayload(
        "{\"schemaVersion\":1,\"localRun\":false,"
            + "\"requireContinuationConfirmation\":false,"
            + "\"requireContinuationBenchmarkPrior\":false,\"pinnedModel\":null}");
    assertPreflightRejectsPayload(
        "{\"schemaVersion\":1,\"localRun\":false,"
            + "\"requireContinuationConfirmation\":null,"
            + "\"requireContinuationBenchmarkPrior\":false,"
            + "\"pinnedModel\":null,\"lowComplexityWorkerStartTierOverride\":null}");
  }

  @Test
  void resumeAcceptsExactLegacyLowercaseBreakingAcknowledgementBytes() {
    Instant now = Instant.parse("2026-07-28T01:00:00Z");
    PipelineRun run = approvedRun();
    GateRecord gate = approvedGate(run);
    String legacyEvidence =
        gate.getApprovalEvidenceJson()
            .replace(
                "\"contractGateReportDigest\":null",
                "\"contractGateReportDigest\":\"" + "d".repeat(64) + "\"")
            .replace("\"contractRequiresTypedAck\":null", "\"contractRequiresTypedAck\":true")
            .replace(
                "\"contractAcknowledgedAt\":null",
                "\"contractAcknowledgedAt\":\"2026-07-28T00:58:00Z\"")
            .replace(
                "\"contractAcknowledgedByUserId\":null",
                "\"contractAcknowledgedByUserId\":\"owner-1\"")
            .replace(
                "\"contractAcknowledgementText\":null",
                "\"contractAcknowledgementText\":\"breaking\"")
            .replace(
                "\"contractAcknowledgementOutcome\":\"NOT_REQUIRED\"",
                "\"contractAcknowledgementOutcome\":\"ACKNOWLEDGED\"");
    assertThat(DecisionEvidenceV1.parse(legacyEvidence).canonicalJson()).isEqualTo(legacyEvidence);
    gate.setContractGateReportDigest("d".repeat(64));
    gate.setApprovalEvidenceJson(legacyEvidence);
    gate.setApprovalEvidenceDigest(DecisionEvidenceV1.sha256(legacyEvidence));
    PlanGateResumeOutbox outbox = processingOutbox(now.plusSeconds(60));
    PlanGateResumeStore.Lease lease = lease(outbox);
    when(runRepository.findByIdForPlanGateOpen(RUN_ID)).thenReturn(Optional.of(run));
    when(gateRepository.findByIdForPlanGateOpen(GATE_KEY)).thenReturn(Optional.of(gate));
    when(outboxRepository.findByIdForUpdate(RUN_ID)).thenReturn(Optional.of(outbox));

    store.preflight(lease, now);

    assertThat(gate.getApprovalEvidenceJson()).isEqualTo(legacyEvidence);
  }

  @Test
  void deadLetterFailsRunInSameTransactionShape() {
    Instant now = Instant.parse("2026-07-28T01:00:00Z");
    PipelineRun run = approvedRun();
    GateRecord gate = approvedGate(run);
    PlanGateResumeOutbox outbox = processingOutbox(now.plusSeconds(60));
    PlanGateResumeStore.Lease lease = lease(outbox);
    when(runRepository.findByIdForPlanGateOpen(RUN_ID)).thenReturn(Optional.of(run));
    when(gateRepository.findByIdForPlanGateOpen(GATE_KEY)).thenReturn(Optional.of(gate));
    when(outboxRepository.findByIdForUpdate(RUN_ID)).thenReturn(Optional.of(outbox));

    assertThat(store.dead(lease, "poison", now)).isTrue();

    assertThat(run.getStatus()).isEqualTo("FAILED");
    assertThat(run.getFailureReason()).isEqualTo("poison");
    assertThat(outbox.getStatus()).isEqualTo("DEAD");
    assertThat(outbox.getCompletedAt()).isEqualTo(now);
  }

  @Test
  void exhaustedReadyRowFailsRunAndDeadLettersAtomically() {
    Instant now = Instant.parse("2026-07-28T01:00:00Z");
    PipelineRun run = approvedRun();
    GateRecord gate = approvedGate(run);
    PlanGateResumeOutbox outbox = readyOutbox();
    outbox.setAttemptCount(outbox.getMaxAttempts());
    when(runRepository.findByIdForPlanGateOpen(RUN_ID)).thenReturn(Optional.of(run));
    when(gateRepository.findFirstByRunIdAndGateType(RUN_ID, "PLAN")).thenReturn(Optional.of(gate));
    when(gateRepository.findByIdForPlanGateOpen(GATE_KEY)).thenReturn(Optional.of(gate));
    when(outboxRepository.findByIdForUpdate(RUN_ID)).thenReturn(Optional.of(outbox));

    assertThat(store.deadExhausted(RUN_ID, now)).isTrue();

    assertThat(run.getStatus()).isEqualTo("FAILED");
    assertThat(outbox.getStatus()).isEqualTo("DEAD");
    assertThat(outbox.getLeaseOwner()).isNull();
    assertThat(outbox.getCompletedAt()).isEqualTo(now);
  }

  @Test
  void cancellationAfterApprovalPreservesReceiptAndCancelsLeasedOutbox() {
    Instant now = Instant.parse("2026-07-28T01:00:00Z");
    PipelineRun run = approvedRun();
    GateRecord gate = approvedGate(run);
    String evidence = gate.getApprovalEvidenceJson();
    PlanGateResumeOutbox outbox = processingOutbox(now.plusSeconds(60));
    when(runRepository.findByIdForPlanGateOpen(RUN_ID)).thenReturn(Optional.of(run));
    when(gateRepository.findFirstByRunIdAndGateType(RUN_ID, "PLAN")).thenReturn(Optional.of(gate));
    when(gateRepository.findByIdForPlanGateOpen(GATE_KEY)).thenReturn(Optional.of(gate));
    when(outboxRepository.findByIdForUpdate(RUN_ID)).thenReturn(Optional.of(outbox));

    assertThat(store.cancelApprovedRun(RUN_ID, "user cancelled", now)).isTrue();

    assertThat(run.getStatus()).isEqualTo("CANCELLED");
    assertThat(outbox.getStatus()).isEqualTo("CANCELLED");
    assertThat(outbox.getLeaseToken()).isNull();
    assertThat(gate.getStatus()).isEqualTo("APPROVED");
    assertThat(gate.getApprovalEvidenceJson()).isEqualTo(evidence);
  }

  @Test
  void genericWorkingRecoveryRehydratesExactPersistedContinuation() {
    PlanGateResumeOutbox outbox = readyOutbox();
    String exact =
        "{\"schemaVersion\":1,\"localRun\":true,"
            + "\"requireContinuationConfirmation\":true,"
            + "\"requireContinuationBenchmarkPrior\":true,"
            + "\"pinnedModel\":\"pinned-model\","
            + "\"lowComplexityWorkerStartTierOverride\":\"PLANNING\"}";
    outbox.setPayload(exact);
    outbox.setPayloadSha256(DecisionEvidenceV1.sha256(exact));
    outbox.setStatus("COMPLETED");
    outbox.setCompletedAt(Instant.parse("2026-07-28T01:00:00Z"));
    when(outboxRepository.findById(RUN_ID)).thenReturn(Optional.of(outbox));
    RunLifecycleService lifecycle =
        new RunLifecycleService(
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            new com.fasterxml.jackson.databind.ObjectMapper(),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null);
    ReflectionTestUtils.setField(lifecycle, "planGateResumeStore", store);

    PlanGateContinuation continuation =
        lifecycle.persistedPlanGateContinuation(PipelineRun.builder().runId(RUN_ID).build());

    assertThat(continuation.schemaVersion()).isEqualTo(1);
    assertThat(continuation.localRun()).isTrue();
    assertThat(continuation.requireContinuationConfirmation()).isTrue();
    assertThat(continuation.requireContinuationBenchmarkPrior()).isTrue();
    assertThat(continuation.pinnedModel()).isEqualTo("pinned-model");
    assertThat(continuation.lowComplexityWorkerStartTierOverride()).isEqualTo("PLANNING");
  }

  @Test
  void genericRecoveryRefusesPresentContinuationThatWasNotHandedOff() {
    PlanGateResumeOutbox outbox = readyOutbox();
    when(outboxRepository.findById(RUN_ID)).thenReturn(Optional.of(outbox));
    RunLifecycleService lifecycle =
        new RunLifecycleService(
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            new com.fasterxml.jackson.databind.ObjectMapper(),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null);
    ReflectionTestUtils.setField(lifecycle, "planGateResumeStore", store);

    assertThatThrownBy(
            () ->
                lifecycle.persistedPlanGateContinuation(
                    PipelineRun.builder().runId(RUN_ID).build()))
        .isInstanceOf(PlanGateResumeStore.PoisonMessageException.class)
        .hasMessageContaining("NOT_HANDED_OFF");
  }

  private static PipelineRun approvedRun() {
    return PipelineRun.builder()
        .runId(RUN_ID)
        .status("APPROVAL_RESUMING")
        .currentGate("PLAN_APPROVAL")
        .planJson("{\"tasks\":[{\"id\":\"t1\"}]}")
        .createdByUserId("owner-1")
        .orgId("org-1")
        .billingOrgId("org-1")
        .aiProvider("anthropic")
        .creditsCharged(1)
        .hardCapUsd(5.0)
        .build();
  }

  private static GateRecord approvedGate(PipelineRun run) {
    String planFingerprint = ExactPlanFingerprint.sha256(run.getPlanJson());
    String requestFingerprint = "a".repeat(64);
    String evidence =
        new DecisionEvidenceV1(
                1,
                DecisionEvidenceV1.POLICY_CONTRACT,
                "APPROVE",
                "2026-07-28T00:59:00Z",
                RUN_ID,
                GATE_KEY,
                "PLAN_APPROVAL_API",
                null,
                "HUMAN",
                "owner-1",
                "USER",
                null,
                "USER",
                "RUN_OWNER",
                null,
                planFingerprint,
                null,
                null,
                requestFingerprint,
                1,
                PAYLOAD_SHA,
                "LEGACY",
                "UNBOUND",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "REALTIME",
                "5.000000",
                "5.000000",
                1,
                null,
                null,
                "owner-1",
                "org-1",
                "anthropic",
                "SYSTEM",
                "SYSTEM",
                "SYSTEM_PLATFORM_KEY",
                "org-1",
                false,
                "ALLOWED",
                "MANAGED",
                null,
                null,
                null,
                null,
                null,
                "ALLOWED",
                null,
                "0.000000",
                "NO_THRESHOLD",
                "ALLOWED",
                null,
                "0.000000",
                "0.000000",
                "NO_CAP",
                null,
                null,
                null,
                null,
                null,
                "NOT_REQUIRED")
            .canonicalJson();
    GateRecord gate = GateRecord.pending(GATE_KEY, RUN_ID, "PLAN");
    gate.setStatus("APPROVED");
    gate.setResolvedAt(Instant.parse("2026-07-28T00:59:00Z"));
    gate.setComment(PAYLOAD);
    gate.setContinuationSchemaVersion(1);
    gate.setContinuationSha256(PAYLOAD_SHA);
    gate.setBindingMode("LEGACY");
    gate.setBindingStatus("UNBOUND");
    gate.setAdmissionContractVersion(1);
    gate.setApprovalEvidenceJson(evidence);
    gate.setApprovalEvidenceDigest(DecisionEvidenceV1.sha256(evidence));
    gate.setDecisionKind("APPROVE");
    gate.setResolvedActorType("HUMAN");
    gate.setResolvedByUserId("owner-1");
    gate.setResolvedByRole("USER");
    gate.setResolvedAuthorizationSource("RUN_OWNER");
    gate.setDecisionRequestFingerprint(requestFingerprint);
    gate.setResolutionSource("PLAN_APPROVAL_API");
    gate.setBillingOwnerUserId("owner-1");
    gate.setBillingOrgId("org-1");
    gate.setAdmittedAt(Instant.parse("2026-07-28T00:59:00Z"));
    return gate;
  }

  private static PlanGateResumeOutbox readyOutbox() {
    return PlanGateResumeOutbox.builder()
        .runId(RUN_ID)
        .gateKey(GATE_KEY)
        .payload(PAYLOAD)
        .payloadSha256(PAYLOAD_SHA)
        .handlerContractVersion(1)
        .status("READY")
        .attemptCount(0)
        .maxAttempts(3)
        .nextRetryAt(Instant.EPOCH)
        .build();
  }

  private static PlanGateResumeOutbox processingOutbox(Instant expiresAt) {
    PlanGateResumeOutbox outbox = readyOutbox();
    outbox.setStatus("PROCESSING");
    outbox.setAttemptCount(1);
    outbox.setLeaseOwner("node-a");
    outbox.setLeaseToken(UUID.randomUUID());
    outbox.setLeaseExpiresAt(expiresAt);
    outbox.setHeartbeatAt(expiresAt.minusSeconds(10));
    return outbox;
  }

  private void assertPreflightRejectsPayload(String payload) {
    Instant now = Instant.parse("2026-07-28T01:00:00Z");
    PipelineRun run = approvedRun();
    GateRecord gate = approvedGate(run);
    String digest = DecisionEvidenceV1.sha256(payload);
    gate.setComment(payload);
    gate.setContinuationSha256(digest);
    PlanGateResumeOutbox outbox = processingOutbox(now.plusSeconds(60));
    outbox.setPayload(payload);
    outbox.setPayloadSha256(digest);
    PlanGateResumeStore.Lease lease = lease(outbox);
    when(runRepository.findByIdForPlanGateOpen(RUN_ID)).thenReturn(Optional.of(run));
    when(gateRepository.findByIdForPlanGateOpen(GATE_KEY)).thenReturn(Optional.of(gate));
    when(outboxRepository.findByIdForUpdate(RUN_ID)).thenReturn(Optional.of(outbox));

    assertThatThrownBy(() -> store.preflight(lease, now))
        .isInstanceOf(PlanGateResumeStore.PoisonMessageException.class)
        .hasMessage("PLAN_GATE_CONTINUATION_INVALID");
  }

  private static PlanGateResumeStore.Lease lease(PlanGateResumeOutbox outbox) {
    return new PlanGateResumeStore.Lease(
        RUN_ID,
        GATE_KEY,
        PAYLOAD,
        PAYLOAD_SHA,
        1,
        outbox.getAttemptCount(),
        outbox.getMaxAttempts(),
        outbox.getLeaseOwner(),
        outbox.getLeaseToken());
  }

  private static String queuedOwner(String suffix) {
    return suffix + PlanGateResumeDispatcher.QUEUED_HANDOFF_OWNER_MARKER + "test-process";
  }
}

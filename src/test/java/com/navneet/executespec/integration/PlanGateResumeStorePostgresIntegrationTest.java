package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import javax.sql.DataSource;

import jakarta.persistence.EntityManager;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agent.ApiKeyResolver;
import com.navneet.executespec.agent.ResolvedKeys;
import com.navneet.executespec.agent.requirement.ExactPlanFingerprint;
import com.navneet.executespec.agent.requirement.RequirementGateBindingService.RequestedMode;
import com.navneet.executespec.agent.requirement.RequirementIrApprovalCompatibilityRegistry;
import com.navneet.executespec.agent.requirement.repository.RunRequirementContractRepository;
import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.billing.service.ManagedAiUsageService;
import com.navneet.executespec.config.AgentProperties;
import com.navneet.executespec.deployment.WorkspaceRestoreService;
import com.navneet.executespec.governance.GovernanceGuard;
import com.navneet.executespec.harness.repository.GateRepository;
import com.navneet.executespec.harness.repository.PlanGateResumeOutboxRepository;
import com.navneet.executespec.sse.SseBroadcaster;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Real PostgreSQL coverage for the leased, fenced plan-gate continuation store. */
@TestPropertySource(
    properties = {
      "execution.plan-gate-resume.scan-initial-delay-ms=3600000",
      "execution.plan-gate-resume.scan-interval-ms=3600000"
    })
class PlanGateResumeStorePostgresIntegrationTest extends PostgresIntegrationBase {
  private static final String PREFIX = "c03c-resume-";
  private static final String PLAN_JSON = "{\"planTitle\":\"Exact\",\"tasks\":[]}";
  private static final String CONTINUATION =
      "{\"schemaVersion\":1,\"localRun\":false,"
          + "\"requireContinuationConfirmation\":false,"
          + "\"requireContinuationBenchmarkPrior\":false,"
          + "\"pinnedModel\":null,\"lowComplexityWorkerStartTierOverride\":null}";

  @Autowired PlanGateOpenService opener;
  @Autowired PlanGateResumeStore store;
  @Autowired PipelineRunRepository runs;
  @Autowired GateRepository gates;
  @Autowired PlanGateResumeOutboxRepository outboxes;
  @Autowired RunRequirementContractRepository requirements;
  @Autowired EntityManager entityManager;
  @Autowired PlatformTransactionManager transactionManager;
  @Autowired JdbcTemplate jdbc;
  @Autowired DataSource dataSource;
  @Autowired ObjectMapper objectMapper;
  @TempDir Path workspaceRoot;

  private ApiKeyResolver apiKeys;
  private ManagedAiUsageService managedAi;
  private GovernanceGuard governance;
  private PlanGateDecisionService decisionService;
  private TransactionTemplate transactions;

  @BeforeEach
  void setUp() {
    jdbc.update("DELETE FROM pipeline_run WHERE run_id LIKE ?", PREFIX + "%");
    jdbc.update("DELETE FROM project WHERE id LIKE ?", PREFIX + "%");
    jdbc.update("DELETE FROM app_user WHERE id LIKE ?", PREFIX + "%");
    jdbc.update("DELETE FROM org WHERE id LIKE ?", PREFIX + "%");
    apiKeys = mock(ApiKeyResolver.class);
    managedAi = mock(ManagedAiUsageService.class);
    governance = mock(GovernanceGuard.class);
    decisionService =
        new PlanGateDecisionService(
            runs,
            gates,
            outboxes,
            requirements,
            new RequirementIrApprovalCompatibilityRegistry(objectMapper),
            entityManager,
            apiKeys,
            managedAi,
            governance,
            mock(ApplicationEventPublisher.class));
    transactions = new TransactionTemplate(transactionManager);
  }

  @Test
  void skipLockedAllowsExactlyOneConcurrentClaimWhileTheWinningLeaseIsUncommitted()
      throws Exception {
    Fixture fixture = approvedFixture();
    Instant now = Instant.now();
    CountDownLatch winnerClaimed = new CountDownLatch(1);
    CountDownLatch releaseWinner = new CountDownLatch(1);

    try (var pool = Executors.newFixedThreadPool(2)) {
      Future<Optional<PlanGateResumeStore.Lease>> winner =
          pool.submit(
              () ->
                  inTransaction(
                      () -> {
                        Optional<PlanGateResumeStore.Lease> lease =
                            store.claimNext("node-a", now, Duration.ofMinutes(2));
                        winnerClaimed.countDown();
                        await(releaseWinner);
                        return lease;
                      }));
      assertThat(winnerClaimed.await(20, TimeUnit.SECONDS)).isTrue();

      Future<Optional<PlanGateResumeStore.Lease>> skipped =
          pool.submit(() -> store.claimNext("node-b", now, Duration.ofMinutes(2)));
      assertThat(skipped.get(10, TimeUnit.SECONDS)).isEmpty();
      releaseWinner.countDown();

      PlanGateResumeStore.Lease lease = winner.get(20, TimeUnit.SECONDS).orElseThrow();
      assertThat(lease.runId()).isEqualTo(fixture.runId());
      assertThat(lease.owner()).isEqualTo("node-a");
    } finally {
      releaseWinner.countDown();
    }

    assertOutbox(fixture.runId(), "PROCESSING", 1, "node-a");
  }

  @Test
  void expiredProcessingLeaseIsReclaimedAndTheOldOwnerIsFencedFromEveryTerminalWrite() {
    Fixture fixture = approvedFixture();
    Instant firstNow = Instant.now();
    PlanGateResumeStore.Lease first =
        store.claimNext("node-a", firstNow, Duration.ofSeconds(2)).orElseThrow();
    Instant reclaimAt = firstNow.plusSeconds(3);

    PlanGateResumeStore.Lease second =
        store.claimNext("node-b", reclaimAt, Duration.ofMinutes(2)).orElseThrow();

    assertThat(second.attempt()).isEqualTo(2);
    assertThat(second.token()).isNotEqualTo(first.token());
    assertThat(store.heartbeat(first, reclaimAt, Duration.ofMinutes(2))).isFalse();
    assertThat(store.retry(first, "stale retry", reclaimAt.plusSeconds(1), reclaimAt)).isFalse();
    assertThat(store.dead(first, "stale dead", reclaimAt)).isFalse();
    assertThatThrownBy(() -> store.handoff(first, reclaimAt))
        .isInstanceOf(PlanGateResumeStore.StaleLeaseException.class)
        .hasMessage("PLAN_GATE_RESUME_STALE_LEASE");

    PlanGateResumeStore.Handoff handoff = store.handoff(second, reclaimAt.plusSeconds(1));
    assertThat(handoff.continuationPayload()).isEqualTo(CONTINUATION);
    assertTuple(fixture.runId(), "WORKING", "COMPLETED");
  }

  @Test
  void applicationReadyRecoversOneDurableReadyContinuationAndLeavesFutureWorkUntouched()
      throws Exception {
    Fixture ready = approvedFixture();
    Fixture future = approvedFixture();
    String readyReceipt = gateReceipt(ready.runId());
    String futureReceipt = gateReceipt(future.runId());
    jdbc.update(
        "UPDATE plan_gate_resume_outbox SET next_retry_at=? WHERE run_id=?",
        Timestamp.from(Instant.now().plus(Duration.ofHours(1))),
        future.runId());
    Files.createDirectories(workspaceRoot.resolve(ready.runId()));

    RunControlPort control = mock(RunControlPort.class);
    PipelineExecutorPool pool = mock(PipelineExecutorPool.class);
    ExecutorService directExecutor = mock(ExecutorService.class);
    WorkspaceRestoreService workspaceRestore = mock(WorkspaceRestoreService.class);
    SseBroadcaster broadcaster = mock(SseBroadcaster.class);
    when(pool.executor()).thenReturn(directExecutor);
    doAnswer(
            invocation -> {
              invocation.<Runnable>getArgument(0).run();
              return null;
            })
        .when(directExecutor)
        .execute(any(Runnable.class));
    PlanGateResumeDispatcher dispatcher = dispatcher(control, pool, workspaceRestore, broadcaster);
    try {
      dispatcher.onApplicationReady();
    } finally {
      dispatcher.shutdownLeaseRenewalExecutor();
    }

    ArgumentCaptor<RunOrchestrator.RunPipelineRequest> request =
        ArgumentCaptor.forClass(RunOrchestrator.RunPipelineRequest.class);
    InOrder order = inOrder(control, directExecutor);
    order.verify(control).putExternallyHeartbeatedActiveRun(eq(ready.runId()), any());
    order.verify(directExecutor).execute(any(Runnable.class));
    order.verify(control).adoptRuntimeHeartbeat(eq(ready.runId()), any());
    order.verify(control).runPipelineForResume(request.capture());
    order.verify(control).removeActiveRun(eq(ready.runId()), any());
    verify(control, times(1)).runPipelineForResume(any());
    verify(workspaceRestore, never()).ensureWorkspaceAvailable(any());

    assertThat(request.getValue().runId()).isEqualTo(ready.runId());
    assertThat(request.getValue().planGateContinuation().schemaVersion()).isEqualTo(1);
    assertTuple(ready.runId(), "WORKING", "COMPLETED");
    assertTuple(future.runId(), "APPROVAL_RESUMING", "READY");
    assertThat(
            jdbc.queryForMap(
                "SELECT executing_instance,execution_heartbeat FROM pipeline_run WHERE run_id=?",
                ready.runId()))
        .containsEntry("executing_instance", ExecutionHostIdentity.current())
        .doesNotContainEntry("execution_heartbeat", null);
    assertThat(gateReceipt(ready.runId())).isEqualTo(readyReceipt);
    assertThat(gateReceipt(future.runId())).isEqualTo(futureReceipt);
    assertThat(outboxRow(future.runId()).get("attempt_count")).isEqualTo(0);
  }

  @Test
  void applicationReadyReclaimsAnExpiredProcessingContinuationExactlyOnce() throws Exception {
    Fixture fixture = approvedFixture();
    String receipt = gateReceipt(fixture.runId());
    store.claimNext("departed-node", Instant.now(), Duration.ofMinutes(2)).orElseThrow();
    jdbc.update(
        "UPDATE plan_gate_resume_outbox SET lease_expires_at=? WHERE run_id=?",
        Timestamp.from(Instant.now().minusSeconds(1)),
        fixture.runId());
    Files.createDirectories(workspaceRoot.resolve(fixture.runId()));

    RunControlPort control = mock(RunControlPort.class);
    PipelineExecutorPool pool = mock(PipelineExecutorPool.class);
    ExecutorService directExecutor = mock(ExecutorService.class);
    when(pool.executor()).thenReturn(directExecutor);
    doAnswer(
            invocation -> {
              invocation.<Runnable>getArgument(0).run();
              return null;
            })
        .when(directExecutor)
        .execute(any(Runnable.class));
    PlanGateResumeDispatcher dispatcher =
        dispatcher(control, pool, mock(WorkspaceRestoreService.class), mock(SseBroadcaster.class));
    try {
      dispatcher.onApplicationReady();
    } finally {
      dispatcher.shutdownLeaseRenewalExecutor();
    }

    verify(control, times(1)).runPipelineForResume(any());
    assertTuple(fixture.runId(), "WORKING", "COMPLETED");
    assertThat(outboxRow(fixture.runId()))
        .containsEntry("attempt_count", 2)
        .containsEntry("last_error", null);
    assertThat(gateReceipt(fixture.runId())).isEqualTo(receipt);
  }

  @Test
  void realPreflightValidatesTheOwnedReceiptWithoutMutatingDurableState() {
    Fixture fixture = approvedFixture();
    String receipt = gateReceipt(fixture.runId());
    Instant now = Instant.now();
    PlanGateResumeStore.Lease lease =
        store.claimNext("preflight-node", now, Duration.ofMinutes(2)).orElseThrow();
    Map<String, Object> before = outboxRow(fixture.runId());

    store.preflight(lease, now.plusSeconds(1));

    assertTuple(fixture.runId(), "APPROVAL_RESUMING", "PROCESSING");
    assertThat(gateReceipt(fixture.runId())).isEqualTo(receipt);
    assertThat(outboxRow(fixture.runId())).containsAllEntriesOf(before);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void cancellationFencesReadyAndProcessingContinuationsWithoutRewritingApprovalReceipt(
      boolean claimFirst) {
    Fixture fixture = approvedFixture();
    String receipt = gateReceipt(fixture.runId());
    if (claimFirst) {
      store.claimNext("node-a", Instant.now(), Duration.ofMinutes(2)).orElseThrow();
    }

    Instant cancelledAt = Instant.now();
    assertThat(store.cancelApprovedRun(fixture.runId(), "operator cancelled", cancelledAt))
        .isTrue();

    assertTuple(fixture.runId(), "CANCELLED", "CANCELLED");
    assertThat(gateReceipt(fixture.runId())).isEqualTo(receipt);
    Map<String, Object> row = outboxRow(fixture.runId());
    assertThat(row.get("lease_owner")).isNull();
    assertThat(row.get("lease_token")).isNull();
    assertThat(row.get("lease_expires_at")).isNull();
    assertThat(row.get("completed_at")).isNotNull();
  }

  @Test
  void handoffAtomicallyMovesRunAndOutboxAndPreservesExactApprovalReceipt() {
    Fixture fixture = approvedFixture();
    String receipt = gateReceipt(fixture.runId());
    Instant now = Instant.now();
    PlanGateResumeStore.Lease lease =
        store.claimNext("node-a", now, Duration.ofMinutes(2)).orElseThrow();

    PlanGateResumeStore.Handoff handoff = store.handoff(lease, now.plusSeconds(1));

    assertThat(handoff.run().getStatus()).isEqualTo("WORKING");
    assertThat(handoff.continuationPayload()).isEqualTo(CONTINUATION);
    assertTuple(fixture.runId(), "WORKING", "COMPLETED");
    assertThat(gateReceipt(fixture.runId())).isEqualTo(receipt);
    Map<String, Object> row = outboxRow(fixture.runId());
    assertThat(row.get("lease_owner")).isNull();
    assertThat(row.get("lease_token")).isNull();
    assertThat(row.get("heartbeat_at")).isNull();
    assertThat(row.get("completed_at")).isNotNull();
  }

  @Test
  void completedHandoffDispatcherNeverClaimsAWorkerThatAlreadyStarted() {
    Fixture fixture = approvedFixture();
    Instant now = Instant.now();
    PlanGateResumeStore.Lease lease =
        store.claimNext("lease-node", now, Duration.ofMinutes(2)).orElseThrow();
    String queuedOwner = PlanGateResumeDispatcher.newQueuedHandoffOwner();
    PlanGateResumeStore.Handoff queued = store.handoff(lease, queuedOwner, now.plusSeconds(1));
    assertThat(store.startRegisteredHandoff(queued, "runtime-started-node", now.plusSeconds(2)))
        .isTrue();
    jdbc.update(
        "UPDATE pipeline_run SET execution_heartbeat=? WHERE run_id=?",
        Timestamp.from(now.minusSeconds(300)),
        fixture.runId());
    entityManager.clear();

    assertThat(store.staleCompletedHandoffRunIds(now, now.minusSeconds(120), 10))
        .doesNotContain(fixture.runId());
    assertThat(
            store.claimStaleCompletedHandoff(
                fixture.runId(),
                PlanGateResumeDispatcher.newQueuedHandoffOwner(),
                now.minusSeconds(120),
                now))
        .isEmpty();
    assertThat(
            jdbc.queryForObject(
                "SELECT executing_instance FROM pipeline_run WHERE run_id=?",
                String.class,
                fixture.runId()))
        .isEqualTo("runtime-started-node");
    assertThat(outboxRow(fixture.runId()).get("attempt_count")).isEqualTo(1);
  }

  @Test
  void injectedOutboxCompletionFailureRollsBackTheRunHandoff() {
    Fixture fixture = approvedFixture();
    Instant now = Instant.now();
    PlanGateResumeStore.Lease lease =
        store.claimNext("node-a", now, Duration.ofMinutes(2)).orElseThrow();
    String suffix = UUID.randomUUID().toString().replace("-", "");
    String function = "fail_resume_handoff_" + suffix;
    String trigger = "trg_fail_resume_handoff_" + suffix;
    jdbc.execute(
        "CREATE FUNCTION "
            + function
            + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.run_id = '"
            + fixture.runId()
            + "' AND NEW.status = 'COMPLETED' THEN RAISE EXCEPTION 'injected handoff write"
            + " failure'; END IF; RETURN NEW; END; $$");
    jdbc.execute(
        "CREATE TRIGGER "
            + trigger
            + " BEFORE UPDATE ON plan_gate_resume_outbox FOR EACH ROW EXECUTE FUNCTION "
            + function
            + "()");
    try {
      assertThatThrownBy(() -> store.handoff(lease, now.plusSeconds(1)))
          .rootCause()
          .hasMessageContaining("injected handoff write failure");
    } finally {
      jdbc.execute("DROP TRIGGER IF EXISTS " + trigger + " ON plan_gate_resume_outbox");
      jdbc.execute("DROP FUNCTION IF EXISTS " + function + "()");
    }

    assertTuple(fixture.runId(), "APPROVAL_RESUMING", "PROCESSING");
    assertThat(outboxRow(fixture.runId()).get("lease_token")).isEqualTo(lease.token());
  }

  @Test
  void unsupportedPersistedReceiptSchemaIsPoisonedThenDeadLetteredByTheOwnedLease() {
    Fixture fixture = approvedFixture();
    Instant now = Instant.now();
    PlanGateResumeStore.Lease lease =
        store.claimNext("node-a", now, Duration.ofMinutes(2)).orElseThrow();
    corruptPersistedSchemaForRecoveryBoundaryTest(fixture.runId());
    try {
      assertThatThrownBy(() -> store.handoff(lease, now.plusSeconds(1)))
          .isInstanceOf(PlanGateResumeStore.PoisonMessageException.class)
          .hasMessageContaining("PLAN_GATE_RECEIPT_SCHEMA_UNSUPPORTED");
      assertThat(store.dead(lease, "unsupported durable receipt schema", now.plusSeconds(2)))
          .isTrue();

      assertTuple(fixture.runId(), "FAILED", "DEAD");
      assertThat(outboxRow(fixture.runId()).get("last_error"))
          .isEqualTo("unsupported durable receipt schema");
    } finally {
      enablePlanGateIntegrityTriggers();
    }
  }

  @Test
  void digestValidContinuationMissingRequiredFieldsIsDeadLetteredBeforeWorkspaceOrDispatch() {
    Fixture fixture = approvedFixture();
    corruptContinuationForRecoveryBoundaryTest(fixture.runId(), "{\"schemaVersion\":1}");

    RunControlPort control = mock(RunControlPort.class);
    PipelineExecutorPool pool = mock(PipelineExecutorPool.class);
    WorkspaceRestoreService workspaceRestore = mock(WorkspaceRestoreService.class);
    PlanGateResumeDispatcher dispatcher =
        dispatcher(control, pool, workspaceRestore, mock(SseBroadcaster.class));
    try {
      dispatcher.kick();
    } finally {
      dispatcher.shutdownLeaseRenewalExecutor();
    }

    assertTuple(fixture.runId(), "FAILED", "DEAD");
    assertThat(outboxRow(fixture.runId()).get("last_error").toString())
        .contains("PLAN_GATE_CONTINUATION");
    verify(workspaceRestore, never()).ensureWorkspaceAvailable(any());
    verify(control, never()).putExternallyHeartbeatedActiveRun(any(), any());
    verify(control, never()).runPipelineForResume(any());
    verify(pool, never()).executor();
  }

  @Test
  void unsupportedAdmissionContractVersionIsDeadLetteredBeforeWorkspaceOrDispatch() {
    Fixture fixture = approvedFixture();
    corruptAdmissionContractVersionForRecoveryBoundaryTest(fixture.runId(), 99);

    RunControlPort control = mock(RunControlPort.class);
    PipelineExecutorPool pool = mock(PipelineExecutorPool.class);
    WorkspaceRestoreService workspaceRestore = mock(WorkspaceRestoreService.class);
    PlanGateResumeDispatcher dispatcher =
        dispatcher(control, pool, workspaceRestore, mock(SseBroadcaster.class));
    try {
      dispatcher.kick();

      assertTuple(fixture.runId(), "FAILED", "DEAD");
      assertThat(outboxRow(fixture.runId()).get("last_error").toString())
          .contains("PLAN_GATE_RECEIPT_SCHEMA_UNSUPPORTED");
      verify(workspaceRestore, never()).ensureWorkspaceAvailable(any());
      verify(control, never()).putExternallyHeartbeatedActiveRun(any(), any());
      verify(control, never()).runPipelineForResume(any());
      verify(pool, never()).executor();
    } finally {
      dispatcher.shutdownLeaseRenewalExecutor();
      enablePlanGateIntegrityTriggers();
    }
  }

  @Test
  void immediateExecutorRejectionReleasesCompletedHandoffThenDispatcherRecoversIt()
      throws Exception {
    Fixture fixture = approvedFixture();
    String receipt = gateReceipt(fixture.runId());
    Files.createDirectories(workspaceRoot.resolve(fixture.runId()));

    RunControlPort control = mock(RunControlPort.class);
    AtomicReference<Future<?>> registeredTask = new AtomicReference<>();
    doAnswer(
            invocation -> {
              registeredTask.set(invocation.getArgument(1));
              return null;
            })
        .when(control)
        .putExternallyHeartbeatedActiveRun(eq(fixture.runId()), any());
    when(control.removeActiveRun(eq(fixture.runId()), any()))
        .thenAnswer(
            invocation -> {
              Future<?> expected = invocation.getArgument(1);
              return registeredTask.compareAndSet(expected, null);
            });
    PipelineExecutorPool pool = mock(PipelineExecutorPool.class);
    ExecutorService rejectingExecutor = mock(ExecutorService.class);
    when(pool.executor()).thenReturn(rejectingExecutor);
    org.mockito.Mockito.doThrow(new RejectedExecutionException("executor stopping"))
        .when(rejectingExecutor)
        .execute(any(Runnable.class));
    PlanGateResumeDispatcher dispatcher =
        dispatcher(control, pool, mock(WorkspaceRestoreService.class), mock(SseBroadcaster.class));
    try {
      dispatcher.kick();
    } finally {
      dispatcher.shutdownLeaseRenewalExecutor();
    }

    assertTuple(fixture.runId(), "WORKING", "COMPLETED");
    assertThat(outboxRow(fixture.runId()))
        .containsEntry("attempt_count", 1)
        .containsEntry("lease_owner", null)
        .containsEntry("lease_token", null)
        .doesNotContainEntry("completed_at", null)
        .doesNotContainEntry("next_retry_at", null);
    assertThat(outboxRow(fixture.runId()).get("last_error").toString())
        .startsWith("PLAN_GATE_POST_HANDOFF_DISPATCH_FAILED:RejectedExecutionException:");
    assertThat(
            jdbc.queryForMap(
                "SELECT current_gate,executing_instance,execution_heartbeat"
                    + " FROM pipeline_run WHERE run_id=?",
                fixture.runId()))
        .containsEntry("current_gate", null)
        .doesNotContainEntry("executing_instance", null)
        .containsEntry("execution_heartbeat", null);
    assertThat(gateReceipt(fixture.runId())).isEqualTo(receipt);
    verify(control).putExternallyHeartbeatedActiveRun(eq(fixture.runId()), any());
    verify(control).removeActiveRun(eq(fixture.runId()), any());
    verify(control, never()).runPipelineForResume(any());

    jdbc.update(
        "UPDATE plan_gate_resume_outbox SET next_retry_at=? WHERE run_id=?",
        Timestamp.from(Instant.now().minusSeconds(1)),
        fixture.runId());
    ExecutorService directExecutor = mock(ExecutorService.class);
    when(pool.executor()).thenReturn(directExecutor);
    doAnswer(
            invocation -> {
              invocation.<Runnable>getArgument(0).run();
              return null;
            })
        .when(directExecutor)
        .execute(any(Runnable.class));
    PlanGateResumeDispatcher recoveryDispatcher =
        dispatcher(control, pool, mock(WorkspaceRestoreService.class), mock(SseBroadcaster.class));
    try {
      recoveryDispatcher.kick();
    } finally {
      recoveryDispatcher.shutdownLeaseRenewalExecutor();
    }

    assertTuple(fixture.runId(), "WORKING", "COMPLETED");
    assertThat(outboxRow(fixture.runId()))
        .containsEntry("attempt_count", 2)
        .containsEntry("last_error", null);
    assertThat(
            jdbc.queryForMap(
                "SELECT executing_instance,execution_heartbeat"
                    + " FROM pipeline_run WHERE run_id=?",
                fixture.runId()))
        .doesNotContainEntry("executing_instance", null)
        .doesNotContainEntry("execution_heartbeat", null);
    assertThat(gateReceipt(fixture.runId())).isEqualTo(receipt);
    verify(control, times(1)).runPipelineForResume(any());
  }

  @Test
  void transientWorkspaceFailureRetriesAfterDelayThenReclaimsAndDelivers() throws Exception {
    Fixture fixture = approvedFixture();
    String receipt = gateReceipt(fixture.runId());
    Instant firstAttemptStarted = Instant.now();

    RunControlPort control = mock(RunControlPort.class);
    PipelineExecutorPool pool = mock(PipelineExecutorPool.class);
    ExecutorService directExecutor = mock(ExecutorService.class);
    WorkspaceRestoreService workspaceRestore = mock(WorkspaceRestoreService.class);
    when(pool.executor()).thenReturn(directExecutor);
    doAnswer(
            invocation -> {
              invocation.<Runnable>getArgument(0).run();
              return null;
            })
        .when(directExecutor)
        .execute(any(Runnable.class));
    org.mockito.Mockito.doThrow(new IllegalStateException("temporary workspace outage"))
        .doReturn(workspaceRoot.resolve(fixture.runId()))
        .when(workspaceRestore)
        .ensureWorkspaceAvailable(fixture.runId());
    PlanGateResumeDispatcher dispatcher =
        dispatcher(control, pool, workspaceRestore, mock(SseBroadcaster.class));
    try {
      dispatcher.kick();

      assertTuple(fixture.runId(), "APPROVAL_RESUMING", "READY");
      Map<String, Object> retried = outboxRow(fixture.runId());
      assertThat(retried)
          .containsEntry("attempt_count", 1)
          .containsEntry("lease_owner", null)
          .containsEntry("lease_token", null)
          .containsEntry("completed_at", null);
      assertThat(retried.get("last_error").toString())
          .startsWith("IllegalStateException:temporary workspace outage");
      assertThat(((Timestamp) retried.get("next_retry_at")).toInstant())
          .isAfter(firstAttemptStarted);
      verify(control, never()).runPipelineForResume(any());

      Files.createDirectories(workspaceRoot.resolve(fixture.runId()));
      jdbc.update(
          "UPDATE plan_gate_resume_outbox SET next_retry_at=? WHERE run_id=?",
          Timestamp.from(Instant.now().minusSeconds(1)),
          fixture.runId());
      dispatcher.kick();
    } finally {
      dispatcher.shutdownLeaseRenewalExecutor();
    }

    assertTuple(fixture.runId(), "WORKING", "COMPLETED");
    assertThat(outboxRow(fixture.runId()))
        .containsEntry("attempt_count", 2)
        .containsEntry("last_error", null);
    assertThat(gateReceipt(fixture.runId())).isEqualTo(receipt);
    verify(control, times(1)).runPipelineForResume(any());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void dispatcherDeadLettersExhaustedReadyAndExpiredProcessingWork(boolean processing) {
    Fixture fixture = approvedFixture();
    int maxAttempts =
        jdbc.queryForObject(
            "SELECT max_attempts FROM plan_gate_resume_outbox WHERE run_id=?",
            Integer.class,
            fixture.runId());
    Instant now = Instant.now();
    if (processing) {
      jdbc.update(
          "UPDATE plan_gate_resume_outbox SET attempt_count=? WHERE run_id=?",
          maxAttempts - 1,
          fixture.runId());
      PlanGateResumeStore.Lease lease =
          store.claimNext("departed-max-attempt-node", now, Duration.ofSeconds(2)).orElseThrow();
      assertThat(lease.attempt()).isEqualTo(maxAttempts);
      jdbc.update(
          "UPDATE plan_gate_resume_outbox SET lease_expires_at=? WHERE run_id=?",
          Timestamp.from(now.minusSeconds(1)),
          fixture.runId());
    } else {
      jdbc.update(
          "UPDATE plan_gate_resume_outbox SET attempt_count=?,next_retry_at=? WHERE run_id=?",
          maxAttempts,
          Timestamp.from(now.minusSeconds(1)),
          fixture.runId());
    }

    RunControlPort control = mock(RunControlPort.class);
    PipelineExecutorPool pool = mock(PipelineExecutorPool.class);
    WorkspaceRestoreService workspaceRestore = mock(WorkspaceRestoreService.class);
    PlanGateResumeDispatcher dispatcher =
        dispatcher(control, pool, workspaceRestore, mock(SseBroadcaster.class));
    try {
      dispatcher.kick();
    } finally {
      dispatcher.shutdownLeaseRenewalExecutor();
    }

    assertTuple(fixture.runId(), "FAILED", "DEAD");
    assertThat(outboxRow(fixture.runId()))
        .containsEntry("attempt_count", maxAttempts)
        .containsEntry("last_error", "PLAN_GATE_RESUME_ATTEMPTS_EXHAUSTED")
        .containsEntry("lease_owner", null)
        .containsEntry("lease_token", null)
        .doesNotContainEntry("completed_at", null);
    verify(workspaceRestore, never()).ensureWorkspaceAvailable(any());
    verify(control, never()).putExternallyHeartbeatedActiveRun(any(), any());
    verify(pool, never()).executor();
  }

  @Test
  void exactApprovalReplayStillReturnsStoredBytesAfterCompletedHandoff() {
    Fixture fixture = approvedFixture();
    String receipt = gateReceipt(fixture.runId());
    Instant now = Instant.now();
    PlanGateResumeStore.Lease lease =
        store.claimNext("replay-after-handoff", now, Duration.ofMinutes(2)).orElseThrow();
    store.handoff(lease, now.plusSeconds(1));

    PlanGateDecisionService.DecisionResult replay =
        inTransaction(
            () -> decisionService.decide(fixture.runId(), fixture.approve(), fixture.actor()));

    assertThat(replay.replayed()).isTrue();
    assertThat(replay.evidence().canonicalJson()).isEqualTo(receipt);
    assertTuple(fixture.runId(), "WORKING", "COMPLETED");
    assertThat(gateReceipt(fixture.runId())).isEqualTo(receipt);
  }

  @Test
  void postgresRoundedApprovalReceiptSurvivesResumePreflightAndHandoff() {
    Fixture fixture = approvedFixture(CONTINUATION, 0.5961704999999999d);
    String receipt = gateReceipt(fixture.runId());
    assertThat(receipt).contains("\"estimateHighUsd\":\"0.596171\"");
    Instant now = Instant.now();
    var lease = store.claimNext("rounding-regression", now, Duration.ofMinutes(2)).orElseThrow();
    store.preflight(lease, now.plusSeconds(1));
    store.handoff(lease, now.plusSeconds(2));
    assertTuple(fixture.runId(), "WORKING", "COMPLETED");
    assertThat(gateReceipt(fixture.runId())).isEqualTo(receipt);
  }

  private Fixture approvedFixture() {
    return approvedFixture(CONTINUATION);
  }

  private Fixture approvedFixture(String continuation) {
    return approvedFixture(continuation, 4.5);
  }

  private Fixture approvedFixture(String continuation, double highEstimate) {
    String runId = PREFIX + UUID.randomUUID();
    String ownerId = PREFIX + "owner-" + UUID.randomUUID();
    String orgId = PREFIX + "org-" + UUID.randomUUID();
    String projectId = PREFIX + "project-" + UUID.randomUUID();
    jdbc.update(
        "INSERT INTO org (id,name,domain_patterns,tier,created_by_user_id) VALUES (?,?,?,?,?)",
        orgId,
        "C03C Resume Org",
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
        "C03C Resume Project",
        ownerId,
        "ANTHROPIC");
    jdbc.update(
        """
INSERT INTO pipeline_run
  (run_id,status,started_at,user_request,plan_json,credits_charged,cost_estimate_high,
   created_by_user_id,org_id,billing_org_id,project_id,ai_provider,build_protocol_version)
VALUES (?, 'PLANNING', ?, 'Original requirement', ?, 3, ?, ?, ?, ?, ?, 'anthropic', 'LEGACY_V1')
""",
        runId,
        Timestamp.from(Instant.now()),
        PLAN_JSON,
        highEstimate,
        ownerId,
        orgId,
        orgId,
        projectId);
    opener.open(
        new PlanGateOpenService.OpenCommand(
            runId,
            PLAN_JSON,
            ExactPlanFingerprint.sha256(PLAN_JSON),
            continuation,
            1,
            null,
            RequestedMode.LEGACY));

    AppUserDetails actor =
        new AppUserDetails(
            AppUser.builder()
                .id(ownerId)
                .email(ownerId + "@example.test")
                .globalRole("USER")
                .status("ACTIVE")
                .build(),
            null);
    when(apiKeys.resolveWithProvenance(ownerId)).thenReturn(credentials(orgId));
    when(managedAi.assertCanApproveEstimatedSpend(ownerId, orgId, false, highEstimate))
        .thenReturn(
            new ManagedAiUsageService.ApprovalEvaluation(
                "ALLOWED", "MANAGED", 2000L, 1900L, 100L, 25.0, 23.0));
    when(governance.evaluateApprovalCost(eq(orgId), eq(highEstimate), any(AppUserDetails.class)))
        .thenReturn(
            new GovernanceGuard.OrgCostEvaluation(
                "ALLOWED", new BigDecimal("10.00"), highEstimate, "BELOW_THRESHOLD"));
    when(governance.evaluateProjectSpendCap(projectId, highEstimate))
        .thenReturn(
            new GovernanceGuard.ProjectSpendEvaluation(
                "ALLOWED",
                new BigDecimal("25.00"),
                BigDecimal.ZERO,
                BigDecimal.valueOf(highEstimate),
                "WITHIN_CAP"));
    PlanApprovalService.Command approve =
        new PlanApprovalService.Command(
            true, 3, "REALTIME", ExactPlanFingerprint.sha256(PLAN_JSON), null, null);
    PlanGateDecisionService.DecisionResult decision =
        inTransaction(() -> decisionService.decide(runId, approve, actor));
    assertThat(decision.replayed()).isFalse();
    return new Fixture(runId, actor, approve);
  }

  private void corruptPersistedSchemaForRecoveryBoundaryTest(String runId) {
    try (Connection connection = dataSource.getConnection();
        var statement = connection.createStatement()) {
      connection.setAutoCommit(false);
      statement.execute("ALTER TABLE gate_record DISABLE TRIGGER USER");
      statement.execute("ALTER TABLE plan_gate_resume_outbox DISABLE TRIGGER USER");
      statement.execute("ALTER TABLE pipeline_run DISABLE TRIGGER USER");
      String constraintDefinition =
          constraintDefinition(
              connection, "gate_record", "ck_plan_gate_admission_contract_version");
      statement.execute(
          "ALTER TABLE gate_record DROP CONSTRAINT ck_plan_gate_admission_contract_version");
      try (var update =
          connection.prepareStatement(
              """
              UPDATE gate_record
                 SET admission_contract_version=99,
                     approval_evidence_json=(
                       jsonb_set(approval_evidence_json::jsonb,
                                 '{schemaVersion}', '99'::jsonb)::text),
                     approval_evidence_digest=encode(sha256(convert_to(
                       jsonb_set(approval_evidence_json::jsonb,
                                 '{schemaVersion}', '99'::jsonb)::text,
                       'UTF8')), 'hex')
               WHERE run_id=?
              """)) {
        update.setString(1, runId);
        assertThat(update.executeUpdate()).isEqualTo(1);
      }
      statement.execute(
          "ALTER TABLE gate_record ADD CONSTRAINT ck_plan_gate_admission_contract_version "
              + constraintDefinition);
      connection.commit();
    } catch (Exception failure) {
      throw new IllegalStateException(
          "Unable to stage a persisted unsupported-schema row", failure);
    }
  }

  private void corruptAdmissionContractVersionForRecoveryBoundaryTest(
      String runId, int unsupportedVersion) {
    try (Connection connection = dataSource.getConnection();
        var statement = connection.createStatement()) {
      connection.setAutoCommit(false);
      statement.execute("ALTER TABLE gate_record DISABLE TRIGGER USER");
      statement.execute("ALTER TABLE plan_gate_resume_outbox DISABLE TRIGGER USER");
      statement.execute("ALTER TABLE pipeline_run DISABLE TRIGGER USER");
      String constraintDefinition =
          constraintDefinition(
              connection, "gate_record", "ck_plan_gate_admission_contract_version");
      statement.execute(
          "ALTER TABLE gate_record DROP CONSTRAINT ck_plan_gate_admission_contract_version");
      try (var update =
          connection.prepareStatement(
              """
              UPDATE gate_record
                 SET admission_contract_version=?,
                     approval_evidence_json=(
                       jsonb_set(approval_evidence_json::jsonb,
                                 '{schemaVersion}', to_jsonb(?::int))::text),
                     approval_evidence_digest=encode(sha256(convert_to(
                       jsonb_set(approval_evidence_json::jsonb,
                                 '{schemaVersion}', to_jsonb(?::int))::text,
                       'UTF8')), 'hex')
               WHERE run_id=?
              """)) {
        update.setInt(1, unsupportedVersion);
        update.setInt(2, unsupportedVersion);
        update.setInt(3, unsupportedVersion);
        update.setString(4, runId);
        assertThat(update.executeUpdate()).isEqualTo(1);
      }
      statement.execute(
          "ALTER TABLE gate_record ADD CONSTRAINT ck_plan_gate_admission_contract_version "
              + constraintDefinition);
      connection.commit();
    } catch (Exception failure) {
      throw new IllegalStateException(
          "Unable to stage an unsupported admission-contract version", failure);
    }
  }

  private void corruptContinuationForRecoveryBoundaryTest(String runId, String continuation) {
    try (Connection connection = dataSource.getConnection();
        var statement = connection.createStatement()) {
      connection.setAutoCommit(false);
      statement.execute("ALTER TABLE gate_record DISABLE TRIGGER USER");
      statement.execute("ALTER TABLE plan_gate_resume_outbox DISABLE TRIGGER USER");
      String exactGateForeignKey =
          constraintDefinition(
              connection, "plan_gate_resume_outbox", "fk_plan_gate_resume_exact_gate");
      statement.execute(
          "ALTER TABLE plan_gate_resume_outbox DROP CONSTRAINT fk_plan_gate_resume_exact_gate");
      try (var update =
          connection.prepareStatement(
              """
              UPDATE gate_record
                 SET comment=?,
                     continuation_sha256=encode(sha256(convert_to(?,'UTF8')), 'hex'),
                     approval_evidence_json=(
                       jsonb_set(approval_evidence_json::jsonb, '{continuationSha256}',
                         to_jsonb(encode(sha256(convert_to(?,'UTF8')), 'hex')))::text),
                     approval_evidence_digest=encode(sha256(convert_to(
                       jsonb_set(approval_evidence_json::jsonb, '{continuationSha256}',
                         to_jsonb(encode(sha256(convert_to(?,'UTF8')), 'hex')))::text,
                       'UTF8')), 'hex')
               WHERE run_id=?
              """)) {
        update.setString(1, continuation);
        update.setString(2, continuation);
        update.setString(3, continuation);
        update.setString(4, continuation);
        update.setString(5, runId);
        assertThat(update.executeUpdate()).isEqualTo(1);
      }
      try (var update =
          connection.prepareStatement(
              """
              UPDATE plan_gate_resume_outbox
                 SET payload=?,payload_sha256=encode(sha256(convert_to(?,'UTF8')), 'hex')
               WHERE run_id=?
              """)) {
        update.setString(1, continuation);
        update.setString(2, continuation);
        update.setString(3, runId);
        assertThat(update.executeUpdate()).isEqualTo(1);
      }
      statement.execute(
          "ALTER TABLE plan_gate_resume_outbox ADD CONSTRAINT fk_plan_gate_resume_exact_gate "
              + exactGateForeignKey);
      statement.execute("ALTER TABLE plan_gate_resume_outbox ENABLE TRIGGER USER");
      statement.execute("ALTER TABLE gate_record ENABLE TRIGGER USER");
      connection.commit();
    } catch (Exception failure) {
      throw new IllegalStateException("Unable to stage an invalid continuation payload", failure);
    }
  }

  private void enablePlanGateIntegrityTriggers() {
    try (Connection connection = dataSource.getConnection();
        var statement = connection.createStatement()) {
      statement.execute("ALTER TABLE pipeline_run ENABLE TRIGGER USER");
      statement.execute("ALTER TABLE plan_gate_resume_outbox ENABLE TRIGGER USER");
      statement.execute("ALTER TABLE gate_record ENABLE TRIGGER USER");
    } catch (Exception failure) {
      throw new IllegalStateException("Unable to restore plan-gate integrity triggers", failure);
    }
  }

  private static String constraintDefinition(Connection connection, String table, String constraint)
      throws Exception {
    try (var query =
        connection.prepareStatement(
            """
            SELECT pg_get_constraintdef(c.oid)
              FROM pg_constraint c
              JOIN pg_class t ON t.oid=c.conrelid
             WHERE t.relname=? AND c.conname=?
            """)) {
      query.setString(1, table);
      query.setString(2, constraint);
      try (var result = query.executeQuery()) {
        if (!result.next()) {
          throw new IllegalStateException("Missing constraint " + constraint);
        }
        return result.getString(1);
      }
    }
  }

  private static ApiKeyResolver.CredentialResolution credentials(String orgId) {
    return new ApiKeyResolver.CredentialResolution(ResolvedKeys.SYSTEM_ONLY, orgId, Map.of());
  }

  private PlanGateResumeDispatcher dispatcher(
      RunControlPort control,
      PipelineExecutorPool pool,
      WorkspaceRestoreService workspaceRestore,
      SseBroadcaster broadcaster) {
    when(control.adoptRuntimeHeartbeat(any(), any())).thenReturn(true);
    AgentProperties properties = new AgentProperties();
    properties.setWorkspace(workspaceRoot.toString());
    PlanGateResumeDispatcher dispatcher =
        new PlanGateResumeDispatcher(
            store,
            new ObjectMapper(),
            new PlanGateWorkspaceRecovery(store, properties, workspaceRestore),
            pool,
            control,
            broadcaster);
    ReflectionTestUtils.setField(dispatcher, "leaseSeconds", 120L);
    ReflectionTestUtils.setField(dispatcher, "maxBatch", 16);
    ReflectionTestUtils.setField(dispatcher, "maxWorkspaceRestoreSeconds", 900L);
    return dispatcher;
  }

  private <T> T inTransaction(Supplier<T> work) {
    return transactions.execute(ignored -> work.get());
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(20, TimeUnit.SECONDS)) {
        throw new IllegalStateException("timed out waiting for concurrent test step");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted waiting for concurrent test step", interrupted);
    }
  }

  private void assertTuple(String runId, String runStatus, String outboxStatus) {
    assertThat(
            jdbc.queryForMap(
                "SELECT r.status AS run_status,o.status AS outbox_status FROM pipeline_run r"
                    + " JOIN plan_gate_resume_outbox o ON o.run_id=r.run_id WHERE r.run_id=?",
                runId))
        .containsEntry("run_status", runStatus)
        .containsEntry("outbox_status", outboxStatus);
  }

  private void assertOutbox(String runId, String status, int attempts, String owner) {
    assertThat(outboxRow(runId))
        .containsEntry("status", status)
        .containsEntry("attempt_count", attempts)
        .containsEntry("lease_owner", owner);
  }

  private Map<String, Object> outboxRow(String runId) {
    return jdbc.queryForMap("SELECT * FROM plan_gate_resume_outbox WHERE run_id=?", runId);
  }

  private String gateReceipt(String runId) {
    return jdbc.queryForObject(
        "SELECT approval_evidence_json FROM gate_record WHERE run_id=?", String.class, runId);
  }

  private record Fixture(String runId, AppUserDetails actor, PlanApprovalService.Command approve) {}
}

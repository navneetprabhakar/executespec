package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.config.AgentProperties;
import com.navneet.executespec.deployment.WorkspaceRestoreService;
import com.navneet.executespec.sse.SseBroadcaster;
import com.navneet.executespec.state.entity.PipelineRun;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class PlanGateResumeDispatcherTest {

  @Mock PlanGateResumeStore store;
  @Mock WorkspaceRestoreService workspaceRestoreService;
  @Mock PipelineExecutorPool pool;
  @Mock RunControlPort controlPort;
  @Mock SseBroadcaster broadcaster;
  @Mock ExecutorService executorService;
  @TempDir Path workspace;

  @Test
  void retryBackoffIsBoundedAndDeterministic() {
    assertThat(PlanGateResumeDispatcher.retryBackoff(0)).isEqualTo(Duration.ofSeconds(1));
    assertThat(PlanGateResumeDispatcher.retryBackoff(1)).isEqualTo(Duration.ofSeconds(2));
    assertThat(PlanGateResumeDispatcher.retryBackoff(8)).isEqualTo(Duration.ofSeconds(256));
    assertThat(PlanGateResumeDispatcher.retryBackoff(30)).isEqualTo(Duration.ofMinutes(5));
  }

  @Test
  void queuedHandoffOwnersAreExplicitAndProcessUnique() {
    String first = PlanGateResumeDispatcher.newQueuedHandoffOwner();
    String second = PlanGateResumeDispatcher.newQueuedHandoffOwner();

    assertThat(first).isNotEqualTo(second);
    assertThat(PlanGateResumeDispatcher.isQueuedHandoffOwner(first)).isTrue();
    assertThat(PlanGateResumeDispatcher.isQueuedHandoffOwner(second)).isTrue();
    assertThat(PlanGateResumeDispatcher.isQueuedHandoffOwner(ExecutionHostIdentity.current()))
        .isFalse();
  }

  @Test
  void postHandoffPoolRejectionReleasesOwnershipUnderTheExactLocalFence() throws Exception {
    String runId = "run-1";
    String payload =
        "{\"schemaVersion\":1,\"localRun\":false,"
            + "\"requireContinuationConfirmation\":false,"
            + "\"requireContinuationBenchmarkPrior\":false,\"pinnedModel\":null,"
            + "\"lowComplexityWorkerStartTierOverride\":null}";
    String sha = PlanGateOpenService.sha256(payload);
    var lease =
        new PlanGateResumeStore.Lease(
            runId, "gate:plan:" + runId, payload, sha, 1, 1, 3, "node-a", UUID.randomUUID());
    PipelineRun run =
        PipelineRun.builder()
            .runId(runId)
            .status("WORKING")
            .planJson("{\"tasks\":[{\"id\":\"t1\"}]}")
            .build();
    Files.createDirectories(workspace.resolve(runId));
    AgentProperties properties = new AgentProperties();
    properties.setWorkspace(workspace.toString());
    when(store.exhaustedClaimableRunIds(any())).thenReturn(java.util.List.of());
    when(store.claimNext(any(), any(), any())).thenReturn(Optional.of(lease), Optional.empty());
    when(store.heartbeat(eq(lease), any(), any())).thenReturn(true);
    var handoff =
        new PlanGateResumeStore.Handoff(
            run,
            lease.gateKey(),
            payload,
            sha,
            1,
            1,
            3,
            PlanGateResumeDispatcher.newQueuedHandoffOwner(),
            java.time.Instant.now());
    when(store.handoff(eq(lease), any(), any())).thenReturn(handoff);
    when(store.releaseRejectedHandoff(eq(handoff), any(), any(), any())).thenReturn(true);
    when(pool.executor()).thenReturn(executorService);
    AtomicReference<Future<?>> registered = new AtomicReference<>();
    doAnswer(
            invocation -> {
              registered.set(invocation.getArgument(1));
              return null;
            })
        .when(controlPort)
        .putExternallyHeartbeatedActiveRun(eq(runId), any());
    when(controlPort.removeActiveRun(eq(runId), any()))
        .thenAnswer(invocation -> invocation.getArgument(1) == registered.get());
    org.mockito.Mockito.doThrow(new RejectedExecutionException("stopping"))
        .when(executorService)
        .execute(any());
    PlanGateResumeDispatcher dispatcher =
        new PlanGateResumeDispatcher(
            store,
            new ObjectMapper(),
            new PlanGateWorkspaceRecovery(store, properties, workspaceRestoreService),
            pool,
            controlPort,
            broadcaster);
    ReflectionTestUtils.setField(dispatcher, "leaseSeconds", 120L);
    ReflectionTestUtils.setField(dispatcher, "maxBatch", 2);
    ReflectionTestUtils.setField(dispatcher, "maxWorkspaceRestoreSeconds", 900L);
    ReflectionTestUtils.setField(dispatcher, "executionStaleSeconds", 120L);

    dispatcher.kick();

    verify(controlPort).putExternallyHeartbeatedActiveRun(eq(runId), any());
    verify(controlPort).removeActiveRun(eq(runId), any());
    verify(store).releaseRejectedHandoff(eq(handoff), any(), any(), any());
    verify(store, never()).retry(any(), any(), any(), any());
    verify(store, never()).dead(any(), any(), any());
    dispatcher.shutdownLeaseRenewalExecutor();
  }

  @Test
  void startupSweepRedispatchesStaleCompletedHandoffWithoutGenericRecovery() throws Exception {
    String runId = "stale-handoff";
    String payload =
        "{\"schemaVersion\":1,\"localRun\":false,"
            + "\"requireContinuationConfirmation\":false,"
            + "\"requireContinuationBenchmarkPrior\":false,\"pinnedModel\":null,"
            + "\"lowComplexityWorkerStartTierOverride\":null}";
    String sha = PlanGateOpenService.sha256(payload);
    PipelineRun run =
        PipelineRun.builder()
            .runId(runId)
            .status("WORKING")
            .planJson("{\"tasks\":[{\"id\":\"t1\"}]}")
            .build();
    PlanGateResumeStore.Handoff handoff =
        new PlanGateResumeStore.Handoff(
            run,
            "gate:plan:" + runId,
            payload,
            sha,
            1,
            2,
            3,
            PlanGateResumeDispatcher.newQueuedHandoffOwner(),
            java.time.Instant.now());
    AgentProperties properties = new AgentProperties();
    properties.setWorkspace(workspace.toString());
    doAnswer(
            ignored -> {
              Files.createDirectories(workspace.resolve(runId));
              return null;
            })
        .when(workspaceRestoreService)
        .ensureWorkspaceAvailable(runId);
    when(store.staleCompletedHandoffRunIds(any(), any(), eq(2)))
        .thenReturn(java.util.List.of(runId));
    when(store.claimStaleCompletedHandoff(eq(runId), any(), any(), any()))
        .thenReturn(Optional.of(handoff));
    when(store.heartbeatRegisteredHandoff(eq(handoff), any())).thenReturn(true);
    when(store.exhaustedClaimableRunIds(any())).thenReturn(java.util.List.of());
    when(store.claimNext(any(), any(), any())).thenReturn(Optional.empty());
    when(pool.executor()).thenReturn(executorService);
    when(store.startRegisteredHandoff(eq(handoff), eq(ExecutionHostIdentity.current()), any()))
        .thenReturn(true);
    when(controlPort.adoptRuntimeHeartbeat(eq(runId), any())).thenReturn(true);
    doAnswer(
            invocation -> {
              ((Runnable) invocation.getArgument(0)).run();
              return null;
            })
        .when(executorService)
        .execute(any());
    PlanGateResumeDispatcher dispatcher =
        new PlanGateResumeDispatcher(
            store,
            new ObjectMapper(),
            new PlanGateWorkspaceRecovery(store, properties, workspaceRestoreService),
            pool,
            controlPort,
            broadcaster);
    ReflectionTestUtils.setField(dispatcher, "leaseSeconds", 120L);
    ReflectionTestUtils.setField(dispatcher, "maxBatch", 2);
    ReflectionTestUtils.setField(dispatcher, "executionStaleSeconds", 120L);

    dispatcher.onApplicationReady();

    verify(store).claimStaleCompletedHandoff(eq(runId), any(), any(), any());
    verify(store, atLeastOnce()).heartbeatRegisteredHandoff(eq(handoff), any());
    verify(workspaceRestoreService).ensureWorkspaceAvailable(runId);
    verify(controlPort).putExternallyHeartbeatedActiveRun(eq(runId), any());
    verify(controlPort).adoptRuntimeHeartbeat(eq(runId), any());
    verify(controlPort).runPipelineForResume(any());
    dispatcher.shutdownLeaseRenewalExecutor();
  }

  @Test
  void queuedFutureThatLosesDurableDispatchOwnershipNeverRunsPipeline() throws Exception {
    String runId = "owner-lost-before-start";
    String payload =
        "{\"schemaVersion\":1,\"localRun\":false,"
            + "\"requireContinuationConfirmation\":false,"
            + "\"requireContinuationBenchmarkPrior\":false,\"pinnedModel\":null,"
            + "\"lowComplexityWorkerStartTierOverride\":null}";
    String sha = PlanGateOpenService.sha256(payload);
    var lease =
        new PlanGateResumeStore.Lease(
            runId, "gate:plan:" + runId, payload, sha, 1, 1, 3, "node-a", UUID.randomUUID());
    PipelineRun run =
        PipelineRun.builder()
            .runId(runId)
            .status("WORKING")
            .planJson("{\"tasks\":[{\"id\":\"t1\"}]}")
            .build();
    Files.createDirectories(workspace.resolve(runId));
    AgentProperties properties = new AgentProperties();
    properties.setWorkspace(workspace.toString());
    when(store.staleCompletedHandoffRunIds(any(), any(), eq(2))).thenReturn(java.util.List.of());
    when(store.exhaustedClaimableRunIds(any())).thenReturn(java.util.List.of());
    when(store.claimNext(any(), any(), any())).thenReturn(Optional.of(lease), Optional.empty());
    when(store.heartbeat(eq(lease), any(), any())).thenReturn(true);
    PlanGateResumeStore.Handoff handoff =
        new PlanGateResumeStore.Handoff(
            run,
            lease.gateKey(),
            payload,
            sha,
            1,
            1,
            3,
            PlanGateResumeDispatcher.newQueuedHandoffOwner(),
            Instant.now());
    when(store.handoff(eq(lease), any(), any())).thenReturn(handoff);
    when(store.startRegisteredHandoff(eq(handoff), eq(ExecutionHostIdentity.current()), any()))
        .thenReturn(false);
    when(pool.executor()).thenReturn(executorService);
    AtomicReference<Runnable> queuedTask = new AtomicReference<>();
    doAnswer(
            invocation -> {
              queuedTask.set(invocation.getArgument(0));
              return null;
            })
        .when(executorService)
        .execute(any());
    PlanGateResumeDispatcher dispatcher =
        new PlanGateResumeDispatcher(
            store,
            new ObjectMapper(),
            new PlanGateWorkspaceRecovery(store, properties, workspaceRestoreService),
            pool,
            controlPort,
            broadcaster);
    ReflectionTestUtils.setField(dispatcher, "leaseSeconds", 120L);
    ReflectionTestUtils.setField(dispatcher, "maxBatch", 2);
    ReflectionTestUtils.setField(dispatcher, "executionStaleSeconds", 120L);

    dispatcher.kick();
    assertThat(queuedTask.get()).isNotNull();

    queuedTask.get().run();

    verify(store).startRegisteredHandoff(eq(handoff), eq(ExecutionHostIdentity.current()), any());
    verify(controlPort, never()).runPipelineForResume(any());
    verify(controlPort).removeActiveRun(eq(runId), any());
    dispatcher.shutdownLeaseRenewalExecutor();
  }

  @Test
  void queuedTaskRenewsDurableHeartbeatBeforeItsFirstInstruction() throws Exception {
    String runId = "queued-heartbeat";
    String payload =
        "{\"schemaVersion\":1,\"localRun\":false,"
            + "\"requireContinuationConfirmation\":false,"
            + "\"requireContinuationBenchmarkPrior\":false,\"pinnedModel\":null,"
            + "\"lowComplexityWorkerStartTierOverride\":null}";
    String sha = PlanGateOpenService.sha256(payload);
    var lease =
        new PlanGateResumeStore.Lease(
            runId, "gate:plan:" + runId, payload, sha, 1, 1, 3, "node-a", UUID.randomUUID());
    PipelineRun run =
        PipelineRun.builder()
            .runId(runId)
            .status("WORKING")
            .planJson("{\"tasks\":[{\"id\":\"t1\"}]}")
            .build();
    Files.createDirectories(workspace.resolve(runId));
    AgentProperties properties = new AgentProperties();
    properties.setWorkspace(workspace.toString());
    when(store.staleCompletedHandoffRunIds(any(), any(), eq(2))).thenReturn(java.util.List.of());
    when(store.exhaustedClaimableRunIds(any())).thenReturn(java.util.List.of());
    when(store.claimNext(any(), any(), any())).thenReturn(Optional.of(lease), Optional.empty());
    when(store.heartbeat(eq(lease), any(), any())).thenReturn(true);
    PlanGateResumeStore.Handoff handoff =
        new PlanGateResumeStore.Handoff(
            run,
            lease.gateKey(),
            payload,
            sha,
            1,
            1,
            3,
            PlanGateResumeDispatcher.newQueuedHandoffOwner(),
            Instant.now());
    when(store.handoff(eq(lease), any(), any())).thenReturn(handoff);
    when(store.heartbeatRegisteredHandoff(eq(handoff), any())).thenReturn(true);
    when(pool.executor()).thenReturn(executorService);
    AtomicReference<Runnable> queuedTask = new AtomicReference<>();
    doAnswer(
            invocation -> {
              queuedTask.set(invocation.getArgument(0));
              return null;
            })
        .when(executorService)
        .execute(any());
    ScheduledExecutorService heartbeatExecutor =
        org.mockito.Mockito.mock(ScheduledExecutorService.class);
    @SuppressWarnings("unchecked")
    ScheduledFuture<Object> heartbeatFuture = org.mockito.Mockito.mock(ScheduledFuture.class);
    ArgumentCaptor<Runnable> heartbeatTick = ArgumentCaptor.forClass(Runnable.class);
    doReturn(heartbeatFuture)
        .when(heartbeatExecutor)
        .scheduleAtFixedRate(heartbeatTick.capture(), anyLong(), anyLong(), any());
    PlanGateResumeDispatcher dispatcher =
        new PlanGateResumeDispatcher(
            store,
            new ObjectMapper(),
            new PlanGateWorkspaceRecovery(store, properties, workspaceRestoreService),
            pool,
            controlPort,
            broadcaster);
    ScheduledExecutorService originalHeartbeatExecutor =
        (ScheduledExecutorService) ReflectionTestUtils.getField(dispatcher, "leaseRenewalExecutor");
    assertThat(originalHeartbeatExecutor).isNotNull();
    originalHeartbeatExecutor.shutdownNow();
    ReflectionTestUtils.setField(dispatcher, "leaseRenewalExecutor", heartbeatExecutor);
    ReflectionTestUtils.setField(dispatcher, "leaseSeconds", 120L);
    ReflectionTestUtils.setField(dispatcher, "maxBatch", 2);
    ReflectionTestUtils.setField(dispatcher, "executionStaleSeconds", 120L);

    dispatcher.kick();
    assertThat(queuedTask.get()).isNotNull();
    assertThat(heartbeatTick.getValue()).isNotNull();

    heartbeatTick.getValue().run();

    verify(store).heartbeatRegisteredHandoff(eq(handoff), any());
    verify(store, never()).startRegisteredHandoff(any(), any(), any());
    verify(controlPort, never()).runPipelineForResume(any());
    dispatcher.shutdownLeaseRenewalExecutor();
  }

  @Test
  void slowWorkspaceOperationRenewsLeaseUntilItCompletes() {
    PlanGateResumeStore.Lease lease =
        new PlanGateResumeStore.Lease(
            "run-1", "gate:plan:run-1", "{}", "a".repeat(64), 1, 1, 3, "node-a", UUID.randomUUID());
    AgentProperties properties = new AgentProperties();
    properties.setWorkspace(workspace.toString());
    PlanGateResumeDispatcher dispatcher =
        new PlanGateResumeDispatcher(
            store,
            new ObjectMapper(),
            new PlanGateWorkspaceRecovery(store, properties, workspaceRestoreService),
            pool,
            controlPort,
            broadcaster);
    ReflectionTestUtils.setField(dispatcher, "leaseSeconds", 10L);
    when(store.heartbeat(eq(lease), any(), any())).thenReturn(true);

    dispatcher.runWithLeaseRenewal(
        lease,
        () -> {
          try {
            Thread.sleep(40);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        },
        Duration.ofMillis(5),
        20);

    verify(store, atLeastOnce()).heartbeat(eq(lease), any(), any());
    dispatcher.shutdownLeaseRenewalExecutor();
  }

  @Test
  void blockingWorkspaceRestoreIsInterruptedAtRenewalBudget() {
    PlanGateResumeStore.Lease lease =
        new PlanGateResumeStore.Lease(
            "run-1", "gate:plan:run-1", "{}", "a".repeat(64), 1, 1, 3, "node-a", UUID.randomUUID());
    AgentProperties properties = new AgentProperties();
    properties.setWorkspace(workspace.toString());
    PlanGateResumeDispatcher dispatcher =
        new PlanGateResumeDispatcher(
            store,
            new ObjectMapper(),
            new PlanGateWorkspaceRecovery(store, properties, workspaceRestoreService),
            pool,
            controlPort,
            broadcaster);
    ReflectionTestUtils.setField(dispatcher, "leaseSeconds", 10L);
    when(store.heartbeat(eq(lease), any(), any())).thenReturn(true);

    long started = System.nanoTime();
    assertThatThrownBy(
            () ->
                dispatcher.runWithLeaseRenewal(
                    lease,
                    () -> {
                      try {
                        Thread.sleep(30_000);
                      } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                      }
                    },
                    Duration.ofMillis(10),
                    2))
        .isInstanceOf(PlanGateResumeStore.StaleLeaseException.class)
        .hasMessage("PLAN_GATE_RESUME_RESTORE_TIMEOUT");
    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
    dispatcher.shutdownLeaseRenewalExecutor();
  }
}

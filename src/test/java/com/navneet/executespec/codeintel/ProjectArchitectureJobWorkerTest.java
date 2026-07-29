package com.navneet.executespec.codeintel;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;

import com.navneet.executespec.executioncontext.LlmExecutionOperationClaim;
import com.navneet.executespec.executioncontext.LlmExecutionOperationCoordinator;
import com.navneet.executespec.executioncontext.LlmExecutionOperationDescriptor;
import com.navneet.executespec.executioncontext.LlmExecutionOperationService;
import com.navneet.executespec.executioncontext.LlmExecutionReference;
import com.navneet.executespec.executioncontext.LlmExecutionScope;
import com.navneet.executespec.project.service.ProjectRepoService;
import com.navneet.executespec.tool.ExecutionContext;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

class ProjectArchitectureJobWorkerTest {
  private ProjectArchitectureJobStore store;
  private ProjectArchitectureJobService jobs;
  private ProjectArchitectureService architecture;
  private ProjectArchitectureJobPublisher publisher;
  private LlmExecutionOperationCoordinator.Lease operationLease;
  private ExecutionContext operationContext;
  private java.util.concurrent.ScheduledExecutorService leaseExecutor;
  private ProjectArchitectureJobWorker worker;

  @BeforeEach
  void setUp() {
    store = mock(ProjectArchitectureJobStore.class);
    jobs = mock(ProjectArchitectureJobService.class);
    architecture = mock(ProjectArchitectureService.class);
    publisher = mock(ProjectArchitectureJobPublisher.class);
    operationLease = operationLease();
    operationContext =
        new ExecutionContext(
            "project-architecture-job",
            "architecture-job",
            java.nio.file.Path.of("/tmp").toAbsolutePath());
    leaseExecutor = Executors.newSingleThreadScheduledExecutor();
    worker =
        new ProjectArchitectureJobWorker(
            store,
            jobs,
            architecture,
            publisher,
            mock(ProjectRepoService.class),
            mock(StalenessService.class),
            new ProjectArchitectureJobProperties(),
            mock(ThreadPoolTaskExecutor.class),
            leaseExecutor);
    when(store.isLive(any())).thenReturn(true);
    when(jobs.claimOperation(any(), anyString(), any())).thenReturn(Optional.of(operationLease));
    when(jobs.bindOperationContext(any(), eq(operationLease))).thenReturn(operationContext);
  }

  @AfterEach
  void tearDown() {
    leaseExecutor.shutdownNow();
  }

  @Test
  void knownProviderFailureAfterExecutionStartsIsTerminalAndNeverRequeued() {
    var preparing =
        ProjectArchitectureJobFixture.job(ProjectArchitectureJobStore.Status.RUNNING, "PREPARING");
    var prepared =
        ProjectArchitectureJobFixture.job(ProjectArchitectureJobStore.Status.RUNNING, "EXECUTING");
    var lease =
        new ProjectArchitectureJobStore.Lease(
            preparing, "worker-1", 1, Instant.now().plusSeconds(900));
    var snapshot = new ProjectArchitectureJobService.SourceSnapshot(1, "project-1", List.of());
    var evidence = new ProjectArchitectureJobService.Prepared(snapshot, "{}", "a".repeat(64));
    when(jobs.prepare(any())).thenReturn(evidence);
    when(jobs.bindPrepared(any(), eq(evidence))).thenReturn(prepared);
    when(store.beginExecuting(any())).thenReturn(true);
    when(jobs.resolveCredentials(prepared)).thenReturn(Map.of());
    when(architecture.stageForDurableJob(
            eq(prepared), eq(snapshot), any(), any(), eq(operationContext)))
        .thenThrow(new IllegalStateException("provider failed"));
    when(store.needsConfirmation(any(), any(), any())).thenReturn(true);

    worker.process(lease);

    verify(store).needsConfirmation(any(), eq("ILLEGALSTATEEXCEPTION"), any());
    verify(jobs).terminateOperation(eq(operationLease), eq("ILLEGALSTATEEXCEPTION"), anyString());
    verify(store, never()).failOrRequeue(any(), anyString(), anyString(), anyInt(), any());
    verifyNoInteractions(publisher);
  }

  @Test
  void sourceChangeAfterStagingCannotPromote() {
    var preparing =
        ProjectArchitectureJobFixture.job(ProjectArchitectureJobStore.Status.RUNNING, "PREPARING");
    var prepared =
        ProjectArchitectureJobFixture.job(ProjectArchitectureJobStore.Status.RUNNING, "EXECUTING");
    var lease =
        new ProjectArchitectureJobStore.Lease(
            preparing, "worker-1", 1, Instant.now().plusSeconds(900));
    var snapshot = new ProjectArchitectureJobService.SourceSnapshot(1, "project-1", List.of());
    var evidence = new ProjectArchitectureJobService.Prepared(snapshot, "{}", "a".repeat(64));
    var staged =
        new ProjectArchitectureService.StagedArtifacts(
            1, "project-1", List.of(), List.of(), null, "", "QUALIFIED", List.of());
    when(jobs.prepare(any())).thenReturn(evidence);
    when(jobs.bindPrepared(any(), eq(evidence))).thenReturn(prepared);
    when(store.beginExecuting(any())).thenReturn(true);
    when(jobs.resolveCredentials(prepared)).thenReturn(Map.of());
    when(architecture.stageForDurableJob(
            eq(prepared), eq(snapshot), any(), any(), eq(operationContext)))
        .thenReturn(staged);
    when(jobs.canonicalJson(any())).thenReturn("{}");
    when(store.stage(any(), any(), any(), any(), any())).thenReturn(true);
    when(store.get(prepared.id())).thenReturn(java.util.Optional.of(prepared));
    when(jobs.sourceStillCurrent(prepared)).thenReturn(false);

    worker.process(lease);

    verify(store).fail(any(), eq("SOURCE_CHANGED_DURING_EXECUTION"), any());
    verify(jobs)
        .terminateOperation(
            eq(operationLease),
            eq("SOURCE_CHANGED_DURING_EXECUTION"),
            eq("Architecture source changed before publication"));
    verify(store, never()).beginPublishing(any());
    verifyNoInteractions(publisher);
  }

  @Test
  void logicalOperationCompletesAfterDurableStageAndBeforePublication() {
    var preparing =
        ProjectArchitectureJobFixture.job(ProjectArchitectureJobStore.Status.RUNNING, "PREPARING");
    var prepared =
        ProjectArchitectureJobFixture.job(ProjectArchitectureJobStore.Status.RUNNING, "EXECUTING");
    var lease =
        new ProjectArchitectureJobStore.Lease(
            preparing, "worker-1", 1, Instant.now().plusSeconds(900));
    var snapshot = new ProjectArchitectureJobService.SourceSnapshot(1, "project-1", List.of());
    var evidence = new ProjectArchitectureJobService.Prepared(snapshot, "{}", "a".repeat(64));
    var staged =
        new ProjectArchitectureService.StagedArtifacts(
            1, "project-1", List.of(), List.of(), null, "", "QUALIFIED", List.of());
    var architectureDoc =
        com.navneet.executespec.codeintel.entity.ProjectArchitectureDoc.builder()
            .id(UUID.randomUUID())
            .projectId("project-1")
            .version(1)
            .content("# architecture")
            .build();
    var published =
        new ProjectArchitectureJobPublisher.Published(
            architectureDoc, List.of(), List.of(), List.of(), "summary");
    when(jobs.prepare(any())).thenReturn(evidence);
    when(jobs.bindPrepared(any(), eq(evidence))).thenReturn(prepared);
    when(store.beginExecuting(any())).thenReturn(true);
    when(jobs.resolveCredentials(prepared)).thenReturn(Map.of());
    when(architecture.stageForDurableJob(
            eq(prepared), eq(snapshot), any(), any(), eq(operationContext)))
        .thenReturn(staged);
    when(jobs.canonicalJson(any())).thenReturn("{}");
    when(store.stage(any(), any(), any(), any(), any())).thenReturn(true);
    when(store.get(prepared.id())).thenReturn(Optional.of(prepared));
    when(jobs.sourceStillCurrent(prepared)).thenReturn(true);
    when(store.beginPublishing(any())).thenReturn(true);
    when(publisher.promote(any(), eq(evidence.digest()))).thenReturn(published);

    worker.process(lease);

    var order = inOrder(store, jobs, publisher);
    order.verify(store).stage(any(), any(), any(), any(), any());
    order.verify(jobs).completeOperation(operationLease);
    order.verify(publisher).promote(any(), eq(evidence.digest()));
    order.verify(jobs).completeContexts(prepared, "worker-1");
    verify(jobs, never()).terminateOperation(eq(operationLease), anyString(), anyString());
  }

  private static LlmExecutionOperationCoordinator.Lease operationLease() {
    LlmExecutionOperationService operations = mock(LlmExecutionOperationService.class);
    LlmExecutionOperationCoordinator coordinator = new LlmExecutionOperationCoordinator(operations);
    LlmExecutionReference reference =
        new LlmExecutionReference("ctx-repo", LlmExecutionScope.customer("org-1"));
    LlmExecutionOperationDescriptor descriptor =
        LlmExecutionOperationDescriptor.of(
            "project-architecture:test", "PROJECT_ARCHITECTURE", "test", 3);
    LlmExecutionOperationClaim claim =
        new LlmExecutionOperationClaim(reference, "llmop-test", 1, 1, UUID.randomUUID());
    when(operations.claimOrResume(reference, descriptor, "worker-1", Duration.ofMinutes(2)))
        .thenReturn(Optional.of(claim));
    return coordinator
        .claimOrResume(reference, descriptor, "worker-1", Duration.ofMinutes(2))
        .orElseThrow();
  }
}

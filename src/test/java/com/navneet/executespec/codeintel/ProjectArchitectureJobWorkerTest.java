package com.navneet.executespec.codeintel;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import com.navneet.executespec.project.service.ProjectRepoService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

class ProjectArchitectureJobWorkerTest {
  private ProjectArchitectureJobStore store;
  private ProjectArchitectureJobService jobs;
  private ProjectArchitectureService architecture;
  private ProjectArchitectureJobPublisher publisher;
  private java.util.concurrent.ScheduledExecutorService leaseExecutor;
  private ProjectArchitectureJobWorker worker;

  @BeforeEach
  void setUp() {
    store = mock(ProjectArchitectureJobStore.class);
    jobs = mock(ProjectArchitectureJobService.class);
    architecture = mock(ProjectArchitectureService.class);
    publisher = mock(ProjectArchitectureJobPublisher.class);
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
    when(architecture.stageForDurableJob(eq(prepared), eq(snapshot), any(), any()))
        .thenThrow(new IllegalStateException("provider failed"));
    when(store.needsConfirmation(any(), any(), any())).thenReturn(true);

    worker.process(lease);

    verify(store).needsConfirmation(any(), eq("ILLEGALSTATEEXCEPTION"), any());
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
    when(architecture.stageForDurableJob(eq(prepared), eq(snapshot), any(), any()))
        .thenReturn(staged);
    when(jobs.canonicalJson(any())).thenReturn("{}");
    when(store.stage(any(), any(), any(), any(), any())).thenReturn(true);
    when(store.get(prepared.id())).thenReturn(java.util.Optional.of(prepared));
    when(jobs.sourceStillCurrent(prepared)).thenReturn(false);

    worker.process(lease);

    verify(store).fail(any(), eq("SOURCE_CHANGED_DURING_EXECUTION"), any());
    verify(store, never()).beginPublishing(any());
    verifyNoInteractions(publisher);
  }
}

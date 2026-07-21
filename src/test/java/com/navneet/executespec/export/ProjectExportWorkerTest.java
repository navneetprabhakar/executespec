package com.navneet.executespec.export;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

@ExtendWith(MockitoExtension.class)
class ProjectExportWorkerTest {

  @Mock ProjectExportJobStore store;
  @Mock ProjectExportBundleWriter writer;
  @Mock ProjectExportObjectStore objectStore;
  @Mock ThreadPoolTaskExecutor executor;
  @TempDir Path temp;

  private ProjectExportProperties properties;
  private ProjectExportWorker worker;
  private ProjectExportJobStore.Lease lease;
  private Path staging;

  @BeforeEach
  void setUp() throws Exception {
    properties = new ProjectExportProperties();
    worker =
        new ProjectExportWorker(
            store, writer, objectStore, properties, executor, new SimpleMeterRegistry());
    var job = ExportTestFixtures.job(ProjectExportJobStore.Status.RUNNING);
    lease = new ProjectExportJobStore.Lease(job, "worker", job.leaseToken(), job.leaseExpiresAt());
    staging = temp.resolve("export.tar.gz");
    when(objectStore.createStagingFile(job.id(), job.leaseToken())).thenReturn(staging);
  }

  @Test
  void successfulPublicationRequiresLiveLeaseAndFencedCompletion() throws Exception {
    doAnswer(
            invocation -> {
              Files.writeString(invocation.getArgument(1), "archive");
              return new ProjectExportBundleWriter.Result(0, 0, 0, false, false, false, 7);
            })
        .when(writer)
        .write(eq(lease.job()), eq(staging), any());
    when(store.cancellationRequested(lease)).thenReturn(false);
    when(store.renew(eq(lease), any(Duration.class))).thenReturn(true);
    var artifact =
        new ProjectExportObjectStore.Artifact(
            "local:project-exports/a/1.tar.gz", 7, "a".repeat(64));
    when(objectStore.publish(any(), anyLong(), any(), eq(staging), any())).thenReturn(artifact);
    when(store.complete(eq(lease), eq(artifact.objectKey()), eq(7L), eq(artifact.sha256()), any()))
        .thenReturn(true);

    worker.process(lease);

    verify(store)
        .complete(eq(lease), eq(artifact.objectKey()), eq(7L), eq(artifact.sha256()), any());
    verify(objectStore, never()).delete(artifact.objectKey());
  }

  @Test
  void cooperativeCancellationPublishesNothingAndFinishesDurably() throws Exception {
    doAnswer(
            invocation -> {
              ProjectExportBundleWriter.Checkpoint checkpoint = invocation.getArgument(2);
              checkpoint.check();
              return null;
            })
        .when(writer)
        .write(eq(lease.job()), eq(staging), any());
    when(store.cancellationRequested(lease)).thenReturn(true);

    worker.process(lease);

    verify(store).finishCancellation(lease);
    verify(objectStore, never()).publish(any(), anyLong(), any(), any(), any());
  }

  @Test
  void boundedFailureSchedulesTypedRetryWithoutPersistingExceptionText() throws Exception {
    doThrow(new IOException("Authorization: Bearer secret-value"))
        .when(writer)
        .write(eq(lease.job()), eq(staging), any());
    when(store.cancellationRequested(lease)).thenReturn(false);
    when(store.failOrRequeue(eq(lease), eq("EXPORT_IO_FAILED"), eq(3), any()))
        .thenReturn(ProjectExportJobStore.Status.QUEUED);

    worker.process(lease);

    verify(store).failOrRequeue(eq(lease), eq("EXPORT_IO_FAILED"), eq(3), any());
  }
}

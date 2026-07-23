package com.navneet.executespec.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import com.navneet.executespec.config.AgentProperties;
import com.navneet.executespec.harness.event.PipelineEvent;
import com.navneet.executespec.saga.SideEffectOutbox;
import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.state.entity.PipelineRun;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

/**
 * RR-1676: {@code uploadZip} can return a null/absent object key, and a persisted key later reads
 * as "archived" ({@code getGcsWorkspaceKey() != null}). The listener must record only a proven,
 * present archive; an unproven key must not be persisted and the run must be enqueued for retry
 * instead.
 */
class WorkspaceGcsUploadListenerArchiveKeyTest {

  @TempDir Path tempDir;

  private WorkspaceGcsUploadListener newListener(
      GcsWorkspaceStore gcsStore,
      WorkspaceExportService exportService,
      StateStore stateStore,
      SideEffectOutbox outbox) {
    AgentProperties agentProps = new AgentProperties();
    agentProps.setWorkspace(tempDir.toString());
    // DeploymentProperties is a final record; construct a real instance with GCS enabled.
    DeploymentProperties deploymentProps =
        new DeploymentProperties(
            false,
            null,
            null,
            null,
            null,
            7,
            null,
            null,
            null,
            null,
            new DeploymentProperties.WorkspaceConfig(7, "bucket", 24, true));
    return new WorkspaceGcsUploadListener(
        agentProps, deploymentProps, exportService, gcsStore, stateStore, outbox);
  }

  @Test
  void absentArchiveObjectIsNotRecordedAndIsEnqueuedForRetry() throws Exception {
    String runId = "run-absent";
    Files.createDirectories(tempDir.resolve(runId));

    GcsWorkspaceStore gcsStore = Mockito.mock(GcsWorkspaceStore.class);
    WorkspaceExportService exportService = Mockito.mock(WorkspaceExportService.class);
    StateStore stateStore = Mockito.mock(StateStore.class);
    SideEffectOutbox outbox = Mockito.mock(SideEffectOutbox.class);

    PipelineRun run = PipelineRun.builder().runId(runId).build();
    when(stateStore.findRun(runId)).thenReturn(Optional.of(run));
    when(exportService.exportWorkspace(runId)).thenReturn(tempDir.resolve("export.zip"));
    // uploadZip returns a nonblank key, but the object never actually landed.
    when(gcsStore.uploadZip(eq(runId), any())).thenReturn("workspaces/" + runId + ".zip");
    when(gcsStore.exists(anyString())).thenReturn(false);

    WorkspaceGcsUploadListener listener = newListener(gcsStore, exportService, stateStore, outbox);
    listener.onRunCompleted(new PipelineEvent.RunCompleted(runId));

    assertThat(run.getGcsWorkspaceKey()).isNull();
    verify(stateStore, never()).saveRun(any());
    verify(outbox).enqueue(eq(runId), eq("GCS_ARCHIVE"), any());
  }

  @Test
  void presentArchiveObjectIsRecorded() throws Exception {
    String runId = "run-present";
    Files.createDirectories(tempDir.resolve(runId));

    GcsWorkspaceStore gcsStore = Mockito.mock(GcsWorkspaceStore.class);
    WorkspaceExportService exportService = Mockito.mock(WorkspaceExportService.class);
    StateStore stateStore = Mockito.mock(StateStore.class);
    SideEffectOutbox outbox = Mockito.mock(SideEffectOutbox.class);

    PipelineRun run = PipelineRun.builder().runId(runId).build();
    when(stateStore.findRun(runId)).thenReturn(Optional.of(run));
    when(exportService.exportWorkspace(runId)).thenReturn(tempDir.resolve("export2.zip"));
    String key = "workspaces/" + runId + ".zip";
    when(gcsStore.uploadZip(eq(runId), any())).thenReturn(key);
    when(gcsStore.exists(key)).thenReturn(true);

    WorkspaceGcsUploadListener listener = newListener(gcsStore, exportService, stateStore, outbox);
    listener.onRunCompleted(new PipelineEvent.RunCompleted(runId));

    assertThat(run.getGcsWorkspaceKey()).isEqualTo(key);
    verify(stateStore).saveRun(run);
    verify(outbox, never()).enqueue(anyString(), anyString(), any());
  }
}

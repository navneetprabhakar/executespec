package com.navneet.executespec.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.navneet.executespec.config.AgentProperties;
import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.state.entity.PipelineRun;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * RR-163 — the hourly archive job used to delete the local workspace unconditionally after passing
 * whatever {@code uploadZip} returned to {@code markGcsArchived}. {@code uploadZip} returns {@code
 * null} when the Storage client was disabled at startup, so a transient ADC/bucket failure could
 * permanently destroy the only copy of customer code.
 */
class WorkspaceArchiveJobRetentionTest {

  @TempDir Path temp;

  private StateStore stateStore;
  private WorkspaceExportService exportService;
  private GcsWorkspaceStore gcsStore;
  private WorkspaceArchiveJob job;
  private Path workspace;
  private Path zip;

  @BeforeEach
  void setUp() throws Exception {
    DeploymentProperties props =
        new DeploymentProperties(
            true,
            "project",
            "region",
            null,
            null,
            7,
            null,
            null,
            null,
            null,
            new DeploymentProperties.WorkspaceConfig(30, "bucket", 24, true));

    AgentProperties agentProps = new AgentProperties();
    agentProps.setWorkspace(temp.toString());
    workspace = temp.resolve("run-1");
    Files.createDirectories(workspace);
    Files.writeString(workspace.resolve("App.java"), "class App {}");

    zip = temp.resolve("run-1.zip");
    Files.writeString(zip, "zip-bytes");

    stateStore = mock(StateStore.class);
    exportService = mock(WorkspaceExportService.class);
    gcsStore = mock(GcsWorkspaceStore.class);
    when(exportService.exportWorkspace("run-1")).thenReturn(zip);

    job = new WorkspaceArchiveJob(props, agentProps, stateStore, exportService, gcsStore);
  }

  private void candidate(String gcsWorkspaceKey) {
    PipelineRun run =
        PipelineRun.builder()
            .runId("run-1")
            .completedAt(Instant.now().minusSeconds(1))
            .gcsWorkspaceKey(gcsWorkspaceKey)
            .build();
    when(stateStore.findRunsToCleanup(any())).thenReturn(List.of(run));
    when(stateStore.findRun("run-1")).thenReturn(Optional.of(run));
  }

  @Test
  void keepsLocalWorkspaceWhenUploadWasSkippedBecauseStorageWasUnavailable() throws Exception {
    candidate(null);
    when(gcsStore.uploadZip("run-1", zip)).thenReturn(null);

    job.archiveOldWorkspaces();

    assertThat(workspace.resolve("App.java")).exists();
    verify(stateStore, never()).markGcsArchived(anyString(), any());
  }

  @Test
  void keepsLocalWorkspaceWhenTheRemoteObjectCannotBeProvenToExist() throws Exception {
    candidate(null);
    when(gcsStore.uploadZip("run-1", zip)).thenReturn("workspaces/run-1.zip");
    when(gcsStore.exists("workspaces/run-1.zip")).thenReturn(false);

    job.archiveOldWorkspaces();

    assertThat(workspace.resolve("App.java")).exists();
    verify(stateStore, never()).markGcsArchived(anyString(), any());
  }

  @Test
  void keepsLocalWorkspaceWhenAPreviouslyRecordedArchiveIsNoLongerPresent() {
    candidate("workspaces/run-1.zip");
    when(gcsStore.exists("workspaces/run-1.zip")).thenReturn(false);

    job.archiveOldWorkspaces();

    assertThat(workspace.resolve("App.java")).exists();
    verify(stateStore, never()).markGcsArchived(anyString(), any());
  }

  @Test
  void deletesLocalWorkspaceOnlyAfterTheRemoteArchiveIsProven() throws Exception {
    candidate(null);
    when(gcsStore.uploadZip("run-1", zip)).thenReturn("workspaces/run-1.zip");
    when(gcsStore.exists("workspaces/run-1.zip")).thenReturn(true);

    job.archiveOldWorkspaces();

    assertThat(workspace).doesNotExist();
    verify(stateStore).markGcsArchived("run-1", "workspaces/run-1.zip");
  }
}

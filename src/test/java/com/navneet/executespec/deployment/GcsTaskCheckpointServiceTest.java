package com.navneet.executespec.deployment;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import com.navneet.executespec.integration.CorrectionWorkspaceTransaction;
import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.workspacegeneration.WorkspaceGenerationArchiveService;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * RL-2026-05-041 (audit #2) — unit coverage for the mid-run durability checkpoint gating. The live
 * GCS upload can't run without GCP creds, so this pins the BEHAVIOR around it: it must no-op (no
 * zip cost) when disabled or GCS-unconfigured, and on the happy path it must export → upload →
 * stamp the run's {@code gcsWorkspaceKey} (and never {@code gcsArchivedAt}, since the local copy
 * survives).
 */
@ExtendWith(MockitoExtension.class)
class GcsTaskCheckpointServiceTest {

  @Mock private WorkspaceExportService exportService;
  @Mock private GcsWorkspaceStore gcsStore;
  @Mock private StateStore stateStore;
  @Mock private WorkspaceGenerationArchiveService generationArchives;
  @Mock private Environment environment;

  @InjectMocks private GcsTaskCheckpointService service;

  private void enable(boolean enabled) {
    ReflectionTestUtils.setField(service, "enabled", enabled);
  }

  @Test
  void noOpWhenDisabled() throws Exception {
    enable(false);
    service.checkpointAfterTask("run-1", "t1");
    verify(exportService, never()).exportWorkspace(anyString());
    verify(gcsStore, never()).isConfigured();
  }

  @Test
  void noOpWhenGcsUnconfigured() throws Exception {
    enable(true);
    when(gcsStore.isConfigured()).thenReturn(false);
    service.checkpointAfterTask("run-1", "t1");
    // crucially: never pays the workspace-zip cost when GCS is off (e.g. local dev)
    verify(exportService, never()).exportWorkspace(anyString());
  }

  @Test
  void happyPathExportsUploadsAndStampsKey() throws Exception {
    enable(true);
    Path tmpZip = Files.createTempFile("ckpt", ".zip");
    try {
      when(gcsStore.isConfigured()).thenReturn(true);
      when(exportService.exportWorkspace("run-1")).thenReturn(tmpZip);
      when(gcsStore.uploadZip("run-1", tmpZip)).thenReturn("dev/workspace/run-1.zip");
      PipelineRun run = PipelineRun.builder().runId("run-1").build();
      when(stateStore.findRun("run-1")).thenReturn(Optional.of(run));

      service.checkpointAfterTask("run-1", "t1");

      verify(gcsStore).uploadZip("run-1", tmpZip);
      verify(stateStore).save(run);
      org.assertj.core.api.Assertions.assertThat(run.getGcsWorkspaceKey())
          .isEqualTo("dev/workspace/run-1.zip");
      // local copy is still authoritative — archived-at stays null on a mid-run checkpoint
      org.assertj.core.api.Assertions.assertThat(run.getGcsArchivedAt()).isNull();
    } finally {
      Files.deleteIfExists(tmpZip); // service also deletes; deleteIfExists is idempotent
    }
  }

  @Test
  void skipsKeyStampWhenUploadReturnsNull() throws Exception {
    enable(true);
    Path tmpZip = Files.createTempFile("ckpt", ".zip");
    try {
      when(gcsStore.isConfigured()).thenReturn(true);
      when(exportService.exportWorkspace("run-1")).thenReturn(tmpZip);
      when(gcsStore.uploadZip(eq("run-1"), any())).thenReturn(null); // upload skipped
      service.checkpointAfterTask("run-1", "t1");
      verify(stateStore, never()).save(any());
    } finally {
      Files.deleteIfExists(tmpZip);
    }
  }

  @Test
  void strictGenerationCheckpointFailsClosedInProductionWithoutGcs() {
    enable(true);
    when(gcsStore.isConfigured()).thenReturn(false);
    when(environment.acceptsProfiles(any(Profiles.class))).thenReturn(true);

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> service.checkpointGeneration("run-1", "T1", checkpoint()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("WORKSPACE_GENERATION_DURABLE_CHECKPOINT_UNAVAILABLE");
  }

  @Test
  void strictGenerationCheckpointIsExplicitlyHostBoundOutsideProduction() {
    enable(true);
    when(gcsStore.isConfigured()).thenReturn(false);
    when(environment.acceptsProfiles(any(Profiles.class))).thenReturn(false);

    GcsTaskCheckpointService.CheckpointReceipt receipt =
        service.checkpointGeneration("run-1", "T1", checkpoint());

    org.assertj.core.api.Assertions.assertThat(receipt.kind()).isEqualTo("LOCAL_SAME_HOST");
    org.assertj.core.api.Assertions.assertThat(receipt.key()).startsWith("local-same-host://");
    org.assertj.core.api.Assertions.assertThat(receipt.generation())
        .isEqualTo(checkpoint().generation());
  }

  private static CorrectionWorkspaceTransaction.Checkpoint checkpoint() {
    Path root = Path.of("/tmp/workspace-generation-test").toAbsolutePath().normalize();
    return new CorrectionWorkspaceTransaction.Checkpoint(
        root,
        java.util.List.of(
            new CorrectionWorkspaceTransaction.RepositoryCheckpoint(
                root, "a".repeat(40), "b".repeat(40))));
  }
}

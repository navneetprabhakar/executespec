package com.navneet.executespec.workspacegeneration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import com.navneet.executespec.config.AgentProperties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceGenerationRecoveryArtifactJanitorTest {

  @TempDir Path workspace;

  @Test
  void deletesOnlyOwnedRecoveryArtifactAndCompletesDurableClaim() throws Exception {
    String runId = "run-1";
    Path artifact =
        Files.createDirectories(workspace.resolve(runId + ".displaced-" + UUID.randomUUID()));
    Files.writeString(artifact.resolve("old.txt"), "old");
    WorkspaceGenerationRecoveryCleanupStore store =
        mock(WorkspaceGenerationRecoveryCleanupStore.class);
    var claim =
        new WorkspaceGenerationRecoveryCleanupStore.Claim(
            UUID.randomUUID(), runId, artifact.toString(), 1);
    when(store.claimNext(java.time.Duration.ofMinutes(10)))
        .thenReturn(Optional.of(claim), Optional.empty());

    WorkspaceGenerationRecoveryArtifactJanitor janitor = janitor(store);

    assertThat(janitor.sweep()).isEqualTo(1);
    assertThat(artifact).doesNotExist();
    verify(store).complete(claim);
    verify(store, never())
        .fail(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
  }

  @Test
  void invalidClaimIsRetriedAndCannotDeleteOutsideWorkspaceRoot() throws Exception {
    String runId = "run-2";
    Path outside = Files.createDirectories(workspace.resolveSibling(runId + ".displaced-evil"));
    Files.writeString(outside.resolve("keep.txt"), "keep");
    WorkspaceGenerationRecoveryCleanupStore store =
        mock(WorkspaceGenerationRecoveryCleanupStore.class);
    var claim =
        new WorkspaceGenerationRecoveryCleanupStore.Claim(
            UUID.randomUUID(), runId, outside.toString(), 1);
    when(store.claimNext(java.time.Duration.ofMinutes(10)))
        .thenReturn(Optional.of(claim), Optional.empty());

    assertThat(janitor(store).sweep()).isZero();
    assertThat(outside.resolve("keep.txt")).exists();
    verify(store).fail(claim, "WORKSPACE_RECOVERY_CLEANUP_PATH_REFUSED");
    verify(store, never()).complete(claim);
  }

  @Test
  void enqueueRejectsAPathThatDoesNotBelongToTheRun() throws Exception {
    WorkspaceGenerationRecoveryCleanupStore store =
        mock(WorkspaceGenerationRecoveryCleanupStore.class);
    Path wrong =
        Files.createDirectories(workspace.resolve("different.displaced-" + UUID.randomUUID()));

    assertThatThrownBy(() -> janitor(store).enqueue("run-3", wrong))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("WORKSPACE_RECOVERY_CLEANUP_PATH_REFUSED");
    verify(store, never())
        .enqueue(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
  }

  private WorkspaceGenerationRecoveryArtifactJanitor janitor(
      WorkspaceGenerationRecoveryCleanupStore store) {
    AgentProperties properties = new AgentProperties();
    properties.setWorkspace(workspace.toString());
    return new WorkspaceGenerationRecoveryArtifactJanitor(store, properties);
  }
}

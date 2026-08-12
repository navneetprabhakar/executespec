package com.navneet.executespec.workspacegeneration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import com.navneet.executespec.deployment.GcsWorkspaceStore;
import com.navneet.executespec.deployment.LocalGenerationCheckpointStore;

import org.junit.jupiter.api.Test;

class WorkspaceGenerationArchiveJanitorTest {

  @Test
  void freshReferenceFenceProtectsArchiveWithoutCallingStorage() {
    WorkspaceGenerationArchiveCleanupStore cleanup =
        mock(WorkspaceGenerationArchiveCleanupStore.class);
    GcsWorkspaceStore gcs = mock(GcsWorkspaceStore.class);
    LocalGenerationCheckpointStore local = mock(LocalGenerationCheckpointStore.class);
    var claim = claim("GCS");
    when(cleanup.claimNext(any(Duration.class))).thenReturn(Optional.of(claim), Optional.empty());
    when(cleanup.stillDeletable(claim)).thenReturn(false);

    assertThat(new WorkspaceGenerationArchiveJanitor(cleanup, gcs, local).sweep(5)).isZero();

    verify(cleanup).protectedReference(claim);
    verifyNoInteractions(gcs, local);
  }

  @Test
  void exactGcsGenerationIsDeletedOnlyAfterFreshFence() throws Exception {
    WorkspaceGenerationArchiveCleanupStore cleanup =
        mock(WorkspaceGenerationArchiveCleanupStore.class);
    GcsWorkspaceStore gcs = mock(GcsWorkspaceStore.class);
    LocalGenerationCheckpointStore local = mock(LocalGenerationCheckpointStore.class);
    var claim = claim("GCS");
    when(cleanup.claimNext(any(Duration.class))).thenReturn(Optional.of(claim), Optional.empty());
    when(cleanup.stillDeletable(claim)).thenReturn(true);

    assertThat(new WorkspaceGenerationArchiveJanitor(cleanup, gcs, local).sweep(5)).isEqualTo(1);

    var order = inOrder(cleanup, gcs);
    order.verify(cleanup).stillDeletable(claim);
    order.verify(gcs).deleteArtifactExact(claim.key(), claim.storageGeneration());
    order.verify(cleanup).complete(claim);
    verifyNoInteractions(local);
  }

  private static WorkspaceGenerationArchiveCleanupStore.Claim claim(String kind) {
    return new WorkspaceGenerationArchiveCleanupStore.Claim(
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        "run-1",
        kind,
        "dev/workspace-generations/run-1/archive.tar.gz",
        "a".repeat(64),
        "42",
        512,
        "crc",
        "etag");
  }
}

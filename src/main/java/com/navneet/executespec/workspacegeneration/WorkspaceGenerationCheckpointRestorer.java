package com.navneet.executespec.workspacegeneration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.navneet.executespec.deployment.GcsWorkspaceStore;
import com.navneet.executespec.deployment.LocalGenerationCheckpointStore;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * Exact artifact transport and restore boundary shared by same-node and replacement-node recovery.
 */
@Component
@RequiredArgsConstructor
public class WorkspaceGenerationCheckpointRestorer {

  private final GcsWorkspaceStore gcsStore;
  private final LocalGenerationCheckpointStore localStore;
  private final WorkspaceGenerationArchiveService archives;

  public void restore(
      String kind,
      String key,
      String digest,
      String storageGeneration,
      Long sizeBytes,
      String crc32c,
      String etag,
      String generation,
      Path root,
      String runId,
      Runnable fence) {
    if (sizeBytes == null || sizeBytes <= 0) {
      throw new IllegalStateException("WORKSPACE_GENERATION_CHECKPOINT_STORAGE_IDENTITY_MISSING");
    }
    Path archive = null;
    try {
      fence.run();
      archive =
          switch (kind) {
            case "GCS" ->
                gcsStore.downloadArtifactToTempExact(
                    key, runId, storageGeneration, sizeBytes, crc32c, etag);
            case "LOCAL_ARCHIVE" ->
                localStore.copyToTempExact(
                    key, runId, digest, storageGeneration, sizeBytes, crc32c, etag);
            default ->
                throw new IllegalStateException(
                    "WORKSPACE_GENERATION_CHECKPOINT_KIND_UNRESTORABLE");
          };
      fence.run();
      archives.restore(archive, digest, root, generation);
      fence.run();
    } catch (IOException failure) {
      throw new IllegalStateException("WORKSPACE_GENERATION_ARCHIVE_DOWNLOAD_FAILED", failure);
    } finally {
      if (archive != null) {
        try {
          Files.deleteIfExists(archive);
        } catch (IOException ignored) {
          // Temp cleanup is non-authoritative; the OS temp janitor owns eventual removal.
        }
      }
    }
  }
}

package com.navneet.executespec.deployment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import com.navneet.executespec.integration.CorrectionWorkspaceTransaction;
import com.navneet.executespec.workspacegeneration.WorkspaceGenerationArchiveService;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

/**
 * Creates the exact durable checkpoint receipt required before a BUILD generation can be accepted.
 */
@Service
@Slf4j
public class GcsTaskCheckpointService {

  @FunctionalInterface
  interface PhysicalWriteObserver {
    void afterWrite(String key);
  }

  /** Acknowledgement consumed by the workspace-generation acceptance transaction. */
  public record CheckpointReceipt(
      String kind,
      String key,
      String generation,
      String artifactDigest,
      String storageGeneration,
      long sizeBytes,
      String crc32c,
      String etag,
      Instant completedAt) {}

  private final WorkspaceGenerationArchiveService generationArchives;
  private final GcsWorkspaceStore gcsStore;
  private final LocalGenerationCheckpointStore localStore;
  private final Environment environment;
  private final PhysicalWriteObserver physicalWrites;

  @org.springframework.beans.factory.annotation.Autowired
  public GcsTaskCheckpointService(
      WorkspaceGenerationArchiveService generationArchives,
      GcsWorkspaceStore gcsStore,
      LocalGenerationCheckpointStore localStore,
      Environment environment) {
    this(generationArchives, gcsStore, localStore, environment, key -> {});
  }

  GcsTaskCheckpointService(
      WorkspaceGenerationArchiveService generationArchives,
      GcsWorkspaceStore gcsStore,
      LocalGenerationCheckpointStore localStore,
      Environment environment,
      PhysicalWriteObserver physicalWrites) {
    this.generationArchives = generationArchives;
    this.gcsStore = gcsStore;
    this.localStore = localStore;
    this.environment = environment;
    this.physicalWrites = physicalWrites;
  }

  @Value("${durability.task-checkpoint.enabled:true}")
  private boolean enabled;

  /**
   * Strict checkpoint boundary for V374 workspace-generation acceptance. Unlike the legacy async
   * method, this either returns an exact acknowledgement or throws; callers must not accept the
   * generation on failure. Local development writes the same exact archive to a host-bound durable
   * checkpoint store; the live workspace path is never accepted as a checkpoint.
   */
  public CheckpointReceipt checkpointGeneration(
      String runId,
      String taskIdShort,
      CorrectionWorkspaceTransaction.Checkpoint workspaceCheckpoint) {
    String generation = workspaceCheckpoint == null ? null : workspaceCheckpoint.generation();
    return checkpointGeneration(
        runId,
        taskIdShort,
        workspaceCheckpoint,
        "workspace-generations/" + runId + "/" + generation + ".tar.gz");
  }

  /**
   * Idempotent physical write for a caller-journalled deterministic object identity. The logical
   * key is persisted before this method is called; GCS enforces create-if-absent and validates the
   * exact metadata on replay.
   */
  public CheckpointReceipt checkpointGeneration(
      String runId,
      String taskIdShort,
      CorrectionWorkspaceTransaction.Checkpoint workspaceCheckpoint,
      String deterministicObjectKey) {
    String generation = workspaceCheckpoint == null ? null : workspaceCheckpoint.generation();
    Path workspaceRoot = workspaceCheckpoint == null ? null : workspaceCheckpoint.workspaceRoot();
    if (runId == null || generation == null || !generation.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("WORKSPACE_CHECKPOINT_IDENTITY_INVALID");
    }
    if (deterministicObjectKey == null
        || deterministicObjectKey.isBlank()
        || deterministicObjectKey.startsWith("/")
        || deterministicObjectKey.contains("..")) {
      throw new IllegalArgumentException("WORKSPACE_CHECKPOINT_OBJECT_KEY_INVALID");
    }
    boolean local = !enabled || !gcsStore.isConfigured();
    if (local && environment.acceptsProfiles(Profiles.of("prod"))) {
      throw new IllegalStateException("WORKSPACE_GENERATION_DURABLE_CHECKPOINT_UNAVAILABLE");
    }
    Path zipPath = null;
    try {
      zipPath = generationArchives.create(workspaceCheckpoint);
      String artifactDigest;
      try (var input = Files.newInputStream(zipPath)) {
        artifactDigest = org.apache.commons.codec.digest.DigestUtils.sha256Hex(input);
      }
      GcsWorkspaceStore.ArtifactReceipt artifact;
      String kind;
      if (local) {
        artifact = localStore.store(runId, generation, artifactDigest, zipPath);
        zipPath = null; // ownership moved into the immutable local checkpoint store
        kind = "LOCAL_ARCHIVE";
      } else {
        artifact =
            gcsStore.uploadArtifactExact(
                deterministicObjectKey,
                zipPath,
                "application/gzip",
                java.util.Map.of(
                    "runId", runId,
                    "taskId", taskIdShort == null ? "unknown" : taskIdShort,
                    "workspaceGeneration", generation,
                    "artifactDigest", artifactDigest));
        kind = "GCS";
      }
      if (artifact.key() == null || artifact.key().isBlank()) {
        throw new IllegalStateException("WORKSPACE_CHECKPOINT_UPLOAD_UNACKNOWLEDGED");
      }
      physicalWrites.afterWrite(artifact.key());
      Instant completed = Instant.now();
      log.info(
          "[GcsTaskCheckpoint] strict run={} task={} generation={} key={}",
          runId,
          taskIdShort,
          generation,
          artifact.key());
      return new CheckpointReceipt(
          kind,
          artifact.key(),
          generation,
          artifactDigest,
          artifact.storageGeneration(),
          artifact.sizeBytes(),
          artifact.crc32c(),
          artifact.etag(),
          completed);
    } catch (java.io.IOException failure) {
      throw new IllegalStateException("WORKSPACE_CHECKPOINT_DIGEST_FAILED", failure);
    } finally {
      if (zipPath != null) {
        try {
          Files.deleteIfExists(zipPath);
        } catch (Exception cleanupFailure) {
          log.warn(
              "[GcsTaskCheckpoint] strict temp cleanup failed run={} task={}: {}",
              runId,
              taskIdShort,
              cleanupFailure.getMessage());
        }
      }
    }
  }
}

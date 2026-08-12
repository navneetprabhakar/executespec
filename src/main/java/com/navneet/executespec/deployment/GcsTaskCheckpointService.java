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

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Creates the exact durable checkpoint receipt required before a BUILD generation can be accepted.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GcsTaskCheckpointService {

  /** Acknowledgement consumed by the workspace-generation acceptance transaction. */
  public record CheckpointReceipt(
      String kind, String key, String generation, String artifactDigest, Instant completedAt) {}

  private final WorkspaceGenerationArchiveService generationArchives;
  private final GcsWorkspaceStore gcsStore;
  private final Environment environment;

  @Value("${durability.task-checkpoint.enabled:true}")
  private boolean enabled;

  /**
   * Strict checkpoint boundary for V374 workspace-generation acceptance. Unlike the legacy async
   * method, this either returns an exact acknowledgement or throws; callers must not accept the
   * generation on failure. Local development receives a host-bound receipt that recovery may use
   * only on the same host.
   */
  public CheckpointReceipt checkpointGeneration(
      String runId,
      String taskIdShort,
      CorrectionWorkspaceTransaction.Checkpoint workspaceCheckpoint) {
    String generation = workspaceCheckpoint == null ? null : workspaceCheckpoint.generation();
    Path workspaceRoot = workspaceCheckpoint == null ? null : workspaceCheckpoint.workspaceRoot();
    if (runId == null || generation == null || !generation.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("WORKSPACE_CHECKPOINT_IDENTITY_INVALID");
    }
    if (!enabled || !gcsStore.isConfigured()) {
      if (environment.acceptsProfiles(Profiles.of("prod"))) {
        throw new IllegalStateException("WORKSPACE_GENERATION_DURABLE_CHECKPOINT_UNAVAILABLE");
      }
      String host = localHostIdentity();
      String key = "local-same-host://" + host + "/" + workspaceRoot.toAbsolutePath().normalize();
      return new CheckpointReceipt("LOCAL_SAME_HOST", key, generation, generation, Instant.now());
    }

    Path zipPath = null;
    try {
      zipPath = generationArchives.create(workspaceCheckpoint);
      String artifactDigest;
      try (var input = Files.newInputStream(zipPath)) {
        artifactDigest = org.apache.commons.codec.digest.DigestUtils.sha256Hex(input);
      }
      String key =
          gcsStore.uploadArtifact(
              "workspace-generations/"
                  + runId
                  + "/"
                  + generation
                  + "-"
                  + artifactDigest
                  + ".tar.gz",
              zipPath,
              "application/gzip",
              java.util.Map.of(
                  "runId", runId,
                  "taskId", taskIdShort == null ? "unknown" : taskIdShort,
                  "workspaceGeneration", generation,
                  "artifactDigest", artifactDigest));
      if (key == null || key.isBlank()) {
        throw new IllegalStateException("WORKSPACE_CHECKPOINT_UPLOAD_UNACKNOWLEDGED");
      }
      Instant completed = Instant.now();
      log.info(
          "[GcsTaskCheckpoint] strict run={} task={} generation={} key={}",
          runId,
          taskIdShort,
          generation,
          key);
      return new CheckpointReceipt("GCS", key, generation, artifactDigest, completed);
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

  private static String localHostIdentity() {
    try {
      return java.net.InetAddress.getLocalHost().getHostName().replaceAll("[^A-Za-z0-9._-]", "_");
    } catch (Exception unavailable) {
      return "unknown-host";
    }
  }
}

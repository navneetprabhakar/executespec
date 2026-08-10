package com.navneet.executespec.deployment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import com.navneet.executespec.integration.CorrectionWorkspaceTransaction;
import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.workspacegeneration.WorkspaceGenerationArchiveService;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * RL-2026-05-041 (audit risk #2 — mid-run durability). Checkpoints a run's workspace to GCS at task
 * boundaries so mid-run work survives a node loss (Cloud Run scale-down, MIG auto-heal, container
 * rebuild). Before this, GCS upload fired only on a terminal event ({@code
 * WorkspaceGcsUploadListener}); a node dying mid-run meant the local workspace vanished and the run
 * could only fail {@code WORKSPACE_GONE} — losing work the customer had already paid for at the
 * plan gate.
 *
 * <p>Design (reuses existing infra, no schema change):
 *
 * <ul>
 *   <li>After each terminal task status, async-export the workspace and upload it to GCS under the
 *       run's key (latest-checkpoint-wins — overwrites the same object).
 *   <li>Set {@link PipelineRun#getGcsWorkspaceKey()} (but NOT {@code gcsArchivedAt} — the local
 *       copy still exists). {@code WorkspaceRestoreService.ensureWorkspaceAvailable} then restores
 *       from this key when a resume finds the local workspace gone (see {@code
 *       RunOrchestrator.resumeAfterCrash}).
 *   <li>No-op when GCS is unconfigured (local dev) — guarded by {@link
 *       GcsWorkspaceStore#isConfigured()} so we never pay the zip cost for nothing. Gated by {@code
 *       durability.task-checkpoint.enabled}.
 * </ul>
 *
 * <p>Async + fully self-swallowing: a checkpoint failure must never disrupt the pipeline.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GcsTaskCheckpointService {

  /** Acknowledgement consumed by the workspace-generation acceptance transaction. */
  public record CheckpointReceipt(
      String kind, String key, String generation, String artifactDigest, Instant completedAt) {}

  private final WorkspaceExportService exportService;
  private final WorkspaceGenerationArchiveService generationArchives;
  private final GcsWorkspaceStore gcsStore;
  private final StateStore stateStore;
  private final Environment environment;

  /**
   * Per-run checkpoint chain. Checkpoint uploads overwrite one GCS object, so concurrent
   * fire-and-forget exports can otherwise complete out of order and leave an older workspace as the
   * latest restore candidate. The chain is deliberately local-process only; the database/GCS lease
   * still fences cross-JVM recovery, while this prevents the common same-JVM async reorder.
   */
  private final ConcurrentHashMap<String, CompletableFuture<Void>> checkpointChains =
      new ConcurrentHashMap<>();

  @Value("${durability.task-checkpoint.enabled:true}")
  private boolean enabled;

  /**
   * Checkpoint the workspace after a task boundary. Fire-and-forget; safe to call on every terminal
   * task status. No-op when disabled or GCS is unconfigured.
   */
  @Async
  public void checkpointAfterTask(String runId, String taskIdShort) {
    if (!enabled || runId == null) return;
    if (!gcsStore.isConfigured()) return; // local dev / GCS off — nothing to do, skip the zip

    enqueueCheckpoint(runId, taskIdShort);
  }

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

  private void enqueueCheckpoint(String runId, String taskIdShort) {
    for (; ; ) {
      CompletableFuture<Void> previous = checkpointChains.get(runId);
      CompletableFuture<Void> next =
          (previous == null ? CompletableFuture.completedFuture(null) : previous)
              .handle((ignored, failure) -> null)
              .thenRun(() -> performCheckpoint(runId, taskIdShort));
      boolean installed =
          previous == null
              ? checkpointChains.putIfAbsent(runId, next) == null
              : checkpointChains.replace(runId, previous, next);
      if (installed) {
        next.whenComplete((ignored, failure) -> checkpointChains.remove(runId, next));
        return;
      }
    }
  }

  private void performCheckpoint(String runId, String taskIdShort) {
    Path zipPath = null;
    try {
      zipPath = exportService.exportWorkspace(runId);
      String key = gcsStore.uploadZip(runId, zipPath);
      if (key == null) return; // upload skipped (race with config) — nothing persisted
      PipelineRun run = stateStore.findRun(runId).orElse(null);
      if (run != null) {
        // Latest checkpoint key; leave gcsArchivedAt null — the local copy is still authoritative.
        run.setGcsWorkspaceKey(key);
        stateStore.save(run);
      }
      log.info(
          "[GcsTaskCheckpoint] run={} task={} workspace checkpointed to GCS {}",
          runId,
          taskIdShort,
          key);
    } catch (Exception e) {
      log.warn(
          "[GcsTaskCheckpoint] run={} task={} checkpoint failed (non-fatal): {}",
          runId,
          taskIdShort,
          e.getMessage());
    } finally {
      if (zipPath != null) {
        try {
          Files.deleteIfExists(zipPath);
        } catch (Exception ignore) {
          // temp zip cleanup is best-effort
        }
      }
    }
  }
}

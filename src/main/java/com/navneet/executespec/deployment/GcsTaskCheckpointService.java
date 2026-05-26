package com.navneet.executespec.deployment;

import java.nio.file.Files;
import java.nio.file.Path;

import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.state.entity.PipelineRun;

import org.springframework.beans.factory.annotation.Value;
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

  private final WorkspaceExportService exportService;
  private final GcsWorkspaceStore gcsStore;
  private final StateStore stateStore;

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

package com.navneet.executespec.deployment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import com.navneet.executespec.config.AgentProperties;
import com.navneet.executespec.harness.event.PipelineEvent;
import com.navneet.executespec.saga.SideEffectOutbox;
import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.state.entity.PipelineRun;

import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

/**
 * Immediately uploads a run's workspace to GCS when the run reaches a terminal state.
 *
 * <p>This ensures workspace code survives VM restarts (MIG auto-healing) without waiting for the
 * hourly {@link WorkspaceArchiveJob}. The local copy is preserved — only {@code gcsWorkspaceKey} is
 * set (not {@code gcsArchivedAt}), so the archive job knows it only needs to delete the local copy
 * later.
 *
 * <p>A 5-minute fallback sweep catches any runs missed by the event listener (e.g. PARTIAL runs,
 * event processing failures).
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class WorkspaceGcsUploadListener {

  private final AgentProperties agentProps;
  private final DeploymentProperties deploymentProps;
  private final WorkspaceExportService exportService;
  private final GcsWorkspaceStore gcsStore;
  private final StateStore stateStore;
  private final SideEffectOutbox sideEffectOutbox;

  // Event-driven upload (covers COMPLETE + FAILED + PARTIAL)

  @Async
  @EventListener
  public void onRunCompleted(PipelineEvent.RunCompleted event) {
    uploadWorkspaceToGcs(event.runId());
  }

  @Async
  @EventListener
  public void onRunFailed(PipelineEvent.RunFailed event) {
    uploadWorkspaceToGcs(event.runId());
  }

  // Fallback sweep (catches PARTIAL runs + any missed events)

  /** Every 5 minutes, find terminal runs without a GCS key and upload them. */
  @Scheduled(fixedRate = 300_000)
  @SchedulerLock(name = "GcsUploadFallback", lockAtMostFor = "4m", lockAtLeastFor = "1m")
  public void uploadMissingWorkspaces() {
    if (!deploymentProps.workspace().gcsEnabled()) return;
    String bucket = deploymentProps.workspace().gcsBucket();
    if (bucket == null || bucket.isBlank()) return;

    Instant cutoff = Instant.now().minus(5, ChronoUnit.MINUTES);
    List<PipelineRun> candidates = stateStore.findTerminalRunsWithoutGcsKey(cutoff);

    for (PipelineRun run : candidates) {
      uploadWorkspaceToGcs(run.getRunId());
    }
  }

  private void uploadWorkspaceToGcs(String runId) {
    if (!deploymentProps.workspace().gcsEnabled()) return;
    String bucket = deploymentProps.workspace().gcsBucket();
    if (bucket == null || bucket.isBlank()) return;

    // Idempotent: skip if already uploaded
    PipelineRun run = stateStore.findRun(runId).orElse(null);
    if (run == null || run.getGcsWorkspaceKey() != null) return;

    Path workspace = Path.of(agentProps.getWorkspace(), runId);
    if (!Files.isDirectory(workspace)) {
      log.debug("[GcsUpload] Local workspace absent for run {} — nothing to upload", runId);
      return;
    }

    try {
      Path zipPath = exportService.exportWorkspace(runId);
      try {
        String gcsKey = gcsStore.uploadZip(runId, zipPath);
        // Set gcsWorkspaceKey but NOT gcsArchivedAt — local copy stays
        run.setGcsWorkspaceKey(gcsKey);
        stateStore.saveRun(run);
        log.info("[GcsUpload] Uploaded workspace for run {} → gs://{}/{}", runId, bucket, gcsKey);
      } finally {
        Files.deleteIfExists(zipPath);
      }
    } catch (Exception e) {
      log.warn(
          "[GcsUpload] Failed to upload workspace for run {}: {} — enqueuing for retry via outbox",
          runId,
          e.getMessage());
      // Enqueue for reliable retry via SideEffectOutbox (max 3 attempts, exp. backoff)
      sideEffectOutbox.enqueue(
          runId,
          "GCS_ARCHIVE",
          Map.of(
              "runId", runId,
              "bucket",
                  deploymentProps.workspace().gcsBucket() != null
                      ? deploymentProps.workspace().gcsBucket()
                      : "",
              "error", e.getMessage() != null ? e.getMessage() : "unknown"));
    }
  }
}

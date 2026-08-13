package com.navneet.executespec.workspacegeneration;

import java.time.Duration;

import com.navneet.executespec.deployment.GcsWorkspaceStore;
import com.navneet.executespec.deployment.LocalGenerationCheckpointStore;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** Deletes terminal orphan archives only after a fresh durable-reference check. */
@Service
@RequiredArgsConstructor
@Slf4j
public class WorkspaceGenerationArchiveJanitor {

  private final WorkspaceGenerationArchiveCleanupStore cleanup;
  private final GcsWorkspaceStore gcs;
  private final LocalGenerationCheckpointStore local;

  @EventListener(ApplicationReadyEvent.class)
  public void startup() {
    sweep(20);
  }

  @Scheduled(
      fixedDelayString = "${execution.workspace-generation.archive-cleanup-scan-ms:300000}",
      initialDelayString =
          "${execution.workspace-generation.archive-cleanup-initial-delay-ms:120000}")
  public void scheduled() {
    sweep(20);
  }

  int sweep(int limit) {
    cleanup.enqueueEligible();
    int completed = 0;
    for (int i = 0; i < limit; i++) {
      var claim = cleanup.claimNext(Duration.ofMinutes(15));
      if (claim.isEmpty()) break;
      var row = claim.get();
      try {
        if (!cleanup.stillDeletable(row)) {
          cleanup.protectedReference(row);
          continue;
        }
        if ("GCS".equals(row.kind())) {
          gcs.deleteArtifactExact(row.key(), row.storageGeneration());
        } else if ("LOCAL_ARCHIVE".equals(row.kind())) {
          local.deleteExact(
              row.key(),
              row.runId(),
              row.artifactDigest(),
              row.storageGeneration(),
              row.sizeBytes(),
              row.crc32c(),
              row.etag());
        } else {
          throw new IllegalStateException("WORKSPACE_GENERATION_ARCHIVE_KIND_INVALID");
        }
        cleanup.complete(row);
        completed++;
      } catch (Exception failure) {
        cleanup.fail(row, failure.getMessage());
        log.warn(
            "Workspace generation archive cleanup deferred attempt={} step={} errorClass={}",
            row.attemptId(),
            row.stepId(),
            failure.getClass().getSimpleName());
      }
    }
    return completed;
  }
}

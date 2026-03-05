package com.navneet.agentteam.deployment;

import com.navneet.agentteam.config.AgentProperties;
import com.navneet.agentteam.state.StateStore;
import com.navneet.agentteam.state.entity.PipelineRun;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;

/**
 * Hourly job that archives completed run workspaces to GCS and deletes local copies.
 *
 * <p><b>Strategy:</b>
 * <ol>
 *   <li>Find runs in a terminal state whose {@code completedAt} is older than
 *       {@code archiveAfterHours} (default 24 h) and that have not yet been archived.</li>
 *   <li>For each run: build a zip (reuses {@link WorkspaceExportService}), upload it to
 *       GCS, persist the GCS key + timestamp in the DB, then delete the local workspace
 *       directory.</li>
 * </ol>
 *
 * <p>Runs within the first 24 hours are never touched — users can browse and download
 * their code immediately after completion without any latency penalty.
 *
 * <p>After archiving, the download endpoint issues a signed-URL redirect so the client
 * downloads directly from GCS (zero server bandwidth in the data path).
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class WorkspaceArchiveJob {

    private final DeploymentProperties   props;
    private final AgentProperties        agentProps;
    private final StateStore             stateStore;
    private final WorkspaceExportService exportService;
    private final GcsWorkspaceStore      gcsStore;

    /** Runs every hour at the top of the hour. ShedLock prevents duplicate execution across MIG instances. */
    @Scheduled(cron = "0 0 * * * *")
    @SchedulerLock(name = "WorkspaceArchiveJob", lockAtMostFor = "50m", lockAtLeastFor = "5m")
    public void archiveOldWorkspaces() {
        String bucket = props.workspace().gcsBucket();
        if (bucket == null || bucket.isBlank()) {
            log.debug("[WorkspaceArchiveJob] GCS bucket not configured — skipping archival");
            return;
        }

        int archiveAfterHours = props.workspace().archiveAfterHours();
        Instant cutoff = Instant.now().minus(archiveAfterHours, ChronoUnit.HOURS);

        List<PipelineRun> candidates = stateStore.findRunsToArchive(cutoff);
        if (candidates.isEmpty()) {
            log.debug("[WorkspaceArchiveJob] No workspaces to archive");
            return;
        }

        log.info("[WorkspaceArchiveJob] Archiving {} workspace(s) to GCS (completed before {})",
            candidates.size(), cutoff);

        int archived = 0;
        int failed   = 0;

        for (PipelineRun run : candidates) {
            try {
                archiveRun(run.getRunId());
                archived++;
            } catch (Exception e) {
                failed++;
                log.error("[WorkspaceArchiveJob] Failed to archive run {}: {}",
                    run.getRunId(), e.getMessage(), e);
                // Continue — one failure must not block remaining runs
            }
        }

        log.info("[WorkspaceArchiveJob] Done — archived={}, failed={}", archived, failed);
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private void archiveRun(String runId) throws IOException {
        log.info("[WorkspaceArchiveJob] Archiving run {}", runId);

        // 1. Build zip from local workspace (reuses existing zip logic in exportService)
        Path zipPath = exportService.exportWorkspace(runId);
        try {
            // 2. Upload zip to GCS
            String gcsKey = gcsStore.uploadZip(runId, zipPath);

            // 3. Persist GCS key + archived timestamp in DB
            stateStore.markGcsArchived(runId, gcsKey);

            // 4. Delete local workspace directory (only after successful GCS upload)
            deleteLocalWorkspace(runId);

            log.info("[WorkspaceArchiveJob] Archived run {} → gs://{}/{}",
                runId, props.workspace().gcsBucket(), gcsKey);

        } finally {
            // Clean up temp zip regardless of success/failure
            try { Files.deleteIfExists(zipPath); }
            catch (IOException ex) {
                log.warn("[WorkspaceArchiveJob] Could not delete temp zip {}: {}", zipPath, ex.getMessage());
            }
        }
    }

    /**
     * Recursively deletes the local workspace directory for a run.
     * Uses depth-first deletion (deepest files first) so parent directories are empty
     * by the time we try to remove them.
     */
    private void deleteLocalWorkspace(String runId) {
        Path workspace = Path.of(agentProps.getWorkspace(), runId).toAbsolutePath().normalize();
        if (!Files.exists(workspace)) {
            log.debug("[WorkspaceArchiveJob] Local workspace already absent for {}", runId);
            return;
        }
        try (var stream = Files.walk(workspace)) {
            stream.sorted(Comparator.reverseOrder())
                  .map(Path::toFile)
                  .forEach(f -> {
                      if (!f.delete()) {
                          log.warn("[WorkspaceArchiveJob] Could not delete: {}", f);
                      }
                  });
            log.info("[WorkspaceArchiveJob] Deleted local workspace directory for run {}", runId);
        } catch (IOException e) {
            log.warn("[WorkspaceArchiveJob] Partial deletion for run {}: {}", runId, e.getMessage());
        }
    }
}

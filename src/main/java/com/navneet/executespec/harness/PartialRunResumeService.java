package com.navneet.executespec.harness;

import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.state.entity.AgentTask;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Periodically scans for PARTIAL runs (pipeline completed but some tasks failed/stuck)
 * and automatically retries up to MAX_RETRIES_PER_SWEEP failed tasks per run.
 *
 * <p>PARTIAL runs are "nearly done" — 14/15 or 23/24 tasks succeeded. Without this
 * service, they require manual UI intervention via the retry button.
 *
 * <p>Only picks up runs that have been PARTIAL for at least PARTIAL_AGE_MINUTES —
 * avoids interfering with runs that just transitioned to PARTIAL.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PartialRunResumeService {

    private static final int PARTIAL_AGE_MINUTES = 10;
    private static final int MAX_RETRIES_PER_SWEEP = 3;

    private final PipelineRunRepository runRepo;
    private final StateStore stateStore;
    private final PipelineHarness harness;

    /**
     * Every 5 minutes: find stale PARTIAL runs and retry their failed/stuck tasks.
     */
    @Scheduled(fixedDelay = 5 * 60 * 1000)
    public void resumePartialRuns() {
        List<PipelineRun> partialRuns = runRepo.findByStatusIn(List.of("PARTIAL"));
        if (partialRuns.isEmpty()) return;

        Instant cutoff = Instant.now().minusSeconds(PARTIAL_AGE_MINUTES * 60L);

        for (PipelineRun run : partialRuns) {
            try {
                resumeIfEligible(run, cutoff);
            } catch (Exception e) {
                log.warn("[PartialResume] Error processing run {}: {}", run.getRunId(), e.getMessage());
            }
        }
    }

    private void resumeIfEligible(PipelineRun run, Instant cutoff) {
        String runId = run.getRunId();

        // Skip if the pipeline thread is still active for this run
        if (harness.isRunActive(runId)) {
            log.debug("[PartialResume] Run {} is still active — skipping", runId);
            return;
        }

        // Only process runs that have been PARTIAL for at least PARTIAL_AGE_MINUTES
        Instant completedAt = run.getCompletedAt();
        if (completedAt == null || completedAt.isAfter(cutoff)) {
            log.debug("[PartialResume] Run {} completed too recently — skipping", runId);
            return;
        }

        // Find failed or stuck tasks for this run
        List<AgentTask> failedTasks = stateStore.getAllTasks(runId).stream()
                .filter(t -> "FAILED".equals(t.getStatus()) || "STUCK".equals(t.getStatus()))
                .limit(MAX_RETRIES_PER_SWEEP)
                .toList();

        if (failedTasks.isEmpty()) {
            log.debug("[PartialResume] Run {} has no failed/stuck tasks — skipping", runId);
            return;
        }

        log.info("[PartialResume] Auto-retrying {} task(s) for PARTIAL run {}", failedTasks.size(), runId);
        for (AgentTask task : failedTasks) {
            try {
                boolean started = harness.retryPartialTask(runId, task.getTaskId());
                if (started) {
                    log.info("[PartialResume] Queued retry for task {} in run {}", task.getTaskId(), runId);
                } else {
                    log.warn("[PartialResume] retryPartialTask returned false for task {} in run {}", task.getTaskId(), runId);
                }
            } catch (Exception e) {
                log.warn("[PartialResume] Failed to retry task {} in run {}: {}", task.getTaskId(), runId, e.getMessage());
            }
        }
    }
}

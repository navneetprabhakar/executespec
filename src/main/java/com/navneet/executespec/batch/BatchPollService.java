package com.navneet.executespec.batch;

import com.navneet.executespec.config.AgentProperties;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.sse.RunEvent;
import com.navneet.executespec.sse.SseBroadcaster;
import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.state.entity.PipelineRun;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Polls batch API providers for in-progress batch runs.
 * Scheduled at a fixed rate (default 30s) with ShedLock distributed locking.
 * When a batch completes, delegates to {@link BatchResumeService} to continue the pipeline.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BatchPollService {

    private final StateStore stateStore;
    private final BatchClientRouter batchClientRouter;
    private final BatchResumeService batchResumeService;
    private final SseBroadcaster broadcaster;
    private final AgentProperties agentProperties;

    @Scheduled(fixedDelayString = "${agent.cost.batch.poll-interval-seconds:30}000")
    @SchedulerLock(name = "BatchPollService", lockAtMostFor = "60s", lockAtLeastFor = "10s")
    public void pollBatchRuns() {
        if (!agentProperties.getCost().getBatch().isEnabled()) return;

        // C2 fix: include BATCH_COMPLETE to recover orphaned runs where resumeFromBatch() failed
        // after status was already set to BATCH_COMPLETE (poll won't re-fetch results for these —
        // it will re-attempt the resume path)
        List<PipelineRun> batchRuns = stateStore.findRunsByStatusIn(
                List.of("IN_PROGRESS_BATCH", "QUEUED_BATCH", "BATCH_COMPLETE"));

        if (batchRuns.isEmpty()) return;

        log.debug("Polling {} batch runs", batchRuns.size());

        for (PipelineRun run : batchRuns) {
            try {
                pollSingleRun(run);
            } catch (Exception e) {
                log.error("[Run {}] Batch poll error: {}", run.getRunId(), e.getMessage());
            }
        }
    }

    private void pollSingleRun(PipelineRun run) {
        String runId = run.getRunId();
        String batchId = run.getBatchId();
        String providerName = run.getBatchProvider();

        if (batchId == null || providerName == null) {
            log.warn("[Run {}] Batch run missing batchId or provider — skipping", runId);
            return;
        }

        // C2 fix: recovery path for runs stuck in BATCH_COMPLETE
        // (results were fetched but resumeFromBatch() failed on the previous attempt)
        if ("BATCH_COMPLETE".equals(run.getStatus())) {
            log.info("[Run {}] Recovering orphaned BATCH_COMPLETE run — re-fetching results and resuming", runId);
            try {
                LlmProvider provider = LlmProvider.valueOf(providerName);
                BatchResults results = batchClientRouter.getResults(batchId, runId, provider);
                batchResumeService.resumeFromBatch(run, results);
            } catch (Exception e) {
                log.error("[Run {}] BATCH_COMPLETE recovery failed: {}", runId, e.getMessage());
            }
            return;
        }

        // Check for timeout
        int maxWaitHours = agentProperties.getCost().getBatch().getMaxWaitHours();
        if (run.getBatchSubmittedAt() != null &&
                Duration.between(run.getBatchSubmittedAt(), Instant.now()).toHours() >= maxWaitHours) {
            log.warn("[Run {}] Batch timed out after {}h — canceling and failing", runId, maxWaitHours);
            LlmProvider provider = LlmProvider.valueOf(providerName);
            batchClientRouter.cancelBatch(batchId, runId, provider);
            stateStore.markFailed(runId, "Batch timed out after " + maxWaitHours + " hours");
            broadcaster.emit(runId, RunEvent.batchFailed(runId, "Batch timed out"));
            broadcaster.emit(runId, RunEvent.complete(runId, "FAILED"));
            return;
        }

        LlmProvider provider = LlmProvider.valueOf(providerName);
        BatchStatus status = batchClientRouter.checkStatus(batchId, runId, provider);

        // Update progress
        if (status.totalRequests() > 0) {
            stateStore.updateBatchProgress(runId, status.completedRequests(), status.totalRequests());
            broadcaster.emit(runId, RunEvent.batchProgress(runId,
                    status.completedRequests(), status.totalRequests()));
        }

        switch (status.state()) {
            case COMPLETED -> {
                log.info("[Run {}] Batch complete — retrieving results", runId);
                try {
                    BatchResults results = batchClientRouter.getResults(batchId, runId, provider);
                    broadcaster.emit(runId, RunEvent.batchComplete(runId));
                    stateStore.updateStatus(runId, "BATCH_COMPLETE");
                    batchResumeService.resumeFromBatch(run, results);
                } catch (Exception e) {
                    // OpenAI may report completed before output_file_id is ready — retry next poll
                    log.warn("[Run {}] Batch results not ready yet ({}), will retry next poll",
                            runId, e.getMessage());
                }
            }
            case FAILED -> {
                String error = status.error() != null ? status.error() : "Batch processing failed";
                log.error("[Run {}] Batch failed: {}", runId, error);
                stateStore.markFailed(runId, "Batch failed: " + error);
                broadcaster.emit(runId, RunEvent.batchFailed(runId, error));
                broadcaster.emit(runId, RunEvent.complete(runId, "FAILED"));
            }
            case EXPIRED -> {
                log.warn("[Run {}] Batch expired", runId);
                stateStore.markFailed(runId, "Batch expired before completion");
                broadcaster.emit(runId, RunEvent.batchFailed(runId, "Batch expired"));
                broadcaster.emit(runId, RunEvent.complete(runId, "FAILED"));
            }
            case IN_PROGRESS, PENDING, CANCELING -> {
                log.debug("[Run {}] Batch still processing: {} ({}/{})",
                        runId, status.state(), status.completedRequests(), status.totalRequests());
            }
        }
    }
}

package com.navneet.executespec.recovery;

import com.navneet.executespec.agent.model.WorkerReport;
import com.navneet.executespec.harness.WorkerPool;
import com.navneet.executespec.harness.event.PipelineEvent;
import com.navneet.executespec.sse.RunEvent;
import com.navneet.executespec.sse.SseBroadcaster;
import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.state.entity.AgentTask;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.entity.RunRecovery;
import com.navneet.executespec.state.repository.RunRecoveryRepository;
import com.navneet.executespec.tool.FileLockTool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class RunRecoveryService {

    private final StateStore stateStore;
    private final WorkerPool workerPool;
    private final FileLockTool fileLockTool;
    private final RunRecoveryRepository recoveryRepository;
    private final SseBroadcaster sseBroadcaster;
    private final RecoveryProperties props;
    private final ApplicationEventPublisher eventPublisher;

    /** Keywords in a worker's {@code blockers} field that indicate a transient failure. */
    private static final List<String> TRANSIENT_KEYWORDS = List.of(
        "rate", "429", "provider", "overloaded", "server error", "timeout",
        "503", "502", "all providers", "rate limit", "service unavailable",
        "temporarily unavailable", "too many requests", "unavailable"
    );

    /**
     * Examines worker reports from a failed run and, if the failure looks transient
     * (rate-limits, provider outage, etc.), schedules an automatic restart after a
     * configurable backoff period.
     *
     * <p>Guards:
     * <ul>
     *   <li>Recovery must be enabled in config.</li>
     *   <li>{@code run.autoRestartCount} must be below {@code maxAutoRestarts}.</li>
     *   <li>At least one report must have a transient keyword in its {@code blockers}.</li>
     * </ul>
     *
     * @param run     the failed run
     * @param reports the worker reports collected during the run
     * @return {@code true} if a restart was scheduled
     */
    public boolean autoRestartIfTransient(PipelineRun run, List<WorkerReport> reports) {
        if (!props.isEnabled()) return false;

        int restartNum = run.getAutoRestartCount() + 1;
        if (run.getAutoRestartCount() >= props.getMaxAutoRestarts()) {
            log.warn("Run {} has reached max auto-restarts ({}) — no further retries",
                run.getRunId(), props.getMaxAutoRestarts());
            sseBroadcaster.emit(run.getRunId(), RunEvent.log(
                run.getRunId(), null,
                "❌ Max auto-restarts (" + props.getMaxAutoRestarts() +
                ") reached — manual intervention required."
            ));
            return false;
        }

        boolean isTransient = reports.stream()
            .filter(r -> r.getBlockers() != null)
            .anyMatch(r -> isTransientMessage(r.getBlockers()));

        if (!isTransient) {
            log.debug("Run {} failure does not appear transient — skipping auto-restart", run.getRunId());
            return false;
        }

        long backoffMs = props.getAutoRestartBackoffSeconds() * 1000L;
        log.info("Transient failure detected for run {} — scheduling auto-restart #{} in {}s",
            run.getRunId(), restartNum, props.getAutoRestartBackoffSeconds());

        sseBroadcaster.emit(run.getRunId(), RunEvent.log(
            run.getRunId(), null,
            "⏳ Transient provider failure detected (rate-limit / unavailable). " +
            "Auto-restarting in " + props.getAutoRestartBackoffSeconds() + "s " +
            "(attempt " + restartNum + " of " + props.getMaxAutoRestarts() + ")"
        ));

        final PipelineRun runRef = run;
        Thread.ofVirtual()
            .name("auto-restart-" + run.getRunId().substring(0, 8))
            .start(() -> {
                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    log.warn("Auto-restart sleep interrupted for run {}", runRef.getRunId());
                    return;
                }

                // Publish RestartRun event — PipelineHarness handles it and submits a new run
                eventPublisher.publishEvent(new PipelineEvent.RestartRun(
                    runRef.getRunId(),
                    runRef.getUserRequest(),
                    runRef.getSourceType() != null ? runRef.getSourceType() : "RECOVERY",
                    runRef.getRepoUrl(),
                    runRef.getRepoMode(),
                    runRef.getAutoRestartCount() + 1
                ));
                // Use a placeholder runId in the recovery record (harness will log the actual new runId)
                String newRunId = "restart-pending-" + runRef.getRunId().substring(0, 8);

                RunRecovery recovery = RunRecovery.builder()
                    .id(UUID.randomUUID().toString())
                    .originalRunId(runRef.getRunId())
                    .restartRunId(newRunId)
                    .stuckTaskId(null)
                    .classification(StuckClassification.LLM_TIMEOUT) // closest semantic match
                    .classificationDetail("Transient provider failure — auto-restart #" + restartNum)
                    .actionTaken("AUTO_RESTART_TRANSIENT")
                    .originalGoal(runRef.getUserRequest())
                    .recoveredAt(Instant.now())
                    .restartCreatedAt(Instant.now())
                    .build();
                recoveryRepository.save(recovery);

                sseBroadcaster.emit(runRef.getRunId(),
                    RunEvent.runRestarted(runRef.getRunId(), newRunId));
                log.info("Auto-restarted run {} → new run {} (restart #{})",
                    runRef.getRunId(), newRunId, restartNum);
            });

        return true;
    }

    private boolean isTransientMessage(String message) {
        if (message == null) return false;
        String lower = message.toLowerCase();
        return TRANSIENT_KEYWORDS.stream().anyMatch(lower::contains);
    }

    public void recover(AgentTask stuckTask, StuckRunEvaluator.EvaluationResult evaluation) {
        PipelineRun run = stateStore.findRun(stuckTask.getRunId()).orElse(null);
        if (run == null) {
            log.warn("Cannot recover task {} — run {} not found",
                stuckTask.getTaskId(), stuckTask.getRunId());
            return;
        }

        log.warn("Recovery triggered — runId={} taskId={} classification={}",
            run.getRunId(), stuckTask.getTaskId(), evaluation.classification());

        sseBroadcaster.emit(run.getRunId(), RunEvent.log(
            run.getRunId(), stuckTask.getTaskId(),
            "⚠️ Stuck detected — task [" + stuckTask.getTaskId() +
            "] classification: " + evaluation.classification() +
            " | " + evaluation.detail()
        ));

        switch (evaluation.classification()) {
            case LLM_TIMEOUT, FILE_LOCK_BLOCK -> {
                if ((stuckTask.getRecoveryAttempt() != null ? stuckTask.getRecoveryAttempt() : 0) < props.getMaxTaskRetries()) {
                    retryTask(run, stuckTask, evaluation);
                } else {
                    log.warn("Task {} retry limit ({}) reached — escalating to fresh restart",
                        stuckTask.getTaskId(), props.getMaxTaskRetries());
                    freshRestart(run, stuckTask, evaluation,
                        "Task retry limit reached after " + evaluation.classification());
                }
            }
            case LLM_LOOP, BUILD_FAILURE_STALL, UNKNOWN ->
                freshRestart(run, stuckTask, evaluation, null);
        }
    }

    private void retryTask(PipelineRun run, AgentTask task,
                           StuckRunEvaluator.EvaluationResult evaluation) {
        log.info("Retrying task {} in run {}", task.getTaskId(), run.getRunId());

        workerPool.cancelTask(task.getTaskId());

        int locksReleased = fileLockTool.releaseAllLocksForTask(task.getTaskId());
        log.info("Released {} file locks held by task {}", locksReleased, task.getTaskId());

        stateStore.markTaskForRetry(task.getTaskId());

        RunRecovery recovery = buildRecovery(run, task, evaluation, "TASK_RETRY", null);
        recoveryRepository.save(recovery);

        sseBroadcaster.emit(run.getRunId(), RunEvent.recoveryAttempted(
            run.getRunId(),
            task.getTaskId(),
            evaluation.classification().name(),
            "TASK_RETRY",
            locksReleased
        ));

        workerPool.resubmitTask(stateStore.getTask(task.getTaskId()), run.getRunId());

        log.info("Task {} resubmitted — recovery attempt {}",
            task.getTaskId(), (task.getRecoveryAttempt() != null ? task.getRecoveryAttempt() : 0) + 1);
    }

    private void freshRestart(PipelineRun run, AgentTask triggeringTask,
                              StuckRunEvaluator.EvaluationResult evaluation,
                              String escalationReason) {
        log.warn("Fresh restart initiated — runId={} reason={}", run.getRunId(),
            evaluation.classification());

        workerPool.cancelAllTasksForRun(run.getRunId());

        int locksReleased = fileLockTool.releaseAllLocksForRun(run.getRunId());
        log.info("Released {} file locks for run {}", locksReleased, run.getRunId());

        stateStore.markFailed(run.getRunId(), "Auto-recovery: " + evaluation.classification()
            + (escalationReason != null ? " — " + escalationReason : ""));

        cleanWorkspace(run);

        RunRecovery recovery = buildRecovery(run, triggeringTask, evaluation, "FRESH_RESTART", null);
        recoveryRepository.save(recovery);

        sseBroadcaster.emit(run.getRunId(), RunEvent.log(
            run.getRunId(), null,
            "🔄 Auto-restarting run — cause: " + evaluation.classification() +
            " | " + evaluation.detail()
        ));

        // Publish RestartRun event — PipelineHarness handles it and submits a new run
        eventPublisher.publishEvent(new PipelineEvent.RestartRun(
            run.getRunId(),
            run.getUserRequest(),
            run.getSourceType() != null ? run.getSourceType() : "RECOVERY",
            run.getRepoUrl(),
            run.getRepoMode(),
            0
        ));
        String newRunId = "restart-pending-" + run.getRunId().substring(0, 8);
        log.info("RestartRun event published — originalRunId={}", run.getRunId());

        recovery.setRestartRunId(newRunId);
        recovery.setRestartCreatedAt(Instant.now());
        recoveryRepository.save(recovery);

        sseBroadcaster.emit(run.getRunId(), RunEvent.runRestarted(run.getRunId(), newRunId));
    }

    private void cleanWorkspace(PipelineRun run) {
        Path workspacePath = Path.of(
            System.getProperty("agent.workspace", "./workspace"),
            run.getRunId()
        );
        try {
            if (Files.exists(workspacePath)) {
                deleteDirectory(workspacePath);
                log.info("Workspace cleaned: {}", workspacePath);
            }
        } catch (IOException e) {
            log.error("Failed to clean workspace for run {}: {}", run.getRunId(), e.getMessage());
        }
    }

    private void deleteDirectory(Path path) throws IOException {
        Files.walk(path)
            .sorted(java.util.Comparator.reverseOrder())
            .map(Path::toFile)
            .forEach(java.io.File::delete);
    }

    private RunRecovery buildRecovery(PipelineRun run, AgentTask task,
                                      StuckRunEvaluator.EvaluationResult evaluation,
                                      String actionTaken, String restartRunId) {
        return RunRecovery.builder()
            .id(UUID.randomUUID().toString())
            .originalRunId(run.getRunId())
            .restartRunId(restartRunId)
            .stuckTaskId(task != null ? task.getTaskId() : null)
            .classification(evaluation.classification())
            .classificationDetail(evaluation.detail())
            .actionTaken(actionTaken)
            .originalGoal(run.getUserRequest())
            .recoveredAt(Instant.now())
            .build();
    }
}

package com.navneet.executespec.recovery;

import com.navneet.executespec.harness.BackgroundValidationService;
import com.navneet.executespec.harness.PipelineHarness;
import com.navneet.executespec.harness.entity.BackgroundValidation;
import com.navneet.executespec.router.AvailableProviderResolver;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.router.ProviderStatusService;
import com.navneet.executespec.sse.RunEvent;
import com.navneet.executespec.sse.SseBroadcaster;
import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.state.entity.AgentTask;
import com.navneet.executespec.state.entity.PipelineRun;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

import static com.navneet.executespec.config.CustomMetricsConfig.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class StuckRunMonitor {

    private final StateStore stateStore;
    private final PipelineHarness harness;
    private final RunRecoveryService recoveryService;
    private final SseBroadcaster sseBroadcaster;
    private final RecoveryProperties props;
    private final ObservationRegistry observationRegistry;
    private final MeterRegistry meterRegistry;
    private final BackgroundValidationService backgroundValidationService;
    private final ProviderStatusService providerStatusService;
    private final AvailableProviderResolver availableProviderResolver;

    /** Background validations stalled longer than this are marked FAILED. */
    private static final int STALLED_VALIDATION_THRESHOLD_MINUTES = 20;

    @Scheduled(fixedDelay = 2 * 60 * 1000)
    @SchedulerLock(name = "StuckRunMonitor", lockAtMostFor = "90s", lockAtLeastFor = "15s")
    public void checkForStuckTasks() {
        if (!props.isEnabled()) return;

        Observation obs = Observation.createNotStarted("executespec.monitor.stuck_check", observationRegistry)
            .start();

        try {
            List<AgentTask> workingTasks = stateStore.getWorkingTasks();
            obs.highCardinalityKeyValue("tasks.checked", String.valueOf(workingTasks.size()));

            if (workingTasks.isEmpty()) {
                obs.lowCardinalityKeyValue("outcome", "no_working_tasks");
                return;
            }

            Duration warnThreshold = Duration.ofMinutes(props.getWarnThresholdMinutes());
            Duration actThreshold  = Duration.ofMinutes(props.getActThresholdMinutes());
            Instant now = Instant.now();
            int stuckCount = 0;

            for (AgentTask task : workingTasks) {
                if (task.getLastActivityAt() == null) continue;

                Duration idle = Duration.between(task.getLastActivityAt(), now);

                if (idle.compareTo(actThreshold) >= 0 && task.getStuckDetectedAt() == null) {
                    // Skip if pipeline thread is still alive — it may be between
                    // tasks or in post-processing; triggering recovery would spawn
                    // a duplicate execution thread.
                    if (harness.isRunActive(task.getRunId())) {
                        log.debug("Task {} idle but pipeline thread active for run {} — skipping recovery",
                            task.getTaskId(), task.getRunId());
                        continue;
                    }

                    stuckCount++;
                    Counter.builder(STUCK_DETECTED).register(meterRegistry).increment();
                    log.warn("Task {} idle for {}min — triggering recovery",
                        task.getTaskId(), idle.toMinutes());

                    stateStore.markStuckDetected(task.getTaskId());
                    stateStore.markRunStuck(task.getRunId());
                    List<String> events = stateStore.getTaskEventLog(
                        task.getTaskId(), props.getEvaluatorLogWindow()
                    );
                    StuckRunEvaluator.EvaluationResult result = new StuckRunEvaluator(props).evaluate(task, events);

                    recoveryService.recover(task, result);

                } else if (idle.compareTo(warnThreshold) >= 0 &&
                           idle.compareTo(actThreshold) < 0 &&
                           task.getStuckDetectedAt() == null) {
                    sseBroadcaster.emit(task.getRunId(), RunEvent.log(
                        task.getRunId(), task.getTaskId(),
                        "⏱ Task [" + task.getTaskId() + "] has been idle for " +
                        idle.toMinutes() + " minutes"
                    ));
                }
            }

            obs.highCardinalityKeyValue("tasks.stuck", String.valueOf(stuckCount));
            obs.lowCardinalityKeyValue("outcome", stuckCount > 0 ? "recovery_triggered" : "all_healthy");

            // Check for stalled background validations
            checkStalledValidations();

            // Retry runs queued due to provider outage
            checkOutageQueuedRuns();
        } catch (Exception e) {
            obs.error(e);
            throw e;
        } finally {
            obs.stop();
        }
    }

    /**
     * Finds runs that were queued due to a total provider outage and re-submits them
     * once at least one provider recovers. The re-submission goes through the normal
     * slot + provider-pool path — if providers are still down, the run stays QUEUED.
     */
    private void checkOutageQueuedRuns() {
        try {
            List<PipelineRun> outageQueued = stateStore.getOutageQueuedRuns();
            if (outageQueued.isEmpty()) return;

            for (PipelineRun run : outageQueued) {
                LlmProvider primary = run.getAiProviderEnum() != null
                        ? run.getAiProviderEnum() : LlmProvider.OPENAI;
                var available = availableProviderResolver.resolve(run.getRunId());
                if (providerStatusService.isAnyProviderHealthy(primary, available)) {
                    log.info("[StuckRunMonitor] Provider recovered — retrying outage-queued run {}",
                            run.getRunId());
                    providerStatusService.recordOutageQueueReleased();
                    run.setFailureReason(null);
                    stateStore.save(run);
                    try {
                        harness.resumeRun(run.getRunId());
                    } catch (Exception e) {
                        log.warn("[StuckRunMonitor] Could not retry outage-queued run {}: {}",
                                run.getRunId(), e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[StuckRunMonitor] Failed to check outage-queued runs: {}", e.getMessage());
        }
    }

    /**
     * Marks background validations that have been RUNNING for more than
     * {@link #STALLED_VALIDATION_THRESHOLD_MINUTES} minutes as FAILED.
     * The in-memory thread is assumed dead (app restart or OOM); the record is
     * left in FAILED state so {@code /retry-validation} can re-trigger it.
     */
    private void checkStalledValidations() {
        try {
            List<BackgroundValidation> stalled =
                    backgroundValidationService.findStalled(STALLED_VALIDATION_THRESHOLD_MINUTES);
            for (BackgroundValidation bv : stalled) {
                log.warn("[StuckRunMonitor] Background validation for run {} stalled at phase '{}' (started {})" +
                         " — marking FAILED",
                        bv.getRunId(), bv.getCurrentPhase(), bv.getStartedAt());
                backgroundValidationService.markFailed(bv.getRunId(),
                        "Stalled — no progress for " + STALLED_VALIDATION_THRESHOLD_MINUTES + " minutes");
                sseBroadcaster.emit(bv.getRunId(), RunEvent.log(bv.getRunId(), "monitor",
                        "⚠️ Background validation stalled and was marked failed. Use retry-validation to re-run."));
                Counter.builder("executespec.background_validation.stalled")
                        .register(meterRegistry).increment();
            }
        } catch (Exception e) {
            log.warn("[StuckRunMonitor] Failed to check stalled validations: {}", e.getMessage());
        }
    }
}

package com.navneet.executespec.recovery;

import com.navneet.executespec.sse.RunEvent;
import com.navneet.executespec.sse.SseBroadcaster;
import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.state.entity.AgentTask;
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
    private final RunRecoveryService recoveryService;
    private final SseBroadcaster sseBroadcaster;
    private final RecoveryProperties props;
    private final ObservationRegistry observationRegistry;
    private final MeterRegistry meterRegistry;

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
        } catch (Exception e) {
            obs.error(e);
            throw e;
        } finally {
            obs.stop();
        }
    }
}

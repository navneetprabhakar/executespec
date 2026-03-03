package com.navneet.agentteam.recovery;

import com.navneet.agentteam.sse.RunEvent;
import com.navneet.agentteam.sse.SseBroadcaster;
import com.navneet.agentteam.state.StateStore;
import com.navneet.agentteam.state.entity.AgentTask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
    private final StuckRunEvaluator evaluator;
    private final RunRecoveryService recoveryService;
    private final SseBroadcaster sseBroadcaster;
    private final RecoveryProperties props;

    @Scheduled(fixedDelay = 2 * 60 * 1000)
    public void checkForStuckTasks() {
        if (!props.isEnabled()) return;

        List<AgentTask> workingTasks = stateStore.getWorkingTasks();
        if (workingTasks.isEmpty()) return;

        Duration warnThreshold = Duration.ofMinutes(props.getWarnThresholdMinutes());
        Duration actThreshold  = Duration.ofMinutes(props.getActThresholdMinutes());
        Instant now = Instant.now();

        for (AgentTask task : workingTasks) {
            if (task.getLastActivityAt() == null) continue;

            Duration idle = Duration.between(task.getLastActivityAt(), now);

            if (idle.compareTo(actThreshold) >= 0 && task.getStuckDetectedAt() == null) {
                log.warn("Task {} idle for {}min — triggering recovery",
                    task.getTaskId(), idle.toMinutes());

                stateStore.markStuckDetected(task.getTaskId());
                stateStore.markRunStuck(task.getRunId());
                List<String> events = stateStore.getTaskEventLog(
                    task.getTaskId(), props.getEvaluatorLogWindow()
                );
                StuckRunEvaluator.EvaluationResult result = evaluator.evaluate(task, events);

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
    }
}

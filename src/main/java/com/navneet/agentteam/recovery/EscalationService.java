package com.navneet.agentteam.recovery;

import com.navneet.agentteam.harness.WorkerPool;
import com.navneet.agentteam.sse.RunEvent;
import com.navneet.agentteam.sse.SseBroadcaster;
import com.navneet.agentteam.state.StateStore;
import com.navneet.agentteam.state.entity.AgentTask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class EscalationService {

    private final StateStore stateStore;
    private final WorkerPool workerPool;
    private final RunRecoveryService recoveryService;
    private final SseBroadcaster sseBroadcaster;
    private final RecoveryProperties props;

    public void escalate(AgentTask task) {
        int level = task.getRecoveryAttempt() != null ? task.getRecoveryAttempt() : 0;

        EscalationTarget target = switch (level) {
            case 0, 1 -> resolveCapableModel(task);
            case 2    -> resolveAlternateProvider(task);
            default   -> null;
        };

        if (target == null) {
            log.warn("Task {} reached escalation terminal after {} attempts",
                task.getTaskId(), level);
            sseBroadcaster.emit(task.getRunId(), RunEvent.log(
                task.getRunId(), task.getTaskId(),
                "🔄 Escalation exhausted for task [" + task.getTaskId() +
                "] — triggering fresh run restart"
            ));
            recoveryService.recover(task, new StuckRunEvaluator.EvaluationResult(
                StuckClassification.UNKNOWN,
                "Escalation ladder exhausted after " + level + " attempts"
            ));
            return;
        }

        log.info("Escalating task {} → {} / {} (attempt {})",
            task.getTaskId(), target.provider(), target.model(), level + 1);

        sseBroadcaster.emit(task.getRunId(), RunEvent.log(
            task.getRunId(), task.getTaskId(),
            "⬆️ Escalating task [" + task.getTaskId() + "] → " +
            target.provider() + " / " + target.model()
        ));

        stateStore.escalateTask(task.getTaskId(), target.provider(), target.model());
        workerPool.cancelTask(task.getTaskId());
        workerPool.resubmitTask(stateStore.getTask(task.getTaskId()), task.getRunId());
    }

    public void escalateProvider(AgentTask task) {
        stateStore.setEscalationLevel(task.getTaskId(), 2);
        escalate(stateStore.getTask(task.getTaskId()));
    }

    public void escalateAfterContextOverflow(AgentTask task) {
        stateStore.forceCompactionFlag(task.getTaskId());
        workerPool.resubmitTask(task, task.getRunId());
    }

    private EscalationTarget resolveCapableModel(AgentTask task) {
        String provider = task.getCurrentProvider() != null
            ? task.getCurrentProvider()
            : props.getDefaultProvider();
        String capable = props.getCapableModelFor(provider);
        if (capable == null) return resolveAlternateProvider(task);
        return new EscalationTarget(provider, capable);
    }

    private EscalationTarget resolveAlternateProvider(AgentTask task) {
        String current = task.getCurrentProvider() != null
            ? task.getCurrentProvider()
            : props.getDefaultProvider();
        String alt = props.getAlternateProviderFor(current);
        if (alt == null) return null;
        String altModel = props.getCapableModelFor(alt);
        if (altModel == null) return null;
        return new EscalationTarget(alt, altModel);
    }

    public record EscalationTarget(String provider, String model) {}
}

package com.navneet.executespec.recovery;

import com.navneet.executespec.state.entity.AgentTask;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RequiredArgsConstructor
public class StuckRunEvaluator {

    private final RecoveryProperties props;

    public record EvaluationResult(
        StuckClassification classification,
        String detail
    ) {}

    public EvaluationResult evaluate(AgentTask task, List<String> events) {

        if (events.isEmpty()) {
            return new EvaluationResult(
                StuckClassification.UNKNOWN,
                "No log events found for task " + task.getTaskId()
            );
        }

        String lastEvent = events.get(events.size() - 1);
        if (lastEvent.contains("LLM_REQUEST_SENT") || lastEvent.contains("Calling LLM")) {
            return new EvaluationResult(
                StuckClassification.LLM_TIMEOUT,
                "Last event was outbound LLM call with no response received. Last event: [" + lastEvent + "]"
            );
        }

        boolean hasLockDenied = events.stream()
            .anyMatch(e -> e.contains("FILE_LOCK_DENIED") || e.contains("lock denied"));
        boolean noProgressAfterLock = events.subList(
            Math.max(0, events.size() - 5), events.size()
        ).stream().allMatch(e -> e.contains("FILE_LOCK_DENIED") || e.contains("WAITING"));

        if (hasLockDenied && noProgressAfterLock) {
            return new EvaluationResult(
                StuckClassification.FILE_LOCK_BLOCK,
                "Task blocked on file lock with no progress. Recent events: " +
                events.subList(Math.max(0, events.size() - 5), events.size())
            );
        }

        int logWindow = props.getEvaluatorLogWindow();
        List<String> recentWindow = events.subList(
            Math.max(0, events.size() - logWindow), events.size()
        );
        String loopedTool = detectToolLoop(recentWindow);
        if (loopedTool != null) {
            return new EvaluationResult(
                StuckClassification.LLM_LOOP,
                "Tool '" + loopedTool + "' called " + props.getLoopRepeatThreshold() +
                "+ times in last " + logWindow + " events with no state change"
            );
        }

        boolean hasBuildFailure = events.stream()
            .anyMatch(e -> e.contains("BUILD_FAILED") || e.contains("TEST_FAILED"));
        boolean noCommitAfterFailure = events.subList(
            Math.max(0, events.size() - 8), events.size()
        ).stream().noneMatch(e -> e.contains("git_commit") || e.contains("COMMITTED"));

        if (hasBuildFailure && noCommitAfterFailure) {
            return new EvaluationResult(
                StuckClassification.BUILD_FAILURE_STALL,
                "Build/test failure detected and worker has not committed a fix"
            );
        }

        return new EvaluationResult(
            StuckClassification.UNKNOWN,
            "Could not determine cause from " + events.size() +
            " log events. Last: [" + lastEvent + "]"
        );
    }

    private String detectToolLoop(List<String> events) {
        Map<String, Long> toolCounts = events.stream()
            .filter(e -> e.contains("TOOL_CALL:"))
            .map(e -> e.replaceAll(".*TOOL_CALL:(\\w+).*", "$1"))
            .collect(Collectors.groupingBy(t -> t, Collectors.counting()));

        return toolCounts.entrySet().stream()
            .filter(e -> e.getValue() >= props.getLoopRepeatThreshold())
            .map(Map.Entry::getKey)
            .findFirst()
            .orElse(null);
    }
}

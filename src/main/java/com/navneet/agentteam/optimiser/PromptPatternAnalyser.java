package com.navneet.agentteam.optimiser;

import com.navneet.agentteam.recovery.StuckClassification;
import com.navneet.agentteam.state.StateStore;
import com.navneet.agentteam.state.entity.AgentTask;
import com.navneet.agentteam.state.entity.PipelineRun;
import com.navneet.agentteam.state.repository.RunRecoveryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class PromptPatternAnalyser {

    private final StateStore stateStore;
    private final RunRecoveryRepository recoveryRepository;
    private final PromptOptimiserProperties props;

    public PromptPatterns analyse() {
        List<PipelineRun> recentRuns = stateStore.getRecentCompletedRuns(
            props.getAnalysisWindowRuns()
        );

        if (recentRuns.isEmpty()) return emptyPatterns();

        List<String> runIds = recentRuns.stream()
            .map(PipelineRun::getRunId)
            .collect(Collectors.toList());

        Map<String, List<String>> patternsByPrompt = new LinkedHashMap<>();

        // ── Worker patterns ───────────────────────────────────────────────
        List<String> workerPatterns = new ArrayList<>();

        long loopCount = recoveryRepository.findByClassificationIn(
            List.of(StuckClassification.LLM_LOOP), runIds
        ).size();
        if (loopCount >= props.getPatternMinOccurrences()) {
            workerPatterns.add("LLM_LOOP detected " + loopCount +
                " times — worker looping on same tool");
        }

        long lockViolations = recentRuns.stream()
            .flatMap(r -> stateStore.getTasksForRun(r.getRunId()).stream())
            .filter(t -> t.getLastLlmFailure() != null &&
                         t.getLastLlmFailure().contains("FILE_LOCK"))
            .count();
        if (lockViolations >= props.getPatternMinOccurrences()) {
            workerPatterns.add("Worker not checking file-locks before editing " +
                "(" + lockViolations + " violations)");
        }

        long boundaryViolations = recentRuns.stream()
            .flatMap(r -> stateStore.getTasksForRun(r.getRunId()).stream())
            .filter(t -> "BOUNDARY_VIOLATION".equals(t.getLastLlmFailure()))
            .count();
        if (boundaryViolations >= props.getPatternMinOccurrences()) {
            workerPatterns.add("Worker exceeding task boundary in " +
                boundaryViolations + " tasks");
        }

        if (!workerPatterns.isEmpty()) {
            patternsByPrompt.put("worker.txt", workerPatterns);
        }

        // ── Planner patterns ──────────────────────────────────────────────
        List<String> plannerPatterns = new ArrayList<>();

        long overreachRuns = recentRuns.stream()
            .filter(r -> {
                List<AgentTask> tasks = stateStore.getTasksForRun(r.getRunId());
                return tasks.stream().anyMatch(t -> "PARTIAL".equals(t.getStatus()));
            }).count();
        if (overreachRuns >= props.getPatternMinOccurrences()) {
            plannerPatterns.add("Task boundaries too broad — PARTIAL status in " +
                overreachRuns + " recent runs");
        }

        if (!plannerPatterns.isEmpty()) {
            patternsByPrompt.put("planner.txt", plannerPatterns);
        }

        // ── Reviewer patterns ─────────────────────────────────────────────
        List<String> reviewerPatterns = new ArrayList<>();

        long missedViolations = recentRuns.stream()
            .filter(r -> {
                var review = stateStore.getReviewReport(r.getRunId());
                return review != null &&
                       "MERGE".equals(review.getRecommendation()) &&
                       stateStore.getTasksForRun(r.getRunId()).stream()
                           .anyMatch(t -> "README_MISSING".equals(t.getLastLlmFailure()));
            }).count();
        if (missedViolations >= props.getPatternMinOccurrences()) {
            reviewerPatterns.add("Reviewer approving MERGE when README check " +
                "later blocked merge (" + missedViolations + " runs)");
        }

        if (!reviewerPatterns.isEmpty()) {
            patternsByPrompt.put("reviewer.txt", reviewerPatterns);
        }

        if (patternsByPrompt.isEmpty()) return emptyPatterns();

        String summary = patternsByPrompt.entrySet().stream()
            .map(e -> e.getKey() + ": " + e.getValue().size() + " pattern(s)")
            .collect(Collectors.joining(", "));

        return new PromptPatterns(patternsByPrompt, runIds, true, summary);
    }

    private PromptPatterns emptyPatterns() {
        return new PromptPatterns(Map.of(), List.of(), false, "No patterns detected");
    }
}

package com.navneet.executespec.optimiser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.state.entity.AgentTask;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.RunRecoveryRepository;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class PromptPatternAnalyser {

  private final StateStore stateStore;
  private final RunRecoveryRepository recoveryRepository;
  private final PromptOptimiserProperties props;

  public PromptPatterns analyse() {
    List<PipelineRun> recentRuns = stateStore.getRecentCompletedRuns(props.getAnalysisWindowRuns());

    if (recentRuns.isEmpty()) return emptyPatterns();

    List<String> runIds =
        recentRuns.stream().map(PipelineRun::getRunId).collect(Collectors.toList());

    Map<String, List<String>> patternsByPrompt = new LinkedHashMap<>();

    List<String> workerPatterns = new ArrayList<>();

    long loopCount =
        recoveryRepository.findByClassificationIn(java.util.List.of("LLM_LOOP"), runIds).size();
    if (loopCount >= props.getPatternMinOccurrences()) {
      workerPatterns.add("LLM_LOOP detected " + loopCount + " times — worker looping on same tool");
    }

    long lockViolations =
        recentRuns.stream()
            .flatMap(r -> stateStore.getTasksForRun(r.getRunId()).stream())
            .filter(
                t -> t.getLastLlmFailure() != null && t.getLastLlmFailure().contains("FILE_LOCK"))
            .count();
    if (lockViolations >= props.getPatternMinOccurrences()) {
      workerPatterns.add(
          "Worker not checking file-locks before editing " + "(" + lockViolations + " violations)");
    }

    long boundaryViolations =
        recentRuns.stream()
            .flatMap(r -> stateStore.getTasksForRun(r.getRunId()).stream())
            .filter(t -> "BOUNDARY_VIOLATION".equals(t.getLastLlmFailure()))
            .count();
    if (boundaryViolations >= props.getPatternMinOccurrences()) {
      workerPatterns.add("Worker exceeding task boundary in " + boundaryViolations + " tasks");
    }

    // Audit 2026-05-12 review item F #42: prompt filenames had bit-rotted to `.txt` when the
    // canonical V2 prompts moved to `.md`. The V1 worker.txt still exists (legacy archive)
    // but V2's worker prompt is composed via `PromptAssembler` from fragments, so this
    // optimiser only meaningfully targets the planner+reviewer for now. Pointed at the V2
    // canonical files so `Files.exists(resolvePromptPath(...))` is true for them.
    if (!workerPatterns.isEmpty()) {
      patternsByPrompt.put("worker.txt", workerPatterns); // V1 legacy worker — still in classpath
    }

    List<String> plannerPatterns = new ArrayList<>();

    long overreachRuns =
        recentRuns.stream()
            .filter(
                r -> {
                  List<AgentTask> tasks = stateStore.getTasksForRun(r.getRunId());
                  return tasks.stream().anyMatch(t -> "PARTIAL".equals(t.getStatus()));
                })
            .count();
    if (overreachRuns >= props.getPatternMinOccurrences()) {
      plannerPatterns.add(
          "Task boundaries too broad — PARTIAL status in " + overreachRuns + " recent runs");
    }

    if (!plannerPatterns.isEmpty()) {
      // Point at the V2 canonical planner prompt (outline-planner.md) — was `planner.txt`
      // which is the V1 legacy archive that no longer drives the active pipeline.
      patternsByPrompt.put("outline-planner.md", plannerPatterns);
    }

    List<String> reviewerPatterns = new ArrayList<>();

    long missedViolations =
        recentRuns.stream()
            .filter(
                r -> {
                  var review = stateStore.getReviewReportV2(r.getRunId());
                  return review != null
                      && "MERGE".equals(review.getRecommendationCompat())
                      && stateStore.getTasksForRun(r.getRunId()).stream()
                          .anyMatch(t -> "README_MISSING".equals(t.getLastLlmFailure()));
                })
            .count();
    if (missedViolations >= props.getPatternMinOccurrences()) {
      reviewerPatterns.add(
          "Reviewer approving MERGE when README check "
              + "later blocked merge ("
              + missedViolations
              + " runs)");
    }

    if (!reviewerPatterns.isEmpty()) {
      // V2 reviewer is composed from reviewer-general.md + reviewer-wiring.md — point at the
      // general pass which is the most likely to want patternation. Previously `reviewer.txt`
      // which never existed in the V2 codebase (audit F #42).
      patternsByPrompt.put("reviewer-general.md", reviewerPatterns);
    }

    if (patternsByPrompt.isEmpty()) return emptyPatterns();

    String summary =
        patternsByPrompt.entrySet().stream()
            .map(e -> e.getKey() + ": " + e.getValue().size() + " pattern(s)")
            .collect(Collectors.joining(", "));

    return new PromptPatterns(patternsByPrompt, runIds, true, summary);
  }

  private PromptPatterns emptyPatterns() {
    return new PromptPatterns(Map.of(), List.of(), false, "No patterns detected");
  }
}

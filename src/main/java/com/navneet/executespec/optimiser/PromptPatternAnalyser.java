package com.navneet.executespec.optimiser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.state.entity.AgentTask;
import com.navneet.executespec.state.entity.PipelineRun;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class PromptPatternAnalyser {

  private final StateStore stateStore;
  private final PromptOptimiserProperties props;

  public PromptPatterns analyse() {
    List<PipelineRun> recentRuns = stateStore.getRecentCompletedRuns(props.getAnalysisWindowRuns());

    if (recentRuns.isEmpty()) return emptyPatterns();

    List<String> runIds =
        recentRuns.stream().map(PipelineRun::getRunId).collect(Collectors.toList());

    Map<String, List<String>> patternsByPrompt = new LinkedHashMap<>();

    // The worker prompt is assembled from Java constants and provider fragments; the old
    // `worker.txt` compatibility resource was removed. Worker-specific pattern optimisation is
    // intentionally not emitted until the optimiser has a writable, versioned prompt target.

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

package com.navneet.executespec.project.estimation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.navneet.executespec.cost.RunCostTracker;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * v2.5.2 — non-LLM heuristic estimate for an upcoming Launch. No new network cost: reads recent
 * completed runs for the project, buckets by requirement length, returns p25/p75 of actual task
 * count + cost. Falls back to global project average when the bucket has fewer than 5 samples; then
 * to a platform-wide constant when the project is brand-new.
 *
 * <p>2026-07-17 — the estimate is now model-aware. The project history supplies the <b>token
 * volume</b> a spec of this size typically needs (roughly model-independent — the same work costs
 * the same tokens on any model), and that volume is priced against the provider/model the user is
 * about to select. Changing the model now changes the estimate. When no provider/model is supplied
 * (or history has no token split) it degrades to the previous raw-cost percentile.
 *
 * <p>Surfaced by {@code GET /api/projects/{id}/estimate?requirement=...&provider=...&model=...} and
 * rendered as a gray chip under the requirement textarea. Explicitly <b>not</b> a cost gate — users
 * still approve cost at the plan gate; this chip is a pre-submit affordance.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EstimationService {

  private final PipelineRunRepository runRepo;
  private final RunCostTracker costTracker;

  public record Estimate(
      int estimatedTasksLow,
      int estimatedTasksHigh,
      double estimatedCostLow,
      double estimatedCostHigh,
      String basis) {}

  /** Platform-wide fallback token volume when a project has no history at all (p25 / p75). */
  private static final long[] FALLBACK_INPUT_TOKENS = {60_000L, 400_000L};

  private static final long[] FALLBACK_OUTPUT_TOKENS = {4_000L, 20_000L};

  private static final Estimate GLOBAL_FALLBACK =
      new Estimate(3, 7, 0.05, 0.25, "Platform average (no project history yet)");

  /** Legacy two-arg entry point (no model): keeps the raw-cost heuristic. */
  public Estimate estimate(String projectId, String requirement) {
    return estimate(projectId, requirement, null, null);
  }

  public Estimate estimate(String projectId, String requirement, String provider, String model) {
    if (requirement == null) requirement = "";
    int len = requirement.length();
    String bucket = len < 200 ? "short" : (len < 500 ? "medium" : "long");

    boolean modelAware = provider != null && !provider.isBlank();

    List<PipelineRun> history =
        runRepo.findByProjectIdOrderByStartedAtDesc(projectId, PageRequest.of(0, 20));
    List<PipelineRun> completed =
        history.stream().filter(r -> "COMPLETE".equals(r.getStatus())).toList();

    if (completed.isEmpty())
      return modelAware ? modelAwareFallback(provider, model) : GLOBAL_FALLBACK;

    // Bucket-scoped first; fall back to project-wide when <5 samples.
    List<PipelineRun> bucketed =
        completed.stream().filter(r -> bucketFor(r.getUserRequest()).equals(bucket)).toList();
    List<PipelineRun> sample = bucketed.size() >= 5 ? bucketed : completed;
    String basis =
        bucketed.size() >= 5
            ? ("Based on " + bucketed.size() + " prior " + bucket + " runs in this project")
            : ("Based on " + completed.size() + " prior runs in this project");

    List<Integer> tasks = new ArrayList<>();
    List<Double> costs = new ArrayList<>();
    List<Long> inputTokens = new ArrayList<>();
    List<Long> outputTokens = new ArrayList<>();
    for (PipelineRun r : sample) {
      if (r.getActualCostUsd() != null) costs.add(r.getActualCostUsd());
      long inTok = r.getTotalInputTokens() == null ? 0 : r.getTotalInputTokens();
      long outTok = r.getTotalOutputTokens() == null ? 0 : r.getTotalOutputTokens();
      long totalTokens = inTok + outTok;
      // Task count isn't directly on PipelineRun — use tokens as a proxy (~6k tokens/task).
      if (totalTokens > 0) tasks.add((int) Math.max(1, Math.round(totalTokens / 6000.0)));
      if (inTok > 0 || outTok > 0) {
        inputTokens.add(inTok);
        outputTokens.add(outTok);
      }
    }
    if (tasks.isEmpty()) return modelAware ? modelAwareFallback(provider, model) : GLOBAL_FALLBACK;

    Collections.sort(tasks);
    int tasksLow = percentile(tasks, 0.25).intValue();
    int tasksHigh = percentile(tasks, 0.75).intValue();

    // Model-aware path: price the historical token volume against the selected provider/model so
    // the
    // estimate scales with the choice. Requires token history AND a selected provider.
    if (modelAware && !inputTokens.isEmpty()) {
      Collections.sort(inputTokens);
      Collections.sort(outputTokens);
      long inLow = percentile(inputTokens, 0.25).longValue();
      long inHigh = percentile(inputTokens, 0.75).longValue();
      long outLow = percentile(outputTokens, 0.25).longValue();
      long outHigh = percentile(outputTokens, 0.75).longValue();
      double costLow = costTracker.estimateTokenCostUsd(provider, model, inLow, outLow);
      double costHigh = costTracker.estimateTokenCostUsd(provider, model, inHigh, outHigh);
      String label = (model == null || model.isBlank()) ? provider : provider + " / " + model;
      return new Estimate(tasksLow, tasksHigh, costLow, costHigh, basis + " · priced for " + label);
    }

    // Legacy raw-cost percentile (no provider selected, or no token history).
    if (costs.isEmpty()) return modelAware ? modelAwareFallback(provider, model) : GLOBAL_FALLBACK;
    Collections.sort(costs);
    return new Estimate(
        tasksLow, tasksHigh, percentile(costs, 0.25), percentile(costs, 0.75), basis);
  }

  /** No project token history yet, but a provider is selected — price a nominal volume. */
  private Estimate modelAwareFallback(String provider, String model) {
    double low =
        costTracker.estimateTokenCostUsd(
            provider, model, FALLBACK_INPUT_TOKENS[0], FALLBACK_OUTPUT_TOKENS[0]);
    double high =
        costTracker.estimateTokenCostUsd(
            provider, model, FALLBACK_INPUT_TOKENS[1], FALLBACK_OUTPUT_TOKENS[1]);
    if (low <= 0.0 && high <= 0.0) return GLOBAL_FALLBACK; // provider unpriced → keep old constant
    String label = (model == null || model.isBlank()) ? provider : provider + " / " + model;
    return new Estimate(3, 7, low, high, "Platform average priced for " + label);
  }

  private static String bucketFor(String req) {
    int len = req == null ? 0 : req.length();
    return len < 200 ? "short" : (len < 500 ? "medium" : "long");
  }

  /** Linear interpolation percentile on a pre-sorted list. */
  private static Double percentile(List<? extends Number> sorted, double p) {
    if (sorted.isEmpty()) return 0.0;
    if (sorted.size() == 1) return sorted.get(0).doubleValue();
    double idx = p * (sorted.size() - 1);
    int lo = (int) Math.floor(idx);
    int hi = (int) Math.ceil(idx);
    if (lo == hi) return sorted.get(lo).doubleValue();
    double frac = idx - lo;
    return sorted.get(lo).doubleValue() * (1 - frac) + sorted.get(hi).doubleValue() * frac;
  }
}

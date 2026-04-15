package com.navneet.executespec.project.estimation;

import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * v2.5.2 — non-LLM heuristic estimate for an upcoming Launch. No new network
 * cost: reads recent completed runs for the project, buckets by requirement
 * length, returns p25/p75 of actual task count + cost. Falls back to global
 * project average when the bucket has fewer than 5 samples; then to a
 * platform-wide constant when the project is brand-new.
 *
 * <p>Surfaced by {@code GET /api/projects/{id}/estimate?requirement=...} and
 * rendered as a gray chip under the requirement textarea in ExecutionSetup.
 * Explicitly <b>not</b> a cost gate — users still approve cost at the plan
 * gate; this chip is a pre-submit affordance.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EstimationService {

    private final PipelineRunRepository runRepo;

    public record Estimate(
            int estimatedTasksLow,
            int estimatedTasksHigh,
            double estimatedCostLow,
            double estimatedCostHigh,
            String basis) {}

    /** Platform-wide fallback when a project has no history at all. */
    private static final Estimate GLOBAL_FALLBACK = new Estimate(
            3, 7, 0.05, 0.25, "Platform average (no project history yet)");

    public Estimate estimate(String projectId, String requirement) {
        if (requirement == null) requirement = "";
        int len = requirement.length();
        String bucket = len < 200 ? "short" : (len < 500 ? "medium" : "long");

        List<PipelineRun> history = runRepo.findByProjectIdOrderByStartedAtDesc(
                projectId, PageRequest.of(0, 20));
        List<PipelineRun> completed = history.stream()
                .filter(r -> "COMPLETE".equals(r.getStatus()))
                .toList();

        if (completed.isEmpty()) return GLOBAL_FALLBACK;

        // Bucket-scoped first; fall back to project-wide when <5 samples.
        List<PipelineRun> bucketed = completed.stream()
                .filter(r -> bucketFor(r.getUserRequest()).equals(bucket))
                .toList();
        List<PipelineRun> sample = bucketed.size() >= 5 ? bucketed : completed;
        String basis = bucketed.size() >= 5
                ? ("Based on " + bucketed.size() + " prior " + bucket + " runs in this project")
                : ("Based on " + completed.size() + " prior runs in this project");

        List<Integer> tasks = new ArrayList<>();
        List<Double> costs = new ArrayList<>();
        for (PipelineRun r : sample) {
            if (r.getActualCostUsd() != null) costs.add(r.getActualCostUsd());
            // Task count isn't directly on PipelineRun — use tokens as a proxy.
            // A typical task runs ~6k tokens end-to-end; tokens/6000 gives a
            // reasonable "how many tasks did this run actually need" signal.
            long totalTokens = (r.getTotalInputTokens() == null ? 0 : r.getTotalInputTokens())
                    + (r.getTotalOutputTokens() == null ? 0 : r.getTotalOutputTokens());
            if (totalTokens > 0) tasks.add((int) Math.max(1, Math.round(totalTokens / 6000.0)));
        }
        if (tasks.isEmpty() || costs.isEmpty()) return GLOBAL_FALLBACK;

        Collections.sort(tasks);
        Collections.sort(costs);
        return new Estimate(
                percentile(tasks, 0.25).intValue(),
                percentile(tasks, 0.75).intValue(),
                percentile(costs, 0.25),
                percentile(costs, 0.75),
                basis);
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

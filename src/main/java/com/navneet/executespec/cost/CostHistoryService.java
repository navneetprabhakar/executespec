package com.navneet.executespec.cost;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Stage 2 of the 2026-05-13 cost-estimation revamp — k-NN over historical runs.
 *
 * <p>Two write/read flows:
 *
 * <ul>
 *   <li><b>recordOnTerminal</b> — called once per terminal run from RunOrchestrator. Persists a
 *       feature vector + actual cost into {@code run_cost_history}.
 *   <li><b>estimateFromHistory</b> — called by RunOrchestrator.persistEstimate BEFORE the Stage-1
 *       formula. Returns Optional&lt;Estimate&gt; when ≥ {@link #MIN_SAMPLES} similar runs exist;
 *       caller falls back to formula otherwise.
 * </ul>
 *
 * <p>Bootstrap path: zero history → always falls back to the Stage-1 formula. After ~10 similar
 * runs the historical estimate kicks in. After ~50 it dominates for that shape.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CostHistoryService {

  /** Minimum number of similar runs required to trust the historical estimate. */
  public static final int MIN_SAMPLES = 10;

  /** Maximum samples to consider (newer = more relevant; oldest beyond this are dropped). */
  public static final int MAX_SAMPLES = 100;

  /**
   * Percentile boundaries for the returned [low, high] band. Stage-1 formula uses ratio + fixed
   * multiplier; the historical estimator returns observed-percentile boundaries, which is naturally
   * more accurate as samples accumulate.
   */
  private static final double LOW_PERCENTILE = 0.25;

  private static final double HIGH_PERCENTILE = 0.75;

  private final RunCostHistoryRepository historyRepo;
  private final PipelineRunRepository runRepo;

  /**
   * Record a completed run's actual cost + features. Idempotent — duplicate records replace. Logged
   * but never throws; cost-history is a best-effort telemetry surface.
   */
  public void recordOnTerminal(String runId) {
    if (runId == null) return;
    try {
      Optional<PipelineRun> opt = runRepo.findById(runId);
      if (opt.isEmpty()) return;
      PipelineRun r = opt.get();
      if (r.getActualCostUsd() == null || r.getCompletedAt() == null) {
        // Don't record runs we don't have cost / terminal-time for yet.
        return;
      }
      RunCostHistory h =
          RunCostHistory.builder()
              .runId(r.getRunId())
              .orgId(r.getOrgId())
              .projectId(r.getProjectId())
              .planStrategy(r.getPlanStrategy())
              .classification(extractClassification(r))
              .taskCount(extractTaskCount(r))
              .repoCount(extractRepoCount(r))
              .projectType(extractProjectType(r))
              .provider(r.getAiProvider() == null ? null : r.getAiProvider().toUpperCase())
              .specLength(r.getUserRequest() == null ? 0 : r.getUserRequest().length())
              .actualCostUsd(BigDecimal.valueOf(r.getActualCostUsd()))
              .actualTokensIn(
                  (long) (r.getTotalInputTokens() == null ? 0 : r.getTotalInputTokens()))
              .actualTokensOut(
                  (long) (r.getTotalOutputTokens() == null ? 0 : r.getTotalOutputTokens()))
              // verifiedScore lives on ValidationReport (not PipelineRun) — fetched later
              // when the cost-history sweeper backfills score correlation. Leave null on
              // initial record.
              .verifiedScore(null)
              .status(r.getStatus())
              .statusReason(r.getStatusReason())
              .startedAt(r.getStartedAt() == null ? Instant.now() : r.getStartedAt())
              .completedAt(r.getCompletedAt())
              .schemaVersion((short) 1)
              .build();
      historyRepo.save(h);
      log.debug(
          "[CostHistory] recorded run {} (strategy={} provider={} repos={} tasks={} cost=${})",
          runId,
          h.getPlanStrategy(),
          h.getProvider(),
          h.getRepoCount(),
          h.getTaskCount(),
          h.getActualCostUsd());
    } catch (Exception e) {
      log.warn(
          "[CostHistory] failed to record run {} (best-effort, continuing): {}",
          runId,
          e.getMessage());
    }
  }

  /**
   * Estimate the cost of a new run from historical observations of similar runs. Returns {@link
   * Optional#empty()} when fewer than {@link #MIN_SAMPLES} similar runs exist — caller MUST fall
   * back to the formula estimator.
   *
   * @param strategy plan strategy (SINGLE_SHOT / SMALL / ...)
   * @param provider provider key (uppercase: ANTHROPIC / OPENAI / GEMINI)
   * @param repoCount linked repo count
   * @param taskCount expected task count
   */
  public Optional<RunCostTracker.Estimate> estimateFromHistory(
      String strategy, String provider, int repoCount, int taskCount) {
    if (strategy == null || provider == null) return Optional.empty();
    List<BigDecimal> strict =
        historyRepo.findSimilarCosts(
            strategy, provider, repoCount, taskCount, PageRequest.of(0, MAX_SAMPLES));
    List<BigDecimal> use =
        strict.size() >= MIN_SAMPLES
            ? strict
            : historyRepo.findSimilarCostsLoose(strategy, provider, PageRequest.of(0, MAX_SAMPLES));
    if (use.size() < MIN_SAMPLES) {
      log.debug(
          "[CostHistory] estimate-from-history skipped — only {} samples for {} {} repos={}"
              + " tasks={} (need ≥{})",
          use.size(),
          strategy,
          provider,
          repoCount,
          taskCount,
          MIN_SAMPLES);
      return Optional.empty();
    }
    double[] sorted = use.stream().mapToDouble(BigDecimal::doubleValue).sorted().toArray();
    double low = percentile(sorted, LOW_PERCENTILE);
    double high = percentile(sorted, HIGH_PERCENTILE);
    int credits = Math.max(1, (int) Math.ceil(((low + high) / 2.0 * 1.20) / 5.0));
    double hardCap = credits * 5.0;
    log.info(
        "[CostHistory] estimate from {} samples for {} {} repos={} tasks={}: ${}-${}",
        sorted.length,
        strategy,
        provider,
        repoCount,
        taskCount,
        String.format("%.4f", low),
        String.format("%.4f", high));
    return Optional.of(new RunCostTracker.Estimate(low, high, credits, hardCap, taskCount));
  }

  // ── Helpers ────────────────────────────────────────────────────────────

  private static double percentile(double[] sortedAsc, double q) {
    if (sortedAsc.length == 0) return 0.0;
    if (sortedAsc.length == 1) return sortedAsc[0];
    double idx = q * (sortedAsc.length - 1);
    int lo = (int) Math.floor(idx);
    int hi = (int) Math.ceil(idx);
    if (lo == hi) return sortedAsc[lo];
    double frac = idx - lo;
    return sortedAsc[lo] + frac * (sortedAsc[hi] - sortedAsc[lo]);
  }

  private static int extractTaskCount(PipelineRun r) {
    // PipelineRun has no direct task count; the planJson would include it, but parsing here
    // would be lossy. Default to 1; future revision can plumb the real count through.
    return 1;
  }

  private static int extractRepoCount(PipelineRun r) {
    String csv = r.getSelectedRepoIds();
    if (csv == null || csv.isBlank()) {
      return (r.getRepoUrl() != null && !r.getRepoUrl().isBlank()) ? 1 : 0;
    }
    // selectedRepoIds is stored as a JSON array; count commas + 1 as an approximation.
    int count = 1;
    for (int i = 0; i < csv.length(); i++) if (csv.charAt(i) == ',') count++;
    return count;
  }

  private static String extractProjectType(PipelineRun r) {
    // Future: parse from planJson or run.techStack. Null for now.
    return null;
  }

  private static String extractClassification(PipelineRun r) {
    // Mirror PlanStrategy on the run row for now — V1's classification enum is being retired.
    return r.getPlanStrategy();
  }
}

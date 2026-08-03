package com.navneet.executespec.cost;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

  private static final ObjectMapper JSON = new ObjectMapper();

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
  private final RunStepUsageRepository stepUsageRepo;

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
      if (!"COMPLETE".equals(r.getStatus())
          || r.getActualCostUsd() == null
          || r.getCompletedAt() == null) {
        // Failed/partial/cancelled runs do not represent the full planned journey and would
        // depress a future approval cap. Also skip rows without cost / terminal-time evidence.
        return;
      }
      String routeFingerprint =
          routeFingerprint(stepUsageRepo.findByRunIdOrderByCreatedAtAsc(runId));
      if (routeFingerprint == null) {
        // Model-blind history is unsafe: a requested provider may have physically failed over or
        // used a different matrix/pinned model. Only ledger-attributed routes enter schema v2.
        return;
      }
      PlanShape planShape = extractPlanShape(r);
      if (planShape == null) {
        // Task count without its complexity distribution is not a safe pricing cohort. A three-LOW
        // plan and a three-HIGH plan can have materially different worker and review spend.
        return;
      }
      RunCostHistory h =
          RunCostHistory.builder()
              .runId(r.getRunId())
              .orgId(r.getOrgId())
              .projectId(r.getProjectId())
              .planStrategy(r.getPlanStrategy())
              .classification(extractClassification(r))
              .taskCount(planShape.taskCount())
              .taskComplexityFingerprint(planShape.complexityFingerprint())
              .repoCount(extractRepoCount(r))
              .projectType(extractProjectType(r))
              .provider(r.getAiProvider() == null ? null : r.getAiProvider().toUpperCase())
              .routeFingerprint(routeFingerprint)
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
              .schemaVersion((short) 2)
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
   * <p>RR-1551 — the estimate is TENANT-SCOPED. It is only ever computed from the submitting org's
   * own past runs, because the caller writes it straight onto {@code creditsCharged} and {@code
   * hardCapUsd}. A blank {@code orgId} is a fail-closed skip, not a licence to widen the pool: an
   * unattributable run takes the model-aware Stage-1 formula instead.
   *
   * @param orgId owning organisation of the run being priced — required; blank ⇒ formula fallback
   * @param strategy plan strategy (SINGLE_SHOT / SMALL / ...)
   * @param provider provider key (uppercase: ANTHROPIC / OPENAI / GEMINI)
   * @param repoCount linked repo count
   * @param taskCount expected task count
   * @param taskComplexityFingerprint canonical LOW/MEDIUM/HIGH distribution
   */
  public Optional<RunCostTracker.Estimate> estimateFromHistory(
      String orgId,
      String strategy,
      String provider,
      String routeFingerprint,
      int repoCount,
      int taskCount,
      String taskComplexityFingerprint) {
    if (strategy == null
        || provider == null
        || routeFingerprint == null
        || routeFingerprint.isBlank()
        || taskComplexityFingerprint == null
        || taskComplexityFingerprint.isBlank()) return Optional.empty();
    // RR-1551 — no tenant, no history. Never fall through to a cross-org pool.
    if (orgId == null || orgId.isBlank()) {
      log.debug(
          "[CostHistory] estimate-from-history skipped — run has no orgId; using formula estimate");
      return Optional.empty();
    }
    List<BigDecimal> strict =
        historyRepo.findSimilarCosts(
            orgId,
            strategy,
            provider,
            routeFingerprint,
            repoCount,
            taskCount,
            taskComplexityFingerprint,
            PageRequest.of(0, MAX_SAMPLES));
    // Do not fall back across repository/task shapes. A route-equivalent 20-task project is not a
    // safe approval-cap analogue for a one-task run (or vice versa).
    List<BigDecimal> use = strict;
    if (use.size() < MIN_SAMPLES) {
      log.debug(
          "[CostHistory] estimate-from-history skipped — only {} samples for org={} {} {} repos={}"
              + " tasks={} complexity={} (need ≥{})",
          use.size(),
          orgId,
          strategy,
          provider,
          repoCount,
          taskCount,
          taskComplexityFingerprint,
          MIN_SAMPLES);
      return Optional.empty();
    }
    double[] sorted = use.stream().mapToDouble(BigDecimal::doubleValue).sorted().toArray();
    double low = percentile(sorted, LOW_PERCENTILE);
    double high = percentile(sorted, HIGH_PERCENTILE);
    int credits = Math.max(1, (int) Math.ceil(((low + high) / 2.0 * 1.20) / 5.0));
    double hardCap = credits * 5.0;
    log.info(
        "[CostHistory] estimate from {} samples for org={} {} {} repos={} tasks={} complexity={}:"
            + " ${}-${}",
        sorted.length,
        orgId,
        strategy,
        provider,
        repoCount,
        taskCount,
        taskComplexityFingerprint,
        String.format("%.4f", low),
        String.format("%.4f", high));
    return Optional.of(new RunCostTracker.Estimate(low, high, credits, hardCap, taskCount));
  }

  static String routeFingerprint(List<RunStepUsage> attempts) {
    TreeSet<String> routes = new TreeSet<>();
    if (attempts != null) {
      attempts.stream()
          .filter(java.util.Objects::nonNull)
          .filter(a -> "SUCCESS".equalsIgnoreCase(a.getAttemptStatus()))
          .filter(a -> a.getProvider() != null && !a.getProvider().isBlank())
          .filter(a -> a.getModel() != null && !a.getModel().isBlank())
          .forEach(
              a ->
                  routes.add(
                      a.getProvider().trim().toUpperCase()
                          + ":"
                          + a.getModel().trim().toLowerCase()));
    }
    return routes.isEmpty() ? null : String.join(",", routes);
  }

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

  public static String taskComplexityFingerprint(List<String> complexities) {
    if (complexities == null || complexities.isEmpty()) return null;
    int low = 0;
    int medium = 0;
    int high = 0;
    for (String complexity : complexities) {
      switch (complexity == null ? "MEDIUM" : complexity.trim().toUpperCase()) {
        case "LOW" -> low++;
        case "HIGH" -> high++;
        default -> medium++;
      }
    }
    return "LOW:" + low + ",MEDIUM:" + medium + ",HIGH:" + high;
  }

  private static PlanShape extractPlanShape(PipelineRun r) {
    String planJson = r.getPlanJson();
    if (planJson != null && !planJson.isBlank()) {
      try {
        JsonNode tasks = JSON.readTree(planJson).path("tasks");
        if (tasks.isArray() && !tasks.isEmpty()) {
          java.util.ArrayList<String> complexities = new java.util.ArrayList<>();
          tasks.forEach(
              task -> {
                JsonNode complexity = task.get("complexity");
                complexities.add(
                    complexity == null || complexity.isNull() ? "MEDIUM" : complexity.asText());
              });
          return new PlanShape(tasks.size(), taskComplexityFingerprint(complexities));
        }
      } catch (Exception malformedPlan) {
        // Cost history is best-effort. Malformed/legacy plans remain schema v1 rather than entering
        // a shape-blind cohort.
      }
    }
    return null;
  }

  private record PlanShape(int taskCount, String complexityFingerprint) {}

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

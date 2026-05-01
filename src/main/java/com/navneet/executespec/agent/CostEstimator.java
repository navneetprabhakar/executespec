package com.navneet.executespec.agent;

import java.util.List;

import com.navneet.executespec.agent.model.TaskDefinition;
import com.navneet.executespec.agent.model.TaskPlan;
import com.navneet.executespec.config.AgentModelConfig;
import com.navneet.executespec.config.AgentProperties;
import com.navneet.executespec.pricing.ModelPricingRepository;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.router.ModelRegistry;
import com.navneet.executespec.router.ModelResolver;
import com.navneet.executespec.router.ResolvedModel;
import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.state.entity.AgentTask;
import com.navneet.executespec.testgen.repository.TestPlanRepository;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Estimates pipeline cost before plan approval. Pricing is DB-backed (model_pricing table),
 * editable via UI/REST.
 *
 * <p>Iteration ranges by complexity (low/high): LOW: 5 – 15 iterations MEDIUM: 15 – 40 iterations
 * HIGH: 30 – 80 iterations
 *
 * <p>High estimate adds 20% escalation premium (probability that stall detection promotes a
 * WORKHORSE task to the PLANNING-tier model mid-run).
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class CostEstimator {

  private final AgentProperties props;
  private final ModelPricingRepository pricingRepo;
  private final ModelResolver modelResolver;
  private final ModelRegistry modelRegistry;
  private final StateStore stateStore;
  private final TestPlanRepository testPlanRepository;
  private final TokenBudgetMonitor tokenBudgetMonitor;

  public record CostBreakdown(double lowUsd, double highUsd) {
    public String formatted() {
      return String.format("Estimated cost: $%.3f – $%.3f USD", lowUsd, highUsd);
    }
  }

  // Extra input tokens added for the reviewer pass to account for the full codebase context
  // it receives beyond the base MEDIUM_IN_TOKENS estimate.
  private static final int REVIEWER_CONTEXT_BUFFER = 20_000;

  // 50% buffer on high estimate to account for retries, failover, and cache-write overhead.
  private static final double HIGH_ESTIMATE_MULTIPLIER = 1.50;

  // Iteration count ranges per complexity (low / high scenarios).
  // Calibrated against observed P50/P95 across real runs (Apr 2026).
  private static final int LOW_ITER_LOW = 3;
  private static final int LOW_ITER_HIGH = 10;
  private static final int MEDIUM_ITER_LOW = 8;
  private static final int MEDIUM_ITER_HIGH = 25;
  private static final int HIGH_ITER_LOW = 15;
  private static final int HIGH_ITER_HIGH = 50;

  // Probability that stall detection promotes a WORKHORSE task to PLANNING tier mid-run.
  // Added as a premium to the high-end estimate only.
  private static final double PLANNING_ESCALATION_PROB = 0.20;

  // Anthropic prompt-cache pricing: cache writes cost 25% more than normal input.
  private static final double CACHE_WRITE_COST_MULTIPLIER = 1.25;

  // Anthropic prompt-cache pricing: cache reads cost only 10% of normal input (90% discount).
  private static final double CACHE_READ_COST_MULTIPLIER = 0.10;

  // Input token estimates per iteration by complexity.
  // Calibrated against observed sliding-window context sizes (first 2 + last 12 messages,
  // truncation at 5k/4k/3k per tool result). P50 actuals: LOW≈6k, MEDIUM≈15k, HIGH≈30k.
  private static final int LOW_IN_TOKENS = 8_000;
  private static final int MEDIUM_IN_TOKENS = 18_000;
  private static final int HIGH_IN_TOKENS = 35_000;

  // Output token estimates per iteration by complexity.
  private static final int LOW_OUT_TOKENS = 1_500;
  private static final int MEDIUM_OUT_TOKENS = 3_000;
  private static final int HIGH_OUT_TOKENS = 6_000;

  public CostBreakdown estimate(TaskPlan plan) {
    return estimate(plan, LlmProvider.ANTHROPIC);
  }

  public CostBreakdown estimate(TaskPlan plan, LlmProvider provider) {
    if (plan == null || plan.getTasks() == null) {
      return new CostBreakdown(0, 0);
    }

    ResolvedModel workerModel = modelResolver.resolve("WorkerAgent", provider);
    ResolvedModel plannerModel = modelResolver.resolve("PlannerAgent", provider);
    ResolvedModel reviewerModel = modelResolver.resolve("ReviewerAgentV2", provider);

    double[] workerRates = dbRatesForModel(workerModel.providerName(), workerModel.getModel());
    double[] plannerRates = dbRatesForModel(plannerModel.providerName(), plannerModel.getModel());
    double[] reviewerRates =
        dbRatesForModel(reviewerModel.providerName(), reviewerModel.getModel());

    double totalLow = 0;
    double totalHigh = 0;

    for (TaskDefinition task : plan.getTasks()) {
      String complexity =
          task.getComplexity() != null ? task.getComplexity().toUpperCase() : "MEDIUM";
      int inTok = complexityInputTokens(complexity);
      int outTok = tokenBudgetMonitor.maxOutputTokens(null, complexity);

      int[] range = iterRange(complexity);
      totalLow += costUsd(inTok, outTok, range[0], workerRates);
      totalHigh += costUsd(inTok, outTok, range[1], workerRates);
    }

    // Add fixed planner and reviewer costs
    int plannerOut = tokenBudgetMonitor.maxOutputTokens(null, "HIGH");
    int reviewerOut = tokenBudgetMonitor.maxOutputTokens(null, "MEDIUM");
    totalLow += costUsd(MEDIUM_IN_TOKENS, plannerOut, 1, plannerRates);
    totalHigh += costUsd(MEDIUM_IN_TOKENS, plannerOut, 1, plannerRates);
    totalLow += costUsd(MEDIUM_IN_TOKENS + REVIEWER_CONTEXT_BUFFER, reviewerOut, 1, reviewerRates);
    totalHigh += costUsd(MEDIUM_IN_TOKENS + REVIEWER_CONTEXT_BUFFER, reviewerOut, 1, reviewerRates);

    // Apply 50% buffer to high estimate to account for retries, failover,
    // and cache-write overhead that the simple model doesn't capture.
    totalHigh *= HIGH_ESTIMATE_MULTIPLIER;

    log.debug(
        "[CostEstimator] {} tasks → low=${}, high=${} (provider={})",
        plan.getTasks().size(),
        String.format("%.3f", totalLow),
        String.format("%.3f", totalHigh),
        provider);

    return new CostBreakdown(totalLow, totalHigh);
  }

  /** Estimate cost for a single task in feasibility mode. Returns [min, max]. */
  public double[] estimateFromTask(TaskDefinition task, String provider, String model) {
    if (task.getEstimatedIterations() == null) return new double[] {0, 0};

    double[] rates = dbRatesForModel(provider, model);
    int tokPerIter =
        task.getEstimatedTokensPerIteration() != null
            ? task.getEstimatedTokensPerIteration()
            : MEDIUM_IN_TOKENS;

    double min = costUsd(tokPerIter, MEDIUM_OUT_TOKENS, task.getEstimatedIterations().min(), rates);
    double max = costUsd(tokPerIter, MEDIUM_OUT_TOKENS, task.getEstimatedIterations().max(), rates);
    return new double[] {min, max};
  }

  /** Estimate fixed overhead cost for planner + reviewer. Returns [min, max]. */
  public double[] estimateOverhead(AgentModelConfig modelConfig) {
    double[] plannerRates = dbRates(props.getRoles().getPlanner());
    double[] reviewerRates = dbRates(props.getRoles().getReviewer());

    double min =
        costUsd(MEDIUM_IN_TOKENS, MEDIUM_OUT_TOKENS, 1, plannerRates)
            + costUsd(
                MEDIUM_IN_TOKENS + REVIEWER_CONTEXT_BUFFER, MEDIUM_OUT_TOKENS, 1, reviewerRates);
    double max =
        costUsd(MEDIUM_IN_TOKENS, MEDIUM_OUT_TOKENS, 1, plannerRates)
            + costUsd(
                MEDIUM_IN_TOKENS + REVIEWER_CONTEXT_BUFFER, MEDIUM_OUT_TOKENS, 2, reviewerRates);
    return new double[] {min, max};
  }

  /** Compute actual cost from real token counts. */
  public double computeCost(String provider, String model, long inputTokens, long outputTokens) {
    double[] rates = dbRatesForModel(provider, model);
    return (inputTokens / 1_000_000.0 * rates[0]) + (outputTokens / 1_000_000.0 * rates[1]);
  }

  /**
   * Compute the actual post-run cost for a completed pipeline run using real token counts and
   * Anthropic prompt-cache pricing multipliers:
   *
   * <ul>
   *   <li>Normal input tokens → 1.00 × input rate
   *   <li>Cache-write tokens → 1.25 × input rate (Anthropic charges extra for cache storage)
   *   <li>Cache-read tokens → 0.10 × input rate (90% discount on cached reads)
   *   <li>Output tokens → 1.00 × output rate
   * </ul>
   *
   * Uses the per-task provider/model for accurate pricing when tasks ran on different models.
   */
  public double computeCostForRun(String runId) {
    List<AgentTask> tasks = stateStore.getAllTasks(runId);
    double totalCost = 0.0;

    // Worker task costs (cache-aware)
    for (AgentTask task : tasks) {
      if (nvl(task.getInputTokens()) == 0 && nvl(task.getOutputTokens()) == 0) continue;

      // Use the provider/model recorded on the task for accurate per-model pricing.
      String provider =
          task.getProvider() != null ? task.getProvider() : props.getRoles().getWorker();
      String model = task.getModel() != null ? task.getModel() : defaultModel(provider);
      double[] rates = dbRatesForModel(provider, model);

      int cacheWrite = nvl(task.getCacheWriteTokens());
      int cacheRead = nvl(task.getCacheReadTokens());
      // normalIn is the portion of input tokens NOT going through cache writes
      int normalIn = Math.max(0, nvl(task.getInputTokens()) - cacheWrite);
      int out = nvl(task.getOutputTokens());

      totalCost += (normalIn / 1_000_000.0) * rates[0]; // 1.00x
      totalCost += (cacheWrite / 1_000_000.0) * rates[0] * CACHE_WRITE_COST_MULTIPLIER;
      totalCost += (cacheRead / 1_000_000.0) * rates[0] * CACHE_READ_COST_MULTIPLIER;
      totalCost += (out / 1_000_000.0) * rates[1]; // 1.00x
    }

    // Pipeline agent costs (planner, reviewer, compiler) — stored on the run since V19
    stateStore
        .findRun(runId)
        .ifPresent(
            run -> {
              // Helper: add agent cost to running total via array trick (lambda needs effectively
              // final)
            });
    totalCost += computeAgentCost(runId);

    log.info(
        "[CostEstimator] Actual cost for run {}: ${}", runId, String.format("%.6f", totalCost));
    return totalCost;
  }

  /** Computes cost for planner/reviewer/compiler/test-gen from per-agent token fields. */
  private double computeAgentCost(String runId) {
    double cost =
        stateStore
            .findRun(runId)
            .map(
                run -> {
                  double c = 0.0;
                  c +=
                      computeAgentTokenCost(
                          run.getPlannerInputTokens(),
                          run.getPlannerOutputTokens(),
                          run.getPlannerProvider(),
                          props.getRoles().getPlanner(),
                          run.getPlannerActualModel());
                  c +=
                      computeAgentTokenCost(
                          run.getReviewerInputTokens(),
                          run.getReviewerOutputTokens(),
                          run.getReviewerProvider(),
                          props.getRoles().getReviewer(),
                          run.getReviewerActualModel());
                  c +=
                      computeAgentTokenCost(
                          run.getCompilerInputTokens(),
                          run.getCompilerOutputTokens(),
                          run.getCompilerProvider(),
                          props.getRoles().getWorker(),
                          run.getCompilerActualModel());
                  return c;
                })
            .orElse(0.0);

    // Test gen pipeline costs (from TestPlan entity)
    cost +=
        testPlanRepository
            .findByRunId(runId)
            .map(
                tp -> {
                  double c = 0.0;
                  c +=
                      computeAgentTokenCost(
                          tp.getPlannerInputTokens(),
                          tp.getPlannerOutputTokens(),
                          tp.getPlannerProvider(),
                          "anthropic",
                          tp.getPlannerModel());
                  c +=
                      computeAgentTokenCost(
                          tp.getWorkerInputTokens(),
                          tp.getWorkerOutputTokens(),
                          tp.getWorkerProvider(),
                          "anthropic",
                          tp.getWorkerModel());
                  // Test report gen (fix suggestions) — uses LIGHTWEIGHT tier
                  String reportProv =
                      tp.getPlannerProvider() != null ? tp.getPlannerProvider() : "anthropic";
                  String reportModel = modelRegistry.lightweightModelFor(reportProv);
                  c +=
                      computeAgentTokenCost(
                          tp.getReportInputTokens(),
                          tp.getReportOutputTokens(),
                          reportProv,
                          reportProv,
                          reportModel);
                  return c;
                })
            .orElse(0.0);

    return cost;
  }

  /**
   * Computes cost for a single agent from its token counts, resolving provider/model with
   * fallbacks.
   */
  private double computeAgentTokenCost(
      Integer inputTokens,
      Integer outputTokens,
      String provider,
      String defaultProvider,
      String model) {
    int inTok = nvl(inputTokens);
    int outTok = nvl(outputTokens);
    if (inTok == 0 && outTok == 0) return 0.0;
    String prov = provider != null ? provider : defaultProvider;
    String resolvedModel = model != null ? model : defaultModel(prov);
    double[] rates = dbRatesForModel(prov, resolvedModel);
    return (inTok / 1_000_000.0) * rates[0] + (outTok / 1_000_000.0) * rates[1];
  }

  private int nvl(Integer v) {
    return v != null ? v : 0;
  }

  private double costUsd(int inputTokens, int outputTokens, int iterations, double[] rates) {
    return (inputTokens / 1_000_000.0) * rates[0] * iterations
        + (outputTokens / 1_000_000.0) * rates[1] * iterations;
  }

  /** Resolve rates from DB by provider, using default model for that provider. */
  private double[] dbRates(String provider) {
    return dbRatesForModel(provider, defaultModel(provider));
  }

  private double[] dbRatesForModel(String provider, String model) {
    return pricingRepo
        .findByIdProviderAndIdModel(provider, model)
        .map(p -> new double[] {p.getInputCostPer1m(), p.getOutputCostPer1m()})
        .orElseGet(() -> registryFallback(provider));
  }

  /** Falls back to pricing from model-matrix config when DB row is missing. */
  private double[] registryFallback(String provider) {
    String fallbackModel = modelRegistry.workhouseModelFor(provider);
    double[] pricing = modelRegistry.pricingFor(fallbackModel);
    if (pricing != null && pricing.length == 2) {
      return pricing;
    }
    log.warn(
        "[CostEstimator] No pricing found for provider={}, fallback model={}",
        provider,
        fallbackModel);
    return new double[] {3.00, 15.00}; // ultimate safety net
  }

  private String defaultModel(String provider) {
    return modelRegistry.workhouseModelFor(provider);
  }

  /** Returns [lowIters, highIters] for the given complexity. */
  private int[] iterRange(String complexity) {
    return switch (complexity) {
      case "LOW" -> new int[] {LOW_ITER_LOW, LOW_ITER_HIGH};
      case "HIGH" -> new int[] {HIGH_ITER_LOW, HIGH_ITER_HIGH};
      default -> new int[] {MEDIUM_ITER_LOW, MEDIUM_ITER_HIGH};
    };
  }

  private int complexityInputTokens(String complexity) {
    return switch (complexity) {
      case "LOW" -> LOW_IN_TOKENS;
      case "HIGH" -> HIGH_IN_TOKENS;
      default -> MEDIUM_IN_TOKENS;
    };
  }

  private int complexityOutputTokens(String complexity) {
    return switch (complexity) {
      case "LOW" -> LOW_OUT_TOKENS;
      case "HIGH" -> HIGH_OUT_TOKENS;
      default -> MEDIUM_OUT_TOKENS;
    };
  }
}

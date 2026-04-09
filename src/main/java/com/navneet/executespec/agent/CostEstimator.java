package com.navneet.executespec.agent;
import com.navneet.executespec.router.ModelRegistry;

import com.navneet.executespec.agent.model.TaskDefinition;
import com.navneet.executespec.agent.model.TaskPlan;
import com.navneet.executespec.config.AgentModelConfig;
import com.navneet.executespec.config.AgentProperties;
import com.navneet.executespec.pricing.ModelPricingRepository;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.router.ModelResolver;
import com.navneet.executespec.router.ResolvedModel;
import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.state.entity.AgentTask;
import com.navneet.executespec.testgen.repository.TestPlanRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Estimates pipeline cost before plan approval.
 * Pricing is DB-backed (model_pricing table), editable via UI/REST.
 *
 * Low estimate  = 1 iteration per worker
 * High estimate = 3 iterations per worker (accounts for retries / compaction)
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

    // Anthropic prompt-cache pricing: cache writes cost 25% more than normal input.
    private static final double CACHE_WRITE_COST_MULTIPLIER = 1.25;

    // Anthropic prompt-cache pricing: cache reads cost only 10% of normal input (90% discount).
    private static final double CACHE_READ_COST_MULTIPLIER = 0.10;

    // Input token estimates per iteration by complexity.
    // Based on typical context window usage at compaction thresholds (45%/60%/80% of 200K limit).
    private static final int LOW_IN_TOKENS    = 15_000;
    private static final int MEDIUM_IN_TOKENS = 40_000;
    private static final int HIGH_IN_TOKENS   = 80_000;

    // Output token estimates per iteration by complexity.
    private static final int LOW_OUT_TOKENS    = 4_000;
    private static final int MEDIUM_OUT_TOKENS = 8_000;
    private static final int HIGH_OUT_TOKENS   = 16_000;

    public CostBreakdown estimate(TaskPlan plan) {
        return estimate(plan, LlmProvider.ANTHROPIC);
    }

    public CostBreakdown estimate(TaskPlan plan, LlmProvider provider) {
        if (plan == null || plan.getTasks() == null) {
            return new CostBreakdown(0, 0);
        }

        ResolvedModel workerModel   = modelResolver.resolve("WorkerAgent", provider);
        ResolvedModel plannerModel  = modelResolver.resolve("PlannerAgent", provider);
        ResolvedModel reviewerModel = modelResolver.resolve("ReviewerAgentV2", provider);

        double[] workerRates   = dbRatesForModel(workerModel.providerName(), workerModel.getModel());
        double[] plannerRates  = dbRatesForModel(plannerModel.providerName(), plannerModel.getModel());
        double[] reviewerRates = dbRatesForModel(reviewerModel.providerName(), reviewerModel.getModel());

        double totalLow  = 0;
        double totalHigh = 0;

        for (TaskDefinition task : plan.getTasks()) {
            String complexity = task.getComplexity() != null ? task.getComplexity().toUpperCase() : "MEDIUM";
            int inTok  = complexityInputTokens(complexity);
            int outTok = tokenBudgetMonitor.maxOutputTokens(null, complexity);

            // Low = 1 iteration, High = 3 iterations
            totalLow  += costUsd(inTok, outTok, 1, workerRates);
            totalHigh += costUsd(inTok, outTok, 3, workerRates);
        }

        // Add fixed planner and reviewer costs
        int plannerOut  = tokenBudgetMonitor.maxOutputTokens(null, "HIGH");
        int reviewerOut = tokenBudgetMonitor.maxOutputTokens(null, "MEDIUM");
        totalLow  += costUsd(MEDIUM_IN_TOKENS, plannerOut, 1, plannerRates);
        totalHigh += costUsd(MEDIUM_IN_TOKENS, plannerOut, 1, plannerRates);
        totalLow  += costUsd(MEDIUM_IN_TOKENS + REVIEWER_CONTEXT_BUFFER, reviewerOut, 1, reviewerRates);
        totalHigh += costUsd(MEDIUM_IN_TOKENS + REVIEWER_CONTEXT_BUFFER, reviewerOut, 2, reviewerRates);

        // Apply 50% buffer to high estimate to account for retries, failover,
        // and cache-write overhead that the simple model doesn't capture.
        totalHigh *= HIGH_ESTIMATE_MULTIPLIER;

        log.debug("[CostEstimator] {} tasks → low=${}, high=${} (provider={})", plan.getTasks().size(),
            String.format("%.3f", totalLow), String.format("%.3f", totalHigh), provider);

        return new CostBreakdown(totalLow, totalHigh);
    }

    /**
     * Estimate cost with credit calculation for the plan approval gate.
     * Uses the configurable estimation buffer (default 10%) instead of the
     * internal 50% HIGH_ESTIMATE_MULTIPLIER.
     */
    public RunCostEstimate estimateWithCredits(TaskPlan plan, LlmProvider provider,
                                                AgentProperties.Cost costConfig) {
        return estimateWithCredits(plan, provider, costConfig, null);
    }

    /**
     * Estimate cost with credit calculation for both real-time and batch paths.
     * @param userTier nullable — used to determine batch availability (FREE→no, PRO→2+ credits, ENTERPRISE→always)
     */
    public RunCostEstimate estimateWithCredits(TaskPlan plan, LlmProvider provider,
                                                AgentProperties.Cost costConfig, String userTier) {
        if (plan == null || plan.getTasks() == null) {
            return new RunCostEstimate(0, 1, costConfig.getCreditMultiplierUsd(),
                    0, 1, costConfig.getCreditMultiplierUsd(),
                    provider.name(), false, 0,
                    List.of(), 0, 0, 0);
        }

        ResolvedModel workerModel   = modelResolver.resolve("WorkerAgent", provider);
        ResolvedModel plannerModel  = modelResolver.resolve("PlannerAgent", provider);
        ResolvedModel reviewerModel = modelResolver.resolve("ReviewerAgentV2", provider);

        double[] workerRates   = dbRatesForModel(workerModel.providerName(), workerModel.getModel());
        double[] plannerRates  = dbRatesForModel(plannerModel.providerName(), plannerModel.getModel());
        double[] reviewerRates = dbRatesForModel(reviewerModel.providerName(), reviewerModel.getModel());

        List<RunCostEstimate.TaskCostEstimate> taskEstimates = new ArrayList<>();
        double totalWorkerCost = 0;

        for (TaskDefinition task : plan.getTasks()) {
            String complexity = task.getComplexity() != null ? task.getComplexity().toUpperCase() : "MEDIUM";
            int inTok  = complexityInputTokens(complexity);
            int outTok = tokenBudgetMonitor.maxOutputTokens(null, complexity);
            int iters  = "LOW".equals(complexity) ? 2 : "HIGH".equals(complexity) ? 4 : 3;

            double taskCost = costUsd(inTok, outTok, iters, workerRates);
            totalWorkerCost += taskCost;

            taskEstimates.add(new RunCostEstimate.TaskCostEstimate(
                    task.getTaskId(), complexity, taskCost, iters));
        }

        // Planner cost (1 call)
        int plannerOut = tokenBudgetMonitor.maxOutputTokens(null, "HIGH");
        double planningCost = costUsd(MEDIUM_IN_TOKENS, plannerOut, 1, plannerRates);

        // Reviewer cost (1 call per task, uses lightweight/planning tier)
        int reviewerOut = tokenBudgetMonitor.maxOutputTokens(null, "MEDIUM");
        double reviewCost = costUsd(MEDIUM_IN_TOKENS + REVIEWER_CONTEXT_BUFFER, reviewerOut,
                plan.getTasks().size(), reviewerRates);

        // Compiler cost (1 call)
        double compilerCost = costUsd(MEDIUM_IN_TOKENS, reviewerOut, 1, reviewerRates);

        double rawTotal = totalWorkerCost + planningCost + reviewCost + compilerCost;
        double bufferedTotal = rawTotal * costConfig.getEstimationBuffer();

        double multiplier = costConfig.getCreditMultiplierUsd();
        int credits = Math.max(1, (int) Math.ceil(bufferedTotal / multiplier));
        double hardCap = credits * multiplier;

        // Batch path — universal 50% discount
        double batchDiscount = costConfig.getBatch() != null ? costConfig.getBatch().getDiscount() : 0.50;
        double batchBuffered = bufferedTotal * (1.0 - batchDiscount);
        int batchCredits = Math.max(1, (int) Math.ceil(batchBuffered / multiplier));
        double batchHardCap = batchCredits * multiplier;

        // Batch availability depends on tier
        boolean batchAvailable;
        String tier = userTier != null ? userTier.toUpperCase() : "FREE";
        boolean batchEnabled = costConfig.getBatch() != null && costConfig.getBatch().isEnabled();
        if (!batchEnabled) {
            batchAvailable = false;
        } else {
            batchAvailable = switch (tier) {
                case "FREE" -> false;
                case "PRO" -> credits >= 2;
                case "MAX", "ENTERPRISE" -> true;
                default -> credits >= 2;
            };
        }

        int creditSavings = batchAvailable ? credits - batchCredits : 0;

        log.info("[CostEstimator] {} tasks → est=${} (buffered), credits={}, cap=${}, batch={} batchCredits={} (provider={})",
                plan.getTasks().size(), String.format("%.2f", bufferedTotal), credits,
                String.format("%.2f", hardCap), batchAvailable, batchCredits, provider);

        return new RunCostEstimate(bufferedTotal, credits, hardCap,
                batchBuffered, batchCredits, batchHardCap,
                provider.name(), batchAvailable, creditSavings,
                taskEstimates, planningCost, reviewCost, compilerCost);
    }

    /** Estimate cost for a single task in feasibility mode. Returns [min, max]. */
    public double[] estimateFromTask(TaskDefinition task, String provider, String model) {
        if (task.getEstimatedIterations() == null) return new double[]{0, 0};

        double[] rates = dbRatesForModel(provider, model);
        int tokPerIter = task.getEstimatedTokensPerIteration() != null
                ? task.getEstimatedTokensPerIteration() : MEDIUM_IN_TOKENS;

        double min = costUsd(tokPerIter, MEDIUM_OUT_TOKENS, task.getEstimatedIterations().min(), rates);
        double max = costUsd(tokPerIter, MEDIUM_OUT_TOKENS, task.getEstimatedIterations().max(), rates);
        return new double[]{min, max};
    }

    /** Estimate fixed overhead cost for planner + reviewer. Returns [min, max]. */
    public double[] estimateOverhead(AgentModelConfig modelConfig) {
        double[] plannerRates  = dbRates(props.getRoles().getPlanner());
        double[] reviewerRates = dbRates(props.getRoles().getReviewer());

        double min = costUsd(MEDIUM_IN_TOKENS, MEDIUM_OUT_TOKENS, 1, plannerRates)
                   + costUsd(MEDIUM_IN_TOKENS + REVIEWER_CONTEXT_BUFFER, MEDIUM_OUT_TOKENS, 1, reviewerRates);
        double max = costUsd(MEDIUM_IN_TOKENS, MEDIUM_OUT_TOKENS, 1, plannerRates)
                   + costUsd(MEDIUM_IN_TOKENS + REVIEWER_CONTEXT_BUFFER, MEDIUM_OUT_TOKENS, 2, reviewerRates);
        return new double[]{min, max};
    }

    /** Compute actual cost from real token counts. */
    public double computeCost(String provider, String model, long inputTokens, long outputTokens) {
        double[] rates = dbRatesForModel(provider, model);
        return (inputTokens / 1_000_000.0 * rates[0]) + (outputTokens / 1_000_000.0 * rates[1]);
    }

    /**
     * Compute the actual post-run cost for a completed pipeline run using real token counts
     * and Anthropic prompt-cache pricing multipliers:
     * <ul>
     *   <li>Normal input tokens  → 1.00 × input rate</li>
     *   <li>Cache-write tokens   → 1.25 × input rate (Anthropic charges extra for cache storage)</li>
     *   <li>Cache-read tokens    → 0.10 × input rate (90% discount on cached reads)</li>
     *   <li>Output tokens        → 1.00 × output rate</li>
     * </ul>
     * Uses the per-task provider/model for accurate pricing when tasks ran on different models.
     */
    public double computeCostForRun(String runId) {
        List<AgentTask> tasks = stateStore.getAllTasks(runId);
        double totalCost = 0.0;

        // Worker task costs (cache-aware)
        for (AgentTask task : tasks) {
            if (nvl(task.getInputTokens()) == 0 && nvl(task.getOutputTokens()) == 0) continue;

            // Use the provider/model recorded on the task for accurate per-model pricing.
            String provider = task.getProvider() != null ? task.getProvider() : props.getRoles().getWorker();
            String model    = task.getModel()    != null ? task.getModel()    : defaultModel(provider);
            double[] rates  = dbRatesForModel(provider, model);

            int cacheWrite = nvl(task.getCacheWriteTokens());
            int cacheRead  = nvl(task.getCacheReadTokens());
            // normalIn is the portion of input tokens NOT going through cache writes
            int normalIn   = Math.max(0, nvl(task.getInputTokens()) - cacheWrite);
            int out        = nvl(task.getOutputTokens());

            totalCost += (normalIn   / 1_000_000.0) * rates[0];         // 1.00x
            totalCost += (cacheWrite / 1_000_000.0) * rates[0] * CACHE_WRITE_COST_MULTIPLIER;
            totalCost += (cacheRead  / 1_000_000.0) * rates[0] * CACHE_READ_COST_MULTIPLIER;
            totalCost += (out        / 1_000_000.0) * rates[1];          // 1.00x
        }

        // Pipeline agent costs (planner, reviewer, compiler) — stored on the run since V19
        stateStore.findRun(runId).ifPresent(run -> {
            // Helper: add agent cost to running total via array trick (lambda needs effectively final)
        });
        totalCost += computeAgentCost(runId);

        log.info("[CostEstimator] Actual cost for run {}: ${}", runId, String.format("%.6f", totalCost));
        return totalCost;
    }

    /** Computes cost for planner/reviewer/compiler/test-gen from per-agent token fields. */
    private double computeAgentCost(String runId) {
        double cost = stateStore.findRun(runId).map(run -> {
            double c = 0.0;
            c += computeAgentTokenCost(run.getPlannerInputTokens(), run.getPlannerOutputTokens(),
                    run.getPlannerProvider(), props.getRoles().getPlanner(),
                    run.getPlannerActualModel());
            c += computeAgentTokenCost(run.getReviewerInputTokens(), run.getReviewerOutputTokens(),
                    run.getReviewerProvider(), props.getRoles().getReviewer(),
                    run.getReviewerActualModel());
            c += computeAgentTokenCost(run.getCompilerInputTokens(), run.getCompilerOutputTokens(),
                    run.getCompilerProvider(), props.getRoles().getWorker(),
                    run.getCompilerActualModel());
            return c;
        }).orElse(0.0);

        // Test gen pipeline costs (from TestPlan entity)
        cost += testPlanRepository.findByRunId(runId).map(tp -> {
            double c = 0.0;
            c += computeAgentTokenCost(tp.getPlannerInputTokens(), tp.getPlannerOutputTokens(),
                    tp.getPlannerProvider(), "anthropic",
                    tp.getPlannerModel());
            c += computeAgentTokenCost(tp.getWorkerInputTokens(), tp.getWorkerOutputTokens(),
                    tp.getWorkerProvider(), "anthropic",
                    tp.getWorkerModel());
            // Test report gen (fix suggestions) — uses LIGHTWEIGHT tier
            String reportProv = tp.getPlannerProvider() != null ? tp.getPlannerProvider() : "anthropic";
            String reportModel = modelRegistry.lightweightModelFor(reportProv);
            c += computeAgentTokenCost(tp.getReportInputTokens(), tp.getReportOutputTokens(),
                    reportProv, reportProv, reportModel);
            return c;
        }).orElse(0.0);

        return cost;
    }

    /**
     * Computes cost for a single agent from its token counts, resolving provider/model with fallbacks.
     */
    private double computeAgentTokenCost(Integer inputTokens, Integer outputTokens,
                                         String provider, String defaultProvider,
                                         String model) {
        int inTok = nvl(inputTokens);
        int outTok = nvl(outputTokens);
        if (inTok == 0 && outTok == 0) return 0.0;
        String prov = provider != null ? provider : defaultProvider;
        String resolvedModel = model != null ? model : defaultModel(prov);
        double[] rates = dbRatesForModel(prov, resolvedModel);
        return (inTok / 1_000_000.0) * rates[0] + (outTok / 1_000_000.0) * rates[1];
    }

    private int nvl(Integer v) { return v != null ? v : 0; }

    private double costUsd(int inputTokens, int outputTokens, int iterations, double[] rates) {
        return (inputTokens  / 1_000_000.0) * rates[0] * iterations
             + (outputTokens / 1_000_000.0) * rates[1] * iterations;
    }

    /** Resolve rates from DB by provider, using default model for that provider. */
    private double[] dbRates(String provider) {
        return dbRatesForModel(provider, defaultModel(provider));
    }

    private double[] dbRatesForModel(String provider, String model) {
        return pricingRepo.findByIdProviderAndIdModel(provider, model)
                .map(p -> new double[]{p.getInputCostPer1m(), p.getOutputCostPer1m()})
                .orElseGet(() -> registryFallback(provider));
    }

    /** Falls back to pricing from model-matrix config when DB row is missing. */
    private double[] registryFallback(String provider) {
        String fallbackModel = modelRegistry.workhouseModelFor(provider);
        double[] pricing = modelRegistry.pricingFor(fallbackModel);
        if (pricing != null && pricing.length == 2) {
            return pricing;
        }
        log.warn("[CostEstimator] No pricing found for provider={}, fallback model={}", provider, fallbackModel);
        return new double[]{3.00, 15.00}; // ultimate safety net
    }

    private String defaultModel(String provider) {
        return modelRegistry.workhouseModelFor(provider);
    }

    private int complexityInputTokens(String complexity) {
        return switch (complexity) {
            case "LOW"  -> LOW_IN_TOKENS;
            case "HIGH" -> HIGH_IN_TOKENS;
            default     -> MEDIUM_IN_TOKENS;
        };
    }

    private int complexityOutputTokens(String complexity) {
        return switch (complexity) {
            case "LOW"  -> LOW_OUT_TOKENS;
            case "HIGH" -> HIGH_OUT_TOKENS;
            default     -> MEDIUM_OUT_TOKENS;
        };
    }
}

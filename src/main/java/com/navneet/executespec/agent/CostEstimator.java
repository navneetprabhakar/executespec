package com.navneet.executespec.agent;
import com.navneet.executespec.config.ModelConstants;

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
    private final StateStore stateStore;
    private final TestPlanRepository testPlanRepository;

    public record CostBreakdown(double lowUsd, double highUsd) {
        public String formatted() {
            return String.format("Estimated cost: $%.3f – $%.3f USD", lowUsd, highUsd);
        }
    }

    // Typical tokens per iteration by complexity (conservative estimates)
    private static final int LOW_IN_TOKENS    = 10_000,  LOW_OUT_TOKENS    = 2_048;
    private static final int MEDIUM_IN_TOKENS = 25_000,  MEDIUM_OUT_TOKENS = 4_096;
    private static final int HIGH_IN_TOKENS   = 60_000,  HIGH_OUT_TOKENS   = 8_192;

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
            int outTok = complexityOutputTokens(complexity);

            // Low = 1 iteration, High = 3 iterations
            totalLow  += costUsd(inTok, outTok, 1, workerRates);
            totalHigh += costUsd(inTok, outTok, 3, workerRates);
        }

        // Add fixed planner and reviewer costs (~1 call each, MEDIUM size)
        totalLow  += costUsd(MEDIUM_IN_TOKENS, MEDIUM_OUT_TOKENS, 1, plannerRates);
        totalHigh += costUsd(MEDIUM_IN_TOKENS, MEDIUM_OUT_TOKENS, 1, plannerRates);
        totalLow  += costUsd(MEDIUM_IN_TOKENS + 20_000, MEDIUM_OUT_TOKENS, 1, reviewerRates);
        totalHigh += costUsd(MEDIUM_IN_TOKENS + 20_000, MEDIUM_OUT_TOKENS, 2, reviewerRates);

        log.debug("[CostEstimator] {} tasks → low=${}, high=${} (provider={})", plan.getTasks().size(),
            String.format("%.3f", totalLow), String.format("%.3f", totalHigh), provider);

        return new CostBreakdown(totalLow, totalHigh);
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
                   + costUsd(MEDIUM_IN_TOKENS + 20_000, MEDIUM_OUT_TOKENS, 1, reviewerRates);
        double max = costUsd(MEDIUM_IN_TOKENS, MEDIUM_OUT_TOKENS, 1, plannerRates)
                   + costUsd(MEDIUM_IN_TOKENS + 20_000, MEDIUM_OUT_TOKENS, 2, reviewerRates);
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
            totalCost += (cacheWrite / 1_000_000.0) * rates[0] * 1.25;  // 1.25x
            totalCost += (cacheRead  / 1_000_000.0) * rates[0] * 0.10;  // 0.10x
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
            // Planner
            int pIn = nvl(run.getPlannerInputTokens()), pOut = nvl(run.getPlannerOutputTokens());
            if (pIn > 0 || pOut > 0) {
                String prov = run.getPlannerProvider() != null ? run.getPlannerProvider() : props.getRoles().getPlanner();
                String model = run.getPlannerActualModel() != null ? run.getPlannerActualModel() : defaultModel(prov);
                double[] rates = dbRatesForModel(prov, model);
                c += (pIn / 1_000_000.0) * rates[0] + (pOut / 1_000_000.0) * rates[1];
            }
            // Reviewer
            int rIn = nvl(run.getReviewerInputTokens()), rOut = nvl(run.getReviewerOutputTokens());
            if (rIn > 0 || rOut > 0) {
                String prov = run.getReviewerProvider() != null ? run.getReviewerProvider() : props.getRoles().getReviewer();
                String model = run.getReviewerActualModel() != null ? run.getReviewerActualModel() : defaultModel(prov);
                double[] rates = dbRatesForModel(prov, model);
                c += (rIn / 1_000_000.0) * rates[0] + (rOut / 1_000_000.0) * rates[1];
            }
            // Compiler
            int cIn = nvl(run.getCompilerInputTokens()), cOut = nvl(run.getCompilerOutputTokens());
            if (cIn > 0 || cOut > 0) {
                String prov = run.getCompilerProvider() != null ? run.getCompilerProvider() : props.getRoles().getWorker();
                String model = run.getCompilerActualModel() != null ? run.getCompilerActualModel() : defaultModel(prov);
                double[] rates = dbRatesForModel(prov, model);
                c += (cIn / 1_000_000.0) * rates[0] + (cOut / 1_000_000.0) * rates[1];
            }
            return c;
        }).orElse(0.0);

        // Test gen pipeline costs (from TestPlan entity)
        cost += testPlanRepository.findByRunId(runId).map(tp -> {
            double c = 0.0;
            // Test planner
            int tpIn = nvl(tp.getPlannerInputTokens()), tpOut = nvl(tp.getPlannerOutputTokens());
            if (tpIn > 0 || tpOut > 0) {
                String prov = tp.getPlannerProvider() != null ? tp.getPlannerProvider() : "anthropic";
                String model = tp.getPlannerModel() != null ? tp.getPlannerModel() : ModelConstants.SONNET;
                double[] rates = dbRatesForModel(prov, model);
                c += (tpIn / 1_000_000.0) * rates[0] + (tpOut / 1_000_000.0) * rates[1];
            }
            // Test worker
            int twIn = nvl(tp.getWorkerInputTokens()), twOut = nvl(tp.getWorkerOutputTokens());
            if (twIn > 0 || twOut > 0) {
                String prov = tp.getWorkerProvider() != null ? tp.getWorkerProvider() : "anthropic";
                String model = tp.getWorkerModel() != null ? tp.getWorkerModel() : ModelConstants.SONNET;
                double[] rates = dbRatesForModel(prov, model);
                c += (twIn / 1_000_000.0) * rates[0] + (twOut / 1_000_000.0) * rates[1];
            }
            // Test report gen (fix suggestions)
            int trIn = nvl(tp.getReportInputTokens()), trOut = nvl(tp.getReportOutputTokens());
            if (trIn > 0 || trOut > 0) {
                String prov = tp.getPlannerProvider() != null ? tp.getPlannerProvider() : "anthropic";
                String model = ModelConstants.HAIKU; // fix suggestions use FAST tier
                double[] rates = dbRatesForModel(prov, model);
                c += (trIn / 1_000_000.0) * rates[0] + (trOut / 1_000_000.0) * rates[1];
            }
            return c;
        }).orElse(0.0);

        return cost;
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
                .orElseGet(() -> hardcodedFallback(provider));
    }

    private double[] hardcodedFallback(String provider) {
        return switch (provider.toLowerCase()) {
            case "openai"  -> new double[]{0.15, 0.60};
            default        -> new double[]{0.80, 4.00};  // anthropic haiku
        };
    }

    private String defaultModel(String provider) {
        return switch (provider.toLowerCase()) {
            case "openai"  -> ModelConstants.GPT5_4_MINI;
            default        -> ModelConstants.HAIKU;
        };
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

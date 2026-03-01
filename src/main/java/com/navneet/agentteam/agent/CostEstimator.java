package com.navneet.agentteam.agent;

import com.navneet.agentteam.agent.model.TaskDefinition;
import com.navneet.agentteam.agent.model.TaskPlan;
import com.navneet.agentteam.config.AgentModelConfig;
import com.navneet.agentteam.config.AgentProperties;
import com.navneet.agentteam.pricing.ModelPricingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

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
        if (plan == null || plan.getTasks() == null) {
            return new CostBreakdown(0, 0);
        }

        double[] workerRates = dbRates(props.getRoles().getWorker());
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
        double[] plannerRates  = dbRates(props.getRoles().getPlanner());
        double[] reviewerRates = dbRates(props.getRoles().getReviewer());

        totalLow  += costUsd(MEDIUM_IN_TOKENS, MEDIUM_OUT_TOKENS, 1, plannerRates);
        totalHigh += costUsd(MEDIUM_IN_TOKENS, MEDIUM_OUT_TOKENS, 1, plannerRates);
        totalLow  += costUsd(MEDIUM_IN_TOKENS + 20_000, MEDIUM_OUT_TOKENS, 1, reviewerRates);
        totalHigh += costUsd(MEDIUM_IN_TOKENS + 20_000, MEDIUM_OUT_TOKENS, 2, reviewerRates);

        log.debug("[CostEstimator] {} tasks → low=${}, high=${}", plan.getTasks().size(),
            String.format("%.3f", totalLow), String.format("%.3f", totalHigh));

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
            case "gemini"  -> new double[]{0.10, 0.40};
            default        -> new double[]{0.80, 4.00};  // anthropic haiku
        };
    }

    private String defaultModel(String provider) {
        return switch (provider.toLowerCase()) {
            case "openai"  -> "gpt-4o-mini";
            case "gemini"  -> "gemini-2.0-flash";
            default        -> "claude-sonnet-4-5-20250929";
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

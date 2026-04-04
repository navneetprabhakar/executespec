package com.navneet.executespec.eval;

import com.navneet.executespec.router.AgentTier;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Complete evaluation report containing all model scores across all tiers.
 *
 * @param evaluationId   unique ID for this evaluation run
 * @param timestamp      when the evaluation completed
 * @param durationMs     total wall-clock time
 * @param modelResults   per-model results grouped by provider:model
 * @param recommendations per-tier recommended model with reasoning
 */
public record EvalReport(
        String evaluationId,
        Instant timestamp,
        long durationMs,
        List<ModelReport> modelResults,
        Map<AgentTier, Recommendation> recommendations,
        List<Disqualification> disqualifications
) {

    /**
     * Per-model evaluation results across all tiers.
     */
    public record ModelReport(
            String provider,
            String model,
            Map<AgentTier, TierScore> tierScores,
            AgentTier bestFitTier
    ) {}

    /**
     * Aggregated scores for a model within a specific tier.
     */
    public record TierScore(
            double accuracy,
            double speed,
            double cost,
            double overall,
            String reasoning,
            List<TestCaseResult> testResults
    ) {}

    /**
     * Individual test case result within a tier evaluation.
     */
    public record TestCaseResult(
            String testCaseId,
            double accuracy,
            double speed,
            double cost,
            double overall,
            long latencyMs,
            double costUsd,
            boolean passed,
            List<PatternResult> patternResults
    ) {}

    /**
     * Per-pattern match result for detailed debugging.
     */
    public record PatternResult(
            String patternLabel,
            boolean matched
    ) {}

    /**
     * Per-tier recommendation with the winning model and reasoning.
     */
    public record Recommendation(
            String provider,
            String model,
            double score,
            String reasoning
    ) {}

    /**
     * A model disqualified from a tier due to minimum accuracy/pass-rate requirements.
     */
    public record Disqualification(
            String provider,
            String model,
            AgentTier tier,
            String reason
    ) {}
}

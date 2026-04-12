package com.navneet.executespec.v2.reviewer;

import com.navneet.executespec.v2.tool.ExecutionContext;
import lombok.Builder;

/**
 * Input to {@link ReviewerAgentV3#review(ReviewerRequestV2)}.
 *
 * @param provider          LLM provider for review calls
 * @param model             LLM model for review calls
 * @param context           execution context (workspace + ids); the reviewer substitutes
 *                          its own task IDs for each review pass
 * @param buildCommand      optional build command override — the reviewer runs this after
 *                          fixes to verify nothing is broken. If null, BuildProjectTool
 *                          falls back to framework inference.
 * @param maxIterationsPerPass  override for worker iteration cap per review pass
 *                              (null = default 30, smaller than worker's 40 since review
 *                              passes are typically shorter)
 * @param skipWiringPass    if true, only run the general review pass
 */
@Builder
public record ReviewerRequestV2(
        String provider,
        String model,
        ExecutionContext context,
        String buildCommand,
        Integer maxIterationsPerPass,
        Boolean skipWiringPass) {

    public ReviewerRequestV2 {
        if (provider == null || provider.isBlank()) throw new IllegalArgumentException("provider required");
        if (model == null || model.isBlank()) throw new IllegalArgumentException("model required");
        if (context == null) throw new IllegalArgumentException("context required");
        if (skipWiringPass == null) skipWiringPass = false;
    }
}

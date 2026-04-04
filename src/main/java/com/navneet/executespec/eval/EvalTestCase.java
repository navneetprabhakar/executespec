package com.navneet.executespec.eval;

import com.navneet.executespec.router.AgentTier;

import java.util.List;
import java.util.regex.Pattern;

/**
 * A single evaluation test case that scores an LLM model on a specific tier capability.
 *
 * @param id              unique test case ID (e.g. "planning-plan-generation")
 * @param tier            the agent tier this test targets
 * @param description     human-readable description of what the test evaluates
 * @param systemPrompt    optional system prompt (null if not needed)
 * @param userPrompt      the user prompt to send to the model
 * @param validationPatterns  regex patterns the response must match for accuracy scoring
 * @param requiredJsonKeys    JSON keys required in the response (for structural validation)
 * @param latencyCeilingMs    max acceptable latency for this tier (used for speed scoring)
 * @param maxResponseLength   max acceptable response length in chars (0 = no limit)
 */
public record EvalTestCase(
        String id,
        AgentTier tier,
        String description,
        String systemPrompt,
        String userPrompt,
        List<Pattern> validationPatterns,
        List<String> patternLabels,
        List<String> requiredJsonKeys,
        long latencyCeilingMs,
        int maxResponseLength
) {
    /**
     * Convenience constructor without pattern labels (auto-generates P1, P2, ...).
     */
    public EvalTestCase(String id, AgentTier tier, String description, String systemPrompt,
                        String userPrompt, List<Pattern> validationPatterns,
                        List<String> requiredJsonKeys, long latencyCeilingMs, int maxResponseLength) {
        this(id, tier, description, systemPrompt, userPrompt, validationPatterns,
                generateLabels(validationPatterns.size()), requiredJsonKeys, latencyCeilingMs, maxResponseLength);
    }

    private static List<String> generateLabels(int count) {
        List<String> labels = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) labels.add("P" + (i + 1));
        return labels;
    }
}

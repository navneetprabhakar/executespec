package com.navneet.executespec.router;

import lombok.Builder;
import lombok.Data;

/**
 * Result of resolving a model via {@link ModelResolver}.
 * Contains the user's requested provider, the actual provider (may differ on failover),
 * the concrete model string, and the agent tier that drove the selection.
 */
@Data
@Builder
public class ResolvedModel {
    private LlmProvider requestedProvider;
    private LlmProvider actualProvider;
    private String model;
    private AgentTier agentTier;
    private boolean fallback;

    /**
     * V2-canonical provider string for {@code LlmRouterV2.dispatch}:
     * {@code anthropic | openai | gemini}. Note the enum value
     * {@link LlmProvider#GOOGLE} maps to the V2 string {@code "gemini"} —
     * {@code .name().toLowerCase()} would emit "google" and
     * {@code LlmRouterV2} rejects it with "Unknown provider: google".
     * Always call this instead of {@code getActualProvider().name().toLowerCase()}.
     */
    public String providerName() {
        // V2-canonical provider string: "anthropic" | "openai" | "gemini".
        // Enum values align 1:1 so simple lowercase suffices.
        return actualProvider == null ? "openai" : actualProvider.name().toLowerCase();
    }
}

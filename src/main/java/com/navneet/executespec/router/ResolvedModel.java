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

    /** Provider name in lowercase for LlmRouter calls (e.g. "anthropic", "google", "openai"). */
    public String providerName() {
        return actualProvider.name().toLowerCase();
    }
}

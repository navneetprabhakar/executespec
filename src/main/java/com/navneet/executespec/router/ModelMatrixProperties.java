package com.navneet.executespec.router;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Map;

/**
 * Binds to {@code model-matrix.*} in application.yml.
 *
 * <p>Defines the model matrix (provider × agent tier → model string),
 * the agent-to-tier mapping, and the failover order per provider.
 */
@Data
@ConfigurationProperties(prefix = "model-matrix")
public class ModelMatrixProperties {

    /** Provider → tier models. Key: "anthropic", "google", "openai". */
    private Map<String, ProviderModels> providers;

    /** Agent class simple name → AgentTier. E.g. "WorkerAgent" → WORKHORSE. */
    private Map<String, AgentTier> agentTierMap;

    /** Provider → ordered failover providers. Key: provider name, Value: fallback list. */
    private Map<String, List<String>> failoverOrder;

    @Data
    public static class ProviderModels {
        private String planning;
        private String workhorse;
        private String lightweight;
    }
}

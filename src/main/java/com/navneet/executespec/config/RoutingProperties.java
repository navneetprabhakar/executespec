package com.navneet.executespec.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "executespec.routing")
@Data
public class RoutingProperties {

    /**
     * Weighted distribution of LLM calls across providers.
     * Keys: "anthropic", "google", "openai". Values: relative weights.
     * Example: {anthropic: 4, google: 3, openai: 3} → 40/30/30% distribution.
     * Default: all traffic to Anthropic (backward-compatible).
     */
    private Map<String, Integer> providerWeights = new LinkedHashMap<>(Map.of(
            "anthropic", 4,
            "google", 3,
            "openai", 3
    ));
}

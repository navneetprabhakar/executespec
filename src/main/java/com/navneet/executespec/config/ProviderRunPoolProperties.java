package com.navneet.executespec.config;

import com.navneet.executespec.router.LlmProvider;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Configuration properties for per-provider run pools.
 *
 * <p>Bound from {@code agent.provider-pools} in application.yml.
 * Each provider (ANTHROPIC, GOOGLE, OPENAI) gets its own concurrency limit
 * and bounded queue capacity, providing isolation between providers.
 *
 * <p>Example YAML:
 * <pre>
 * agent:
 *   provider-pools:
 *     default-concurrency: 3
 *     default-queue-capacity: 10
 *     providers:
 *       ANTHROPIC:
 *         concurrency: 3
 *         queue-capacity: 15
 *       GOOGLE:
 *         concurrency: 3
 *         queue-capacity: 10
 *       OPENAI:
 *         concurrency: 3
 *         queue-capacity: 10
 * </pre>
 */
@Component
@ConfigurationProperties(prefix = "agent.provider-pools")
@Data
public class ProviderRunPoolProperties {

    private int defaultConcurrency = 3;
    private int defaultQueueCapacity = 10;
    private Map<String, ProviderPoolConfig> providers = new HashMap<>();

    @Data
    public static class ProviderPoolConfig {
        private int concurrency = 3;
        private int queueCapacity = 10;
    }

    /**
     * Returns the pool config for the given provider, falling back to defaults
     * if no specific config exists.
     */
    public ProviderPoolConfig forProvider(LlmProvider provider) {
        return providers.getOrDefault(provider.name(), defaultConfig());
    }

    private ProviderPoolConfig defaultConfig() {
        ProviderPoolConfig c = new ProviderPoolConfig();
        c.setConcurrency(defaultConcurrency);
        c.setQueueCapacity(defaultQueueCapacity);
        return c;
    }
}

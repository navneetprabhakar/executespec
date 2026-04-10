package com.navneet.executespec.harness;

import com.navneet.executespec.config.ProviderRunPoolProperties;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.state.StateStore;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

/**
 * Manages one {@link ProviderRunPool} per {@link LlmProvider}.
 *
 * <p>Pools are created at startup with per-provider concurrency and queue
 * capacity from {@link ProviderRunPoolProperties}. Micrometer gauges expose
 * per-provider active and queued counts.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ProviderRunPoolManager {

    private final ProviderRunPoolProperties properties;
    private final MeterRegistry meterRegistry;
    private final StateStore stateStore;

    private final Map<LlmProvider, ProviderRunPool> pools = new EnumMap<>(LlmProvider.class);

    @PostConstruct
    void init() {
        for (LlmProvider provider : LlmProvider.values()) {
            ProviderRunPoolProperties.ProviderPoolConfig config = properties.forProvider(provider);
            ProviderRunPool pool = new ProviderRunPool(
                    provider,
                    config.getConcurrency(),
                    config.getQueueCapacity(),
                    stateStore
            );
            pools.put(provider, pool);

            // Per-provider gauges
            Gauge.builder("executespec.provider.pool.active", pool, ProviderRunPool::activeRuns)
                    .description("Active pipeline runs in the " + provider + " pool")
                    .tag("provider", provider.name())
                    .register(meterRegistry);

            Gauge.builder("executespec.provider.pool.queued", pool, ProviderRunPool::queueDepth)
                    .description("Queued pipeline runs waiting in the " + provider + " pool")
                    .tag("provider", provider.name())
                    .register(meterRegistry);

            Gauge.builder("executespec.provider.pool.capacity", pool, ProviderRunPool::concurrency)
                    .description("Configured concurrency for the " + provider + " pool")
                    .tag("provider", provider.name())
                    .register(meterRegistry);
        }
        log.info("ProviderRunPoolManager initialised — {} provider pools", pools.size());
    }

    /**
     * Returns the pool for the given provider.
     * Falls back to the OPENAI pool if the provider is not recognised.
     */
    public ProviderRunPool poolFor(LlmProvider provider) {
        return pools.getOrDefault(provider, pools.get(LlmProvider.OPENAI));
    }

    /**
     * Parses the provider string and returns the corresponding pool.
     * Falls back to OPENAI if the string is null, blank, or unrecognised.
     */
    public ProviderRunPool poolFor(String providerName) {
        if (providerName == null || providerName.isBlank()) {
            return pools.get(LlmProvider.OPENAI);
        }
        try {
            return poolFor(LlmProvider.valueOf(providerName.toUpperCase()));
        } catch (IllegalArgumentException e) {
            log.warn("Unknown provider '{}' — routing to OPENAI pool", providerName);
            return pools.get(LlmProvider.OPENAI);
        }
    }

    /** Total active runs across all providers. */
    public int totalActiveRuns() {
        return pools.values().stream().mapToInt(ProviderRunPool::activeRuns).sum();
    }

    /** Total queued runs across all providers. */
    public int totalQueuedRuns() {
        return pools.values().stream().mapToInt(ProviderRunPool::queueDepth).sum();
    }

    @PreDestroy
    void shutdown() {
        pools.values().forEach(ProviderRunPool::shutdown);
        log.info("ProviderRunPoolManager shut down");
    }
}

package com.navneet.executespec.router;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Resolves the concrete LLM model for a given agent and user-selected provider.
 *
 * <p>Lookup: agentName → {@link AgentTier} (from config) → provider column in matrix → model string.
 * If the user's selected provider is unhealthy (circuit open in {@link ProviderHealthTracker}),
 * fails over to the next provider in the configured failover chain.
 *
 * <p>Replaces the old {@code ModelRouter} (tier-based FAST/CAPABLE/POWER selection).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModelResolver {

    private final ModelMatrixProperties matrixProps;
    private final ProviderHealthTracker healthTracker;
    private final MeterRegistry meterRegistry;

    /**
     * Resolves the model for a given agent within the user's selected provider.
     * Falls back to next provider in failover chain if primary is unhealthy.
     *
     * @param agentName    simple class name of the agent (e.g. "WorkerAgent")
     * @param userProvider the project's selected LLM provider
     * @return resolved model with actual provider (may differ from requested on failover)
     */
    public ResolvedModel resolve(String agentName, LlmProvider userProvider) {
        AgentTier tier = matrixProps.getAgentTierMap().get(agentName);
        if (tier == null) {
            log.warn("[ModelResolver] No tier mapping for agent '{}' — defaulting to WORKHORSE", agentName);
            tier = AgentTier.WORKHORSE;
        }

        // Try the user's selected provider first
        if (healthTracker.isHealthy(userProvider)) {
            String model = lookupModel(userProvider, tier);
            return ResolvedModel.builder()
                    .requestedProvider(userProvider)
                    .actualProvider(userProvider)
                    .model(model)
                    .agentTier(tier)
                    .fallback(false)
                    .build();
        }

        // Failover: try providers in configured order
        List<String> failoverList = matrixProps.getFailoverOrder()
                .get(userProvider.name().toLowerCase());
        if (failoverList != null) {
            for (String fallbackName : failoverList) {
                LlmProvider fallback = LlmProvider.valueOf(fallbackName.toUpperCase());
                if (healthTracker.isHealthy(fallback)) {
                    String fallbackModel = lookupModel(fallback, tier);
                    log.warn("[ModelResolver] Failover: {} → {} for agent {} (tier={})",
                            userProvider, fallback, agentName, tier);
                    Counter.builder("executespec.routing.failover")
                            .tag("from", userProvider.name())
                            .tag("to", fallback.name())
                            .tag("agent", agentName)
                            .register(meterRegistry).increment();
                    return ResolvedModel.builder()
                            .requestedProvider(userProvider)
                            .actualProvider(fallback)
                            .model(fallbackModel)
                            .agentTier(tier)
                            .fallback(true)
                            .build();
                }
            }
        }

        // All providers unhealthy — force the user's selected provider anyway
        log.warn("[ModelResolver] All providers unhealthy — forcing {} for agent {}", userProvider, agentName);
        String model = lookupModel(userProvider, tier);
        return ResolvedModel.builder()
                .requestedProvider(userProvider)
                .actualProvider(userProvider)
                .model(model)
                .agentTier(tier)
                .fallback(false)
                .build();
    }

    /**
     * Resolves a model at a specific tier override (e.g. for stall escalation).
     */
    public ResolvedModel resolve(String agentName, LlmProvider userProvider, AgentTier tierOverride) {
        ResolvedModel base = resolve(agentName, userProvider);
        String model = lookupModel(base.getActualProvider(), tierOverride);
        return ResolvedModel.builder()
                .requestedProvider(base.getRequestedProvider())
                .actualProvider(base.getActualProvider())
                .model(model)
                .agentTier(tierOverride)
                .fallback(base.isFallback())
                .build();
    }

    private String lookupModel(LlmProvider provider, AgentTier tier) {
        ModelMatrixProperties.ProviderModels models =
                matrixProps.getProviders().get(provider.name().toLowerCase());
        if (models == null) {
            throw new IllegalStateException("No model matrix config for provider: " + provider);
        }
        return switch (tier) {
            case PLANNING -> models.getPlanning();
            case WORKHORSE -> models.getWorkhorse();
            case LIGHTWEIGHT -> models.getLightweight();
        };
    }
}

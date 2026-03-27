package com.navneet.executespec.router;

import com.navneet.executespec.config.ModelConstants;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;


/**
 * Two-axis model routing:
 *   Axis 1 (Tier): CAPABLE → POWER based on task complexity + escalation (FAST tier disabled)
 *   Axis 2 (Provider): Fixed priority order with fallback on failure/unavailability
 *
 * Provider priority: Anthropic → OpenAI → Google
 * On failure or unavailability, the next provider in the chain is tried automatically.
 *
 * Provider failover: same tier, different provider (horizontal)
 * Tier escalation: higher tier on validation retry failure (vertical)
 *   - CAPABLE → POWER on attempt 3 (auto)
 *   - POWER → POWER (already max)
 *
 * This service does NOT make LLM calls. It selects a model, and the caller
 * passes the model name to the existing LlmRouter/ChatModel for execution.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModelRouter {

    /** Fixed priority order: Anthropic first, OpenAI second, Google third. */
    private static final List<LlmProvider> PROVIDER_PRIORITY = List.of(
            LlmProvider.ANTHROPIC,
            LlmProvider.OPENAI,
            LlmProvider.GEMINI
    );

    private static final Map<ModelTier, Map<LlmProvider, String>> MODEL_MATRIX = Map.of(
            ModelTier.FAST, Map.of(
                    LlmProvider.ANTHROPIC, ModelConstants.HAIKU,
                    LlmProvider.OPENAI, ModelConstants.GPT5_4_MINI,
                    LlmProvider.GEMINI, ModelConstants.GEMINI_FLASH
            ),
            ModelTier.CAPABLE, Map.of(
                    LlmProvider.ANTHROPIC, ModelConstants.SONNET,
                    LlmProvider.OPENAI, ModelConstants.GPT5_4,
                    LlmProvider.GEMINI, ModelConstants.GEMINI_PRO
            ),
            ModelTier.POWER, Map.of(
                    LlmProvider.ANTHROPIC, ModelConstants.OPUS,
                    LlmProvider.OPENAI, ModelConstants.GPT5_4,
                    LlmProvider.GEMINI, ModelConstants.GEMINI_PRO
            )
    );

    private final ProviderHealthTracker healthTracker;
    private final MeterRegistry meterRegistry;

    @PostConstruct
    void init() {
        log.info("Provider routing initialized: fixed priority order {}",
                PROVIDER_PRIORITY);
    }

    /**
     * Select model for initial task execution based on planner's complexity hint.
     */
    public ModelSelection selectForTask(TaskComplexity complexity) {
        ModelTier tier = complexityToTier(complexity);
        return selectFromTier(tier);
    }

    /**
     * Select model for retry after validation failure.
     * Attempts 1-2: stay at current tier (give the model a second chance with error feedback).
     * Attempt 3: escalate one tier (FAST→CAPABLE, CAPABLE→POWER).
     */
    public ModelSelection selectForRetry(TaskComplexity complexity, int attempt) {
        ModelTier baseTier = complexityToTier(complexity);
        ModelTier escalatedTier = escalate(baseTier, attempt);

        if (escalatedTier != baseTier) {
            log.info("Escalating from {} to {} on attempt {}", baseTier, escalatedTier, attempt);
        }

        return selectFromTier(escalatedTier);
    }

    /**
     * Select model using fixed priority order.
     * Picks the first healthy provider from the priority list.
     * Falls through to next provider if the selected one is unhealthy.
     */
    public ModelSelection selectFromTier(ModelTier tier) {
        for (LlmProvider provider : PROVIDER_PRIORITY) {
            if (healthTracker.isHealthy(provider)) {
                String modelName = MODEL_MATRIX.get(tier).get(provider);
                log.debug("Selected model: {} ({}/{}) via priority order", modelName, tier, provider);
                meterRegistry.counter("executespec.routing.provider_selected",
                        "provider", provider.name(), "tier", tier.name()).increment();
                return ModelSelection.builder()
                        .tier(tier)
                        .provider(provider)
                        .modelName(modelName)
                        .build();
            }
        }

        // All providers unhealthy — try first in priority anyway
        LlmProvider fallback = PROVIDER_PRIORITY.getFirst();
        log.warn("All providers unhealthy for tier {} — attempting {} anyway", tier, fallback);
        String modelName = MODEL_MATRIX.get(tier).get(fallback);
        return ModelSelection.builder()
                .tier(tier)
                .provider(fallback)
                .modelName(modelName)
                .build();
    }

    // ── Overloaded methods filtered by available providers (API key-aware) ────

    /**
     * Select model for initial task, filtered by available providers.
     */
    public ModelSelection selectForTask(TaskComplexity complexity, Set<LlmProvider> availableProviders) {
        ModelTier tier = complexityToTier(complexity);
        return selectFromTier(tier, availableProviders);
    }

    /**
     * Select model for retry, filtered by available providers.
     */
    public ModelSelection selectForRetry(TaskComplexity complexity, int attempt, Set<LlmProvider> availableProviders) {
        ModelTier baseTier = complexityToTier(complexity);
        ModelTier escalatedTier = escalate(baseTier, attempt);
        if (escalatedTier != baseTier) {
            log.info("Escalating from {} to {} on attempt {}", baseTier, escalatedTier, attempt);
        }
        return selectFromTier(escalatedTier, availableProviders);
    }

    /**
     * Select model using fixed priority, filtered by providers the org can use.
     */
    public ModelSelection selectFromTier(ModelTier tier, Set<LlmProvider> availableProviders) {
        // First pass: healthy + available
        for (LlmProvider provider : PROVIDER_PRIORITY) {
            if (availableProviders.contains(provider) && healthTracker.isHealthy(provider)) {
                String modelName = MODEL_MATRIX.get(tier).get(provider);
                log.debug("Selected model: {} ({}/{}) from available: {} via priority order",
                        modelName, tier, provider, availableProviders);
                meterRegistry.counter("executespec.routing.provider_selected",
                        "provider", provider.name(), "tier", tier.name()).increment();
                return ModelSelection.builder()
                        .tier(tier).provider(provider).modelName(modelName).build();
            }
        }

        // Second pass: available but unhealthy — try anyway
        for (LlmProvider provider : PROVIDER_PRIORITY) {
            if (availableProviders.contains(provider)) {
                String modelName = MODEL_MATRIX.get(tier).get(provider);
                log.warn("No healthy provider in {} — forcing {} anyway", availableProviders, provider);
                return ModelSelection.builder()
                        .tier(tier).provider(provider).modelName(modelName).build();
            }
        }

        throw new IllegalStateException("No providers available for tier " + tier + ". Available: " + availableProviders);
    }

    /**
     * Map complexity hint to initial model tier.
     * FAST tier disabled — all tasks start at CAPABLE minimum for quality.
     * LOW → CAPABLE (Sonnet), MEDIUM → CAPABLE (Sonnet), HIGH → CAPABLE (Sonnet)
     */
    private ModelTier complexityToTier(TaskComplexity complexity) {
        return switch (complexity) {
            case LOW -> ModelTier.CAPABLE;
            case MEDIUM -> ModelTier.CAPABLE;
            case HIGH -> ModelTier.CAPABLE;
        };
    }

    /**
     * Escalate tier based on retry attempt number.
     * Attempts 1-2: stay put. Attempt 3+: escalate one level.
     *
     * CAPABLE → POWER on attempt 3 (auto-escalate to Opus/GPT-5.4)
     * POWER → POWER (already max)
     */
    private ModelTier escalate(ModelTier current, int attempt) {
        if (attempt <= 2) return current;
        return switch (current) {
            case FAST -> ModelTier.CAPABLE;   // shouldn't happen, but safe fallback
            case CAPABLE -> ModelTier.POWER;
            case POWER -> ModelTier.POWER;
        };
    }
}

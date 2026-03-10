package com.navneet.executespec.router;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Two-axis model routing:
 *   Axis 1 (Tier): FAST → CAPABLE → POWER based on task complexity + escalation
 *   Axis 2 (Provider): Rotates between ANTHROPIC and GEMINI (OpenAI last)
 *
 * Provider failover: same tier, different provider (horizontal)
 * Tier escalation: higher tier on validation retry failure (vertical)
 *
 * This service does NOT make LLM calls. It selects a model, and the caller
 * passes the model name to the existing LlmRouter/ChatModel for execution.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModelRouter {

    private static final Map<ModelTier, Map<LlmProvider, String>> MODEL_MATRIX = Map.of(
            ModelTier.FAST, Map.of(
                    LlmProvider.ANTHROPIC, "claude-haiku-4-5-20251001",
                    LlmProvider.OPENAI, "gpt-4o-mini",
                    LlmProvider.GEMINI, "gemini-3.1-flash-lite-preview"
            ),
            ModelTier.CAPABLE, Map.of(
                    LlmProvider.ANTHROPIC, "claude-sonnet-4-6",
                    LlmProvider.OPENAI, "gpt-4o",
                    LlmProvider.GEMINI, "gemini-3.1-pro-preview-customtools"
            ),
            ModelTier.POWER, Map.of(
                    LlmProvider.ANTHROPIC, "claude-opus-4-6",
                    LlmProvider.OPENAI, "gpt-4o",
                    LlmProvider.GEMINI, "gemini-3.1-pro-preview-customtools"
            )
    );

    private final ProviderHealthTracker healthTracker;

    /** Global rotation counter — alternates primary provider between Anthropic and Gemini. */
    private final AtomicInteger rotationCounter = new AtomicInteger(0);

    /**
     * Return provider preference order based on rotation counter.
     * Even calls: [ANTHROPIC, GEMINI, OPENAI]  (Anthropic-first)
     * Odd  calls: [GEMINI, ANTHROPIC, OPENAI]  (Gemini-first)
     * OpenAI stays last in both orderings (most expensive).
     */
    private List<LlmProvider> rotatedProviderOrder() {
        int n = rotationCounter.getAndIncrement();
        if (n % 2 == 0) {
            return List.of(LlmProvider.ANTHROPIC, LlmProvider.GEMINI, LlmProvider.OPENAI);
        } else {
            return List.of(LlmProvider.GEMINI, LlmProvider.ANTHROPIC, LlmProvider.OPENAI);
        }
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
     * Select the best available model for a given tier.
     * Rotates primary provider between Anthropic and Gemini to balance token usage.
     */
    public ModelSelection selectFromTier(ModelTier tier) {
        List<LlmProvider> order = rotatedProviderOrder();
        for (LlmProvider provider : order) {
            if (healthTracker.isHealthy(provider)) {
                String modelName = MODEL_MATRIX.get(tier).get(provider);
                log.debug("Selected model: {} ({}/{}) [rotation]", modelName, tier, provider);
                return ModelSelection.builder()
                        .tier(tier)
                        .provider(provider)
                        .modelName(modelName)
                        .build();
            }
        }
        // All providers down — try first in rotation order anyway (might recover)
        LlmProvider fallback = order.get(0);
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
     * Select the best available model for a tier, filtered by providers the org can use.
     * Rotates primary provider between Anthropic and Gemini to balance token usage.
     */
    public ModelSelection selectFromTier(ModelTier tier, Set<LlmProvider> availableProviders) {
        List<LlmProvider> order = rotatedProviderOrder();
        for (LlmProvider provider : order) {
            if (availableProviders.contains(provider) && healthTracker.isHealthy(provider)) {
                String modelName = MODEL_MATRIX.get(tier).get(provider);
                log.debug("Selected model: {} ({}/{}) from available: {} [rotation]", modelName, tier, provider, availableProviders);
                return ModelSelection.builder()
                        .tier(tier).provider(provider).modelName(modelName).build();
            }
        }

        // No healthy + available provider — try first available regardless of health
        for (LlmProvider provider : availableProviders) {
            String modelName = MODEL_MATRIX.get(tier).get(provider);
            log.warn("No healthy provider in {} — forcing {} anyway", availableProviders, provider);
            return ModelSelection.builder()
                    .tier(tier).provider(provider).modelName(modelName).build();
        }

        throw new IllegalStateException("No providers available for tier " + tier + ". Available: " + availableProviders);
    }

    /**
     * Map complexity hint to initial model tier.
     * LOW → FAST (Haiku), MEDIUM → CAPABLE (Sonnet), HIGH → CAPABLE (Sonnet)
     * Quality-first: MEDIUM uses Sonnet for better output quality.
     */
    private ModelTier complexityToTier(TaskComplexity complexity) {
        return switch (complexity) {
            case LOW -> ModelTier.FAST;
            case MEDIUM -> ModelTier.CAPABLE;
            case HIGH -> ModelTier.CAPABLE;
        };
    }

    /**
     * Escalate tier based on retry attempt number.
     * Attempts 1-2: stay put. Attempt 3: go up one level.
     * FAST→CAPABLE, CAPABLE→POWER (quality-first: allow full escalation on retry).
     */
    private ModelTier escalate(ModelTier current, int attempt) {
        if (attempt <= 2) return current;
        return switch (current) {
            case FAST -> ModelTier.CAPABLE;
            case CAPABLE -> ModelTier.POWER;
            case POWER -> ModelTier.POWER;
        };
    }
}

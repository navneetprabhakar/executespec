package com.navneet.executespec.router;

import com.navneet.executespec.config.ModelConstants;
import com.navneet.executespec.config.RoutingProperties;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;


/**
 * Two-axis model routing:
 *   Axis 1 (Tier): FAST → CAPABLE → POWER based on task complexity + escalation
 *   Axis 2 (Provider): Weighted round-robin distribution (configurable via executespec.routing.provider-weights)
 *
 * Provider failover: same tier, different provider (horizontal)
 * Tier escalation: higher tier on validation retry failure (vertical)
 *   - FAST → CAPABLE on attempt 3 (auto)
 *   - CAPABLE → CAPABLE (capped — POWER is manual-only)
 *   - POWER is triggered only via manual retry endpoint or admin override
 *
 * This service does NOT make LLM calls. It selects a model, and the caller
 * passes the model name to the existing LlmRouter/ChatModel for execution.
 *
 * TODO: Per-run cost budget — check budget before each worker attempt:
 *   - FREE: $0.50 (fixed), PRO: $2.00 (max $10), TEAM: $5.00 (max $25), ENTERPRISE: $10.00 (max $100)
 *   - RunCostBudgetService: initBudget(runId, tier), canAfford(runId, cost), getRemainingBudget(runId)
 *   - Before tier escalation: verify remaining budget covers higher-tier cost
 *   - On budget exceeded: mark task PARTIAL, emit BUDGET_EXCEEDED SSE event
 *   - Store budget in Redis (ephemeral, per-run lifetime)
 *   - Uses CostEstimator.computeCostForRun() for running total
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModelRouter {

    private static final Map<String, LlmProvider> NAME_TO_PROVIDER = Map.of(
            "anthropic", LlmProvider.ANTHROPIC,
            "google", LlmProvider.GEMINI,
            "openai", LlmProvider.OPENAI
    );

    private static final Map<ModelTier, Map<LlmProvider, String>> MODEL_MATRIX = Map.of(
            ModelTier.FAST, Map.of(
                    LlmProvider.ANTHROPIC, ModelConstants.HAIKU,
                    LlmProvider.OPENAI, ModelConstants.GPT5_4_MINI,
                    LlmProvider.GEMINI, ModelConstants.GEMINI_FLASH
            ),
            ModelTier.CAPABLE, Map.of(
                    LlmProvider.ANTHROPIC, ModelConstants.SONNET,
                    LlmProvider.OPENAI, ModelConstants.GPT4O,
                    LlmProvider.GEMINI, ModelConstants.GEMINI_PRO
            ),
            ModelTier.POWER, Map.of(
                    LlmProvider.ANTHROPIC, ModelConstants.OPUS,
                    LlmProvider.OPENAI, ModelConstants.GPT4O,
                    LlmProvider.GEMINI, ModelConstants.GEMINI_PRO
            )
    );

    private final ProviderHealthTracker healthTracker;
    private final RoutingProperties routingProperties;
    private final MeterRegistry meterRegistry;

    /** Weighted provider sequence built from config. e.g. [A,A,A,A,G,G,G,O,O,O] for 4:3:3. */
    private List<LlmProvider> weightedSequence;

    /** Round-robin counter — thread-safe, wraps naturally on overflow. */
    private final AtomicLong roundRobinCounter = new AtomicLong(0);

    @PostConstruct
    void init() {
        weightedSequence = buildWeightedSequence(routingProperties.getProviderWeights());
        log.info("Provider load distribution initialized: weights={} sequence-length={}",
                routingProperties.getProviderWeights(), weightedSequence.size());
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
     * Select model using weighted round-robin distribution.
     * Advances a counter and picks the provider from the weighted sequence.
     * Falls through to next providers if the selected one is unhealthy.
     */
    public ModelSelection selectFromTier(ModelTier tier) {
        int seqLen = weightedSequence.size();
        int startIdx = (int) (Math.abs(roundRobinCounter.getAndIncrement()) % seqLen);

        // Try from the weighted position, cycling through all providers
        for (int offset = 0; offset < seqLen; offset++) {
            LlmProvider provider = weightedSequence.get((startIdx + offset) % seqLen);
            if (healthTracker.isHealthy(provider)) {
                String modelName = MODEL_MATRIX.get(tier).get(provider);
                log.debug("Selected model: {} ({}/{}) via weighted round-robin", modelName, tier, provider);
                meterRegistry.counter("executespec.routing.provider_selected",
                        "provider", provider.name(), "tier", tier.name()).increment();
                return ModelSelection.builder()
                        .tier(tier)
                        .provider(provider)
                        .modelName(modelName)
                        .build();
            }
        }

        // All providers unhealthy — try first in weighted sequence anyway
        LlmProvider fallback = weightedSequence.get(startIdx);
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
     * Select model using weighted round-robin, filtered by providers the org can use.
     */
    public ModelSelection selectFromTier(ModelTier tier, Set<LlmProvider> availableProviders) {
        int seqLen = weightedSequence.size();
        int startIdx = (int) (Math.abs(roundRobinCounter.getAndIncrement()) % seqLen);

        // Try from the weighted position, cycling through all providers, filtered by availability
        for (int offset = 0; offset < seqLen; offset++) {
            LlmProvider provider = weightedSequence.get((startIdx + offset) % seqLen);
            if (availableProviders.contains(provider) && healthTracker.isHealthy(provider)) {
                String modelName = MODEL_MATRIX.get(tier).get(provider);
                log.debug("Selected model: {} ({}/{}) from available: {} via weighted round-robin",
                        modelName, tier, provider, availableProviders);
                meterRegistry.counter("executespec.routing.provider_selected",
                        "provider", provider.name(), "tier", tier.name()).increment();
                return ModelSelection.builder()
                        .tier(tier).provider(provider).modelName(modelName).build();
            }
        }

        // No healthy + available provider — try first available regardless of health
        for (int offset = 0; offset < seqLen; offset++) {
            LlmProvider provider = weightedSequence.get((startIdx + offset) % seqLen);
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
     * Attempts 1-2: stay put. Attempt 3+: escalate one level, capped at CAPABLE.
     *
     * FAST → CAPABLE (auto, on attempt 3)
     * CAPABLE → CAPABLE (capped — never auto-promote to POWER)
     * POWER → POWER (already max, manual trigger only)
     *
     * POWER tier is reserved for manual retry via /api/runs/{id}/tasks/{taskId}/retry
     * or explicit admin override. A task that Sonnet can't solve in 3 attempts is likely
     * a prompt/spec problem, not a model capability problem.
     */
    private ModelTier escalate(ModelTier current, int attempt) {
        if (attempt <= 2) return current;
        return switch (current) {
            case FAST -> ModelTier.CAPABLE;
            case CAPABLE -> ModelTier.CAPABLE; // Never auto-escalate to POWER
            case POWER -> ModelTier.POWER;
        };
    }

    // ── Weighted sequence builder ──────────────────────────────────────────────

    /**
     * Build a flat provider list from weight config.
     * e.g. {anthropic:4, google:3, openai:3} → [ANTHROPIC x4, GEMINI x3, OPENAI x3]
     * Falls back to Anthropic-only if config is empty/invalid.
     */
    private static List<LlmProvider> buildWeightedSequence(Map<String, Integer> weights) {
        List<LlmProvider> sequence = new ArrayList<>();

        if (weights == null || weights.isEmpty()) {
            sequence.add(LlmProvider.ANTHROPIC);
            return sequence;
        }

        for (var entry : weights.entrySet()) {
            LlmProvider provider = NAME_TO_PROVIDER.get(entry.getKey().toLowerCase());
            int weight = entry.getValue() != null ? entry.getValue() : 0;
            if (provider != null && weight > 0) {
                for (int i = 0; i < weight; i++) {
                    sequence.add(provider);
                }
            }
        }

        if (sequence.isEmpty()) {
            sequence.add(LlmProvider.ANTHROPIC);
        }

        return List.copyOf(sequence);
    }
}

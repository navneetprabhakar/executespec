package com.navneet.executespec.router;

import com.navneet.executespec.config.ModelConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;


/**
 * Two-axis model routing:
 *   Axis 1 (Tier): FAST → CAPABLE → POWER based on task complexity + escalation
 *   Axis 2 (Provider): Fixed priority: ANTHROPIC → GEMINI → OPENAI
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

    /** Fixed provider priority: Anthropic primary, Gemini fallback #1, OpenAI fallback #2. */
    private static final List<LlmProvider> PROVIDER_PRIORITY =
            List.of(LlmProvider.ANTHROPIC, LlmProvider.GEMINI, LlmProvider.OPENAI);

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
     * Fixed priority: ANTHROPIC → GEMINI → OPENAI.
     */
    public ModelSelection selectFromTier(ModelTier tier) {
        for (LlmProvider provider : PROVIDER_PRIORITY) {
            if (healthTracker.isHealthy(provider)) {
                String modelName = MODEL_MATRIX.get(tier).get(provider);
                log.debug("Selected model: {} ({}/{})", modelName, tier, provider);
                return ModelSelection.builder()
                        .tier(tier)
                        .provider(provider)
                        .modelName(modelName)
                        .build();
            }
        }
        // All providers down — try Anthropic anyway (might recover)
        LlmProvider fallback = LlmProvider.ANTHROPIC;
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
     * Fixed priority: ANTHROPIC → GEMINI → OPENAI.
     */
    public ModelSelection selectFromTier(ModelTier tier, Set<LlmProvider> availableProviders) {
        for (LlmProvider provider : PROVIDER_PRIORITY) {
            if (availableProviders.contains(provider) && healthTracker.isHealthy(provider)) {
                String modelName = MODEL_MATRIX.get(tier).get(provider);
                log.debug("Selected model: {} ({}/{}) from available: {}", modelName, tier, provider, availableProviders);
                return ModelSelection.builder()
                        .tier(tier).provider(provider).modelName(modelName).build();
            }
        }

        // No healthy + available provider — try first available regardless of health
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
}

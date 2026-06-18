package com.navneet.executespec.router;

import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Data;

/**
 * Binds to {@code model-matrix.*} in application.yml.
 *
 * <p>Defines the model matrix (provider × agent tier → model string), the agent-to-tier mapping,
 * failover order, canonical model registry, legacy aliases, fallback chains, and free-tier lock.
 *
 * <p>This is the single source of truth for all model configuration.
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

  /** Canonical model registry: model name → metadata (provider, limits, pricing). */
  private Map<String, ModelConfig> models;

  /** Legacy model name → canonical model name (for in-flight runs and DB backward compat). */
  private Map<String, String> legacyAliases;

  /** Tier name → ordered fallback chain (replaces hardcoded FallbackLlmRouter chains). */
  private Map<String, List<FallbackEntry>> fallbackChains;

  /** FREE tier model lock. */
  private FreeTierConfig freeTier;

  @Data
  public static class ProviderModels {
    private String planning;
    private String workhorse;
    private String lightweight;
  }

  @Data
  public static class ModelConfig {
    /** Provider that owns this model: "anthropic", "google", "openai". */
    private String provider;

    /** Fallback chain tier: ARCHITECT, CAPABLE, UTILITY. */
    private String chainTier;

    /** Context window limit in tokens. */
    private int contextLimit = 200_000;

    /** Maximum output tokens supported by this model. */
    private int maxOutputTokens = 16_000;

    /** Pricing per 1M tokens. */
    private Pricing pricing;

    /** Human-readable notes (seeded into model_pricing table). */
    private String notes;

    /**
     * Display label for the model selector UI ({@code GET /api/models/available}). When null the
     * controller falls back to the raw model id, so this only needs to be set when the id isn't
     * pretty enough on its own (e.g. {@code claude-haiku-4-5-20251001} → {@code "Claude Haiku
     * 4.5"}).
     */
    private String displayLabel;
  }

  @Data
  public static class Pricing {
    private double inputPer1m;
    private double cachedInputPer1m;
    private double outputPer1m;
  }

  @Data
  public static class FallbackEntry {
    private String provider;
    private String model;
  }

  @Data
  public static class FreeTierConfig {
    private String provider;
    private String model;

    /** Model-level allow-list: ids the FREE tier may run (supersedes {@code provider} when set). */
    private List<String> models;
  }
}

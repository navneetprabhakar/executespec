package com.navneet.executespec.router;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import jakarta.annotation.PostConstruct;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Central lookup component for model metadata, backed entirely by {@link ModelMatrixProperties}
 * (application.yml).
 *
 * <p>Replaces all scattered hardcoded model constants, provider inference heuristics, token limits,
 * and pricing fallbacks with config-driven lookups.
 *
 * <p>All methods accept raw model name strings (including legacy aliases) and resolve them through
 * {@link #canonicalize(String)} automatically.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModelRegistry {

  private final ModelMatrixProperties matrixProps;

  /** Default context limit when model is unknown. */
  private static final int DEFAULT_CONTEXT_LIMIT = 200_000;

  /** Default max output tokens when model is unknown. */
  private static final int DEFAULT_MAX_OUTPUT = 16_000;

  @PostConstruct
  void validate() {
    Map<String, ModelMatrixProperties.ModelConfig> models = getModels();
    Map<String, String> aliases = getAliases();
    Map<String, List<ModelMatrixProperties.FallbackEntry>> chains = getChains();

    // 1. Every model referenced in providers matrix must exist in models map
    if (matrixProps.getProviders() != null) {
      for (var entry : matrixProps.getProviders().entrySet()) {
        String provider = entry.getKey();
        var pm = entry.getValue();
        validateModelExists(
            models, aliases, pm.getPlanning(), "providers." + provider + ".planning");
        validateModelExists(
            models, aliases, pm.getWorkhorse(), "providers." + provider + ".workhorse");
        validateModelExists(
            models, aliases, pm.getLightweight(), "providers." + provider + ".lightweight");
      }
    }

    // 2. Every model referenced in fallback-chains must exist in models map
    if (chains != null) {
      for (var entry : chains.entrySet()) {
        String tier = entry.getKey();
        for (var fb : entry.getValue()) {
          validateModelExists(models, aliases, fb.getModel(), "fallback-chains." + tier);
        }
      }
    }

    // 3. Every legacy alias target must exist in models map
    if (aliases != null) {
      for (var entry : aliases.entrySet()) {
        String target = entry.getValue();
        if (!models.containsKey(target)) {
          log.error(
              "[ModelRegistry] Legacy alias '{}' → '{}' targets unknown model!",
              entry.getKey(),
              target);
        }
      }
    }

    // 4. Free-tier model must exist
    if (matrixProps.getFreeTier() != null && matrixProps.getFreeTier().getModel() != null) {
      validateModelExists(models, aliases, matrixProps.getFreeTier().getModel(), "free-tier.model");
    }

    log.info(
        "[ModelRegistry] Validated {} models, {} legacy aliases, {} fallback chains",
        models.size(),
        aliases != null ? aliases.size() : 0,
        chains != null ? chains.size() : 0);
  }

  private void validateModelExists(
      Map<String, ModelMatrixProperties.ModelConfig> models,
      Map<String, String> aliases,
      String modelName,
      String context) {
    if (modelName == null) return;
    String canonical =
        aliases != null && aliases.containsKey(modelName) ? aliases.get(modelName) : modelName;
    if (!models.containsKey(canonical)) {
      log.error(
          "[ModelRegistry] Model '{}' referenced in {} not found in model-matrix.models!",
          modelName,
          context);
    }
  }

  /**
   * Resolves a model name through legacy aliases to its canonical form. Returns the input unchanged
   * if it's already canonical or unknown.
   */
  public String canonicalize(String modelName) {
    if (modelName == null) return null;
    String lower = modelName.toLowerCase();
    Map<String, String> aliases = getAliases();
    if (aliases != null && aliases.containsKey(lower)) {
      return aliases.get(lower);
    }
    // Also check case-insensitive against models map
    Map<String, ModelMatrixProperties.ModelConfig> models = getModels();
    if (models.containsKey(modelName)) return modelName;
    // Try lowercase match
    for (String key : models.keySet()) {
      if (key.equalsIgnoreCase(modelName)) return key;
    }
    return modelName; // unknown — return as-is
  }

  /**
   * Returns the provider for a given model name (e.g. "anthropic", "google", "openai"). Replaces
   * all {@code startsWith("claude-")} / {@code contains("gpt")} heuristics.
   *
   * @return provider string, or null if model is unknown
   */
  public String providerFor(String modelName) {
    ModelMatrixProperties.ModelConfig config = lookupConfig(modelName);
    return config != null ? config.getProvider() : null;
  }

  /**
   * Returns true if the model name is known (either canonical or a legacy alias). Replaces {@code
   * PlannerAgent.isConcreteModelName()}.
   */
  public boolean isKnownModel(String name) {
    if (name == null) return false;
    return lookupConfig(name) != null;
  }

  /**
   * Returns the context window limit for a model, in tokens. Replaces {@code
   * TokenBudgetMonitor.contextLimitFor()}.
   */
  public int contextLimitFor(String modelName) {
    ModelMatrixProperties.ModelConfig config = lookupConfig(modelName);
    return config != null ? config.getContextLimit() : DEFAULT_CONTEXT_LIMIT;
  }

  /**
   * Returns the context window for a model, falling back to a family-prefix heuristic when the
   * model isn't in the YAML matrix. Used by UI surfaces (e.g. the chat-header context-window chip)
   * that need a number for forward-compat models the registry doesn't yet know about.
   *
   * <p>Lookup order:
   *
   * <ol>
   *   <li>Exact match in {@code model-matrix.models} → return that config's context-limit.
   *   <li>Family prefix: {@code claude-opus-*-1m} → 1M, other claude → 200K, gemini → 1M, gpt →
   *       256K.
   *   <li>Final fallback: {@link #DEFAULT_CONTEXT_LIMIT} (200K).
   * </ol>
   *
   * <p>Replaces the deleted {@code ModelContextWindowMap} which was a separate hardcoded source of
   * truth that drifted from YAML (gpt-5.4 was 256K in the map, 200K in YAML — the YAML wins).
   */
  public int contextLimitForOrPrefixFallback(String modelName) {
    if (modelName == null || modelName.isBlank()) return DEFAULT_CONTEXT_LIMIT;
    ModelMatrixProperties.ModelConfig config = lookupConfig(modelName);
    if (config != null) return config.getContextLimit();
    // Forward-compat prefix heuristic — every shipping model SHOULD have a YAML entry; this
    // exists so a brand-new model name doesn't render as a "?" in the UI before the YAML
    // catches up.
    if (modelName.startsWith("claude-opus-") && modelName.endsWith("-1m")) return 1_000_000;
    if (modelName.startsWith("claude-")) return 200_000;
    if (modelName.startsWith("gemini-")) return 1_048_576;
    if (modelName.startsWith("gpt-")) return 256_000;
    return DEFAULT_CONTEXT_LIMIT;
  }

  /**
   * Returns the maximum output tokens supported by a model. Replaces {@code
   * TokenBudgetMonitor.maxOutputForModel()}.
   */
  public int maxOutputForModel(String modelName) {
    ModelMatrixProperties.ModelConfig config = lookupConfig(modelName);
    return config != null ? config.getMaxOutputTokens() : DEFAULT_MAX_OUTPUT;
  }

  /**
   * Returns pricing as {@code double[]{inputPer1M, outputPer1M}} for a model. Returns null if model
   * is unknown or has no pricing configured.
   */
  public double[] pricingFor(String modelName) {
    ModelMatrixProperties.ModelConfig config = lookupConfig(modelName);
    if (config == null || config.getPricing() == null) return null;
    return new double[] {config.getPricing().getInputPer1m(), config.getPricing().getOutputPer1m()};
  }

  /** Returns the notes string for a model (used by ModelPricingInitializer). */
  public String notesFor(String modelName) {
    ModelMatrixProperties.ModelConfig config = lookupConfig(modelName);
    return config != null ? config.getNotes() : null;
  }

  /**
   * Returns the human-readable display label for a model (used by the model-selector UI). Falls
   * back to the canonical model id when no explicit {@code display-label} is set in YAML.
   *
   * <p>Replaces the hardcoded {@code MODEL_LABELS} map in {@link
   * com.navneet.executespec.api.controller.ModelController}, restoring single-source-of-truth.
   */
  public String displayLabelFor(String modelName) {
    if (modelName == null) return null;
    ModelMatrixProperties.ModelConfig config = lookupConfig(modelName);
    if (config == null) return modelName;
    String label = config.getDisplayLabel();
    return (label != null && !label.isBlank()) ? label : modelName;
  }

  /**
   * Returns the fallback chain for a model based on its chain-tier. Each entry is a {@code
   * String[]{provider, model}}.
   *
   * <p>Replaces the hardcoded {@code ARCHITECT_CHAIN}, {@code CAPABLE_CHAIN}, {@code
   * UTILITY_CHAIN}, and {@code MODEL_CHAIN} in FallbackLlmRouter.
   */
  public List<String[]> fallbackChainFor(String modelName) {
    ModelMatrixProperties.ModelConfig config = lookupConfig(modelName);
    String tier = config != null ? config.getChainTier() : "CAPABLE";
    return fallbackChainForTier(tier);
  }

  /** Returns the fallback chain for a given tier name (ARCHITECT, CAPABLE, UTILITY). */
  public List<String[]> fallbackChainForTier(String tier) {
    Map<String, List<ModelMatrixProperties.FallbackEntry>> chains = getChains();
    if (chains == null) return Collections.emptyList();
    List<ModelMatrixProperties.FallbackEntry> entries =
        chains.get(tier != null ? tier.toUpperCase() : "CAPABLE");
    if (entries == null) {
      // Fallback to CAPABLE if tier not found
      entries = chains.get("CAPABLE");
    }
    if (entries == null) return Collections.emptyList();
    return entries.stream().map(e -> new String[] {e.getProvider(), e.getModel()}).toList();
  }

  /** Returns the FREE tier locked model name. */
  public String freeTierModel() {
    return matrixProps.getFreeTier() != null
        ? matrixProps.getFreeTier().getModel()
        : "gpt-5.4-mini";
  }

  /** Returns the FREE tier locked provider name. */
  public String freeTierProvider() {
    return matrixProps.getFreeTier() != null ? matrixProps.getFreeTier().getProvider() : "openai";
  }

  /**
   * Returns the workhorse model for a given provider from the matrix. Useful for agents that need
   * to reference the current workhorse model (e.g. ConversationAgent building suggestion plans).
   */
  public String workhouseModelFor(String provider) {
    if (matrixProps.getProviders() == null || provider == null) return null;
    var pm = matrixProps.getProviders().get(provider.toLowerCase());
    return pm != null ? pm.getWorkhorse() : null;
  }

  /** Returns the planning model for a given provider from the matrix. */
  public String planningModelFor(String provider) {
    if (matrixProps.getProviders() == null || provider == null) return null;
    var pm = matrixProps.getProviders().get(provider.toLowerCase());
    return pm != null ? pm.getPlanning() : null;
  }

  /** Returns the lightweight model for a given provider from the matrix. */
  public String lightweightModelFor(String provider) {
    if (matrixProps.getProviders() == null || provider == null) return null;
    var pm = matrixProps.getProviders().get(provider.toLowerCase());
    return pm != null ? pm.getLightweight() : null;
  }

  /**
   * Returns all canonical model entries for iteration (e.g. pricing seeding). Key: model name,
   * Value: ModelConfig.
   */
  public Map<String, ModelMatrixProperties.ModelConfig> allModels() {
    return getModels();
  }

  private ModelMatrixProperties.ModelConfig lookupConfig(String modelName) {
    if (modelName == null) return null;
    Map<String, ModelMatrixProperties.ModelConfig> models = getModels();
    // Direct lookup
    ModelMatrixProperties.ModelConfig config = models.get(modelName);
    if (config != null) return config;
    // Try canonical (legacy alias resolution)
    String canonical = canonicalize(modelName);
    return models.get(canonical);
  }

  private Map<String, ModelMatrixProperties.ModelConfig> getModels() {
    return matrixProps.getModels() != null ? matrixProps.getModels() : Collections.emptyMap();
  }

  private Map<String, String> getAliases() {
    return matrixProps.getLegacyAliases();
  }

  private Map<String, List<ModelMatrixProperties.FallbackEntry>> getChains() {
    return matrixProps.getFallbackChains();
  }
}

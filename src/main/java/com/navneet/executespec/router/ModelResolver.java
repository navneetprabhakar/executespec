package com.navneet.executespec.router;

import java.util.List;

import com.navneet.executespec.config.featureflag.FeatureFlagService;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * Resolves the concrete LLM model for a given agent and user-selected provider.
 *
 * <p>Lookup: agentName → {@link AgentTier} (from config) → provider column in matrix → model
 * string. This layer never performs a health-only provider switch: physical retry/failover belongs
 * to {@code LlmRouter}, where the durable tenant policy, current key provenance, ordered attempt
 * ledger and provider-specific fallback model are all available.
 *
 * <p>Replaces the old {@code ModelRouter} (tier-based FAST/CAPABLE/POWER selection).
 */
@Slf4j
@Service
public class ModelResolver {

  /**
   * Feature flag gating "adaptive routing" — the historical-success-rate-based provider override
   * inside {@link #selectAdaptiveProvider}. Default OFF (Fix G, 2026-05-03). When OFF, the user's
   * selected provider is always honoured. When ON, providers may be swapped under the user's feet
   * based on observed success rates — useful for ops scenarios but surprising for a user who picked
   * a specific provider deliberately.
   */
  public static final String FLAG_ADAPTIVE_ROUTING = "routing.adaptive-routing.enabled";

  private final ModelMatrixProperties matrixProps;
  private final ProviderHealthTracker healthTracker;
  private final ModelPerformanceTracker modelPerfTracker;
  private final MeterRegistry meterRegistry;
  private final FeatureFlagService featureFlagService;

  /**
   * Optional — only used to gate {@link #FLAG_ADAPTIVE_ROUTING}. Tests use the constructor variant
   * where this is null and adaptive routing is always disabled.
   */
  @Autowired
  public ModelResolver(
      ModelMatrixProperties matrixProps,
      ProviderHealthTracker healthTracker,
      ModelPerformanceTracker modelPerfTracker,
      MeterRegistry meterRegistry,
      ObjectProvider<FeatureFlagService> featureFlagServiceProvider) {
    this(
        matrixProps,
        healthTracker,
        modelPerfTracker,
        meterRegistry,
        featureFlagServiceProvider.getIfAvailable());
  }

  ModelResolver(
      ModelMatrixProperties matrixProps,
      ProviderHealthTracker healthTracker,
      ModelPerformanceTracker modelPerfTracker,
      MeterRegistry meterRegistry) {
    this(matrixProps, healthTracker, modelPerfTracker, meterRegistry, (FeatureFlagService) null);
  }

  ModelResolver(
      ModelMatrixProperties matrixProps,
      ProviderHealthTracker healthTracker,
      ModelPerformanceTracker modelPerfTracker,
      MeterRegistry meterRegistry,
      FeatureFlagService featureFlagService) {
    this.matrixProps = matrixProps;
    this.healthTracker = healthTracker;
    this.modelPerfTracker = modelPerfTracker;
    this.meterRegistry = meterRegistry;
    this.featureFlagService = featureFlagService;
  }

  /**
   * Resolves the model for a given agent within the user's selected provider. Provider-wire
   * failover happens later in {@code LlmRouter}, after durable policy authorization.
   *
   * @param agentName simple class name of the agent (e.g. "WorkerAgent")
   * @param userProvider the project's selected LLM provider
   * @return resolved model with actual provider (may differ only when adaptive routing is enabled)
   */
  public ResolvedModel resolve(String agentName, LlmProvider userProvider) {
    AgentTier tier = matrixProps.getAgentTierMap().get(agentName);
    if (tier == null) {
      log.warn(
          "[ModelResolver] No tier mapping for agent '{}' — defaulting to WORKHORSE", agentName);
      tier = AgentTier.WORKHORSE;
    }

    String primaryModel = lookupModel(userProvider, tier);
    // Adaptive selection is an explicit opt-in. It is evaluated only while the primary circuit is
    // healthy; an unhealthy circuit must not trigger a global-key-based pre-dispatch switch.
    // LlmRouter will attempt/retry the selected route and then apply the tenant-authorized chain.
    if (healthTracker.isHealthy(userProvider)) {
      // Fix G (2026-05-03) — adaptive routing is OPT-IN, not opt-out. Default behaviour:
      // honour the user's selected provider. Diagnosed from run a076c876 where every
      // MEDIUM (WORKHORSE-tier) task ran on claude-sonnet-4-6 instead of the configured
      // claude-haiku-4-5-20251001 because adaptive routing kicked in silently — Sonnet
      // is ~3× the cost and ~2× the latency of Haiku, so T7 (a 720-line test file)
      // burned 17 minutes + ~$2.50 instead of the ~6 minutes + ~$0.80 it should have.
      //
      // Two gates layered for back-compat:
      //   1. JVM strict-provider system property (existing) — set by eval scripts /
      //      orgs that want hard-locked provider routing
      //   2. Feature flag "routing.adaptive-routing.enabled" (new, default OFF) —
      //      operator can flip on globally if they want adaptive behaviour back
      // If neither says "let adaptive run", we always use the user's provider.
      boolean strict = Boolean.getBoolean("executespec.strict-provider");
      // DeepSeek has a deliberately ordered recovery policy: native first, then OpenRouter,
      // then Fireworks. Adaptive routing must not turn a healthy native DeepSeek request into a
      // direct gateway request merely because a gateway has a better historical score. That would
      // make the documented recovery order nondeterministic and hide which route was actually
      // exercised. Explicit provider selection (for example provider=FIREWORKS) is still honored;
      // this guard only applies when DeepSeek is the requested provider.
      boolean deepSeekOrderedRoute = userProvider == LlmProvider.DEEPSEEK;
      boolean adaptiveAllowed =
          !deepSeekOrderedRoute
              && !strict
              && featureFlagService != null
              && featureFlagService.isEnabled(FLAG_ADAPTIVE_ROUTING);
      LlmProvider adaptiveProvider =
          adaptiveAllowed
              ? selectAdaptiveProvider(userProvider, primaryModel, tier, agentName)
              : null;
      if (adaptiveProvider != null && adaptiveProvider != userProvider) {
        String adaptiveModel = lookupModel(adaptiveProvider, tier);
        log.info(
            "[ModelResolver] Adaptive routing: {} → {} for agent {} (better success rate)",
            userProvider,
            adaptiveProvider,
            agentName);
        Counter.builder("executespec.routing.adaptive_switch")
            .tag("from", userProvider.name())
            .tag("to", adaptiveProvider.name())
            .tag("agent", agentName)
            .register(meterRegistry)
            .increment();
        return resolved(userProvider, adaptiveProvider, adaptiveModel, tier, true);
      }
    } else {
      log.warn(
          "[ModelResolver] {} circuit is open for agent {}; preserving the requested route for"
              + " tenant-authorized router failover",
          userProvider,
          agentName);
    }
    return resolved(userProvider, userProvider, primaryModel, tier, false);
  }

  /** Resolves a model at a specific tier override (e.g. for stall escalation). */
  public ResolvedModel resolve(String agentName, LlmProvider userProvider, AgentTier tierOverride) {
    ResolvedModel base = resolve(agentName, userProvider);
    String model = lookupModel(base.getActualProvider(), tierOverride);
    return resolved(
        base.getRequestedProvider(),
        base.getActualProvider(),
        model,
        tierOverride,
        base.isFallback());
  }

  /**
   * Resolves the configured provider/tier cell without adaptive provider selection.
   *
   * <p>Used by pre-dispatch pricing fingerprints: historical physical attempts may be reused only
   * when they match the run's bound YAML/org/exact-pin matrix. A health/performance decision that
   * can change between calls is not a stable cohort identity; any later physical failover/adaptive
   * route simply produces a different recorded fingerprint and is excluded.
   */
  public ResolvedModel resolveConfigured(LlmProvider provider, AgentTier tier) {
    return resolved(provider, provider, lookupModel(provider, tier), tier, false);
  }

  /**
   * Checks if a healthy failover provider has materially better performance than the primary.
   * Returns the better provider, or null if the primary is best (or data is insufficient).
   */
  private LlmProvider selectAdaptiveProvider(
      LlmProvider primary, String primaryModel, AgentTier tier, String agentName) {
    List<String> failoverList = matrixProps.getFailoverOrder().get(primary.name().toLowerCase());
    if (failoverList == null) return null;

    for (String fallbackName : failoverList) {
      LlmProvider candidate = LlmProvider.valueOf(fallbackName.toUpperCase());
      if (!healthTracker.isHealthy(candidate)) continue;
      String candidateModel = lookupModel(candidate, tier);
      if (modelPerfTracker.isMateriallyBetter(
          candidate.name(), candidateModel, primary.name(), primaryModel)) {
        return candidate;
      }
    }
    return null;
  }

  private String lookupModel(LlmProvider provider, AgentTier tier) {
    ModelMatrixProperties.ProviderModels models =
        matrixProps.getProviders().get(provider.name().toLowerCase());
    if (models == null) {
      throw new ModelMatrixConfigurationException(
          "No model matrix config for provider: " + provider);
    }
    // F3 — a run-scoped per-org override for this (provider, tier) cell wins over the YAML
    // default. Bound by the pipeline at run start (RunModelOverrides); absent for non-pipeline
    // callers and for unset cells, in which case we fall straight through to YAML.
    String override = RunModelOverrides.lookup(provider, tier);
    if (override != null) {
      return override;
    }
    String configured =
        switch (tier) {
          case PLANNING -> models.getPlanning();
          case WORKHORSE -> models.getWorkhorse();
          case LIGHTWEIGHT -> models.getLightweight();
        };
    if (configured == null || configured.isBlank()) {
      throw new ModelMatrixConfigurationException(
          "No " + tier + " model matrix entry for provider: " + provider);
    }
    return configured;
  }

  /** Builds a resolved route in one place so all routing branches preserve the same metadata. */
  private static ResolvedModel resolved(
      LlmProvider requestedProvider,
      LlmProvider actualProvider,
      String model,
      AgentTier tier,
      boolean fallback) {
    return ResolvedModel.builder()
        .requestedProvider(requestedProvider)
        .actualProvider(actualProvider)
        .model(model)
        .agentTier(tier)
        .fallback(fallback)
        .build();
  }
}

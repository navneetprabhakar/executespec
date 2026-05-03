package com.navneet.executespec.router;

import java.util.List;

import com.navneet.executespec.config.featureflag.FeatureFlagService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Resolves the concrete LLM model for a given agent and user-selected provider.
 *
 * <p>Lookup: agentName → {@link AgentTier} (from config) → provider column in matrix → model
 * string. If the user's selected provider is unhealthy (circuit open in {@link
 * ProviderHealthTracker}), fails over to the next provider in the configured failover chain.
 *
 * <p>Replaces the old {@code ModelRouter} (tier-based FAST/CAPABLE/POWER selection).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModelResolver {

  /**
   * Feature flag gating "adaptive routing" — the historical-success-rate-based provider override
   * inside {@link #selectAdaptiveProvider}. Default OFF (Fix G, 2026-05-03). When OFF, the user's
   * selected provider is always honoured (subject only to circuit-breaker failover when it's
   * unhealthy). When ON, providers may be swapped under the user's feet based on observed success
   * rates — useful for ops scenarios but surprising for a user who picked a specific provider
   * deliberately.
   */
  public static final String FLAG_ADAPTIVE_ROUTING = "routing.adaptive-routing.enabled";

  private final ModelMatrixProperties matrixProps;
  private final ProviderHealthTracker healthTracker;
  private final ModelPerformanceTracker modelPerfTracker;
  private final MeterRegistry meterRegistry;

  /**
   * Optional — only used to gate {@link #FLAG_ADAPTIVE_ROUTING}. Tests use the no-arg constructor
   * variant where this is null and adaptive routing is always disabled.
   */
  @Autowired(required = false)
  private FeatureFlagService featureFlagService;

  /**
   * Resolves the model for a given agent within the user's selected provider. Falls back to next
   * provider in failover chain if primary is unhealthy.
   *
   * @param agentName simple class name of the agent (e.g. "WorkerAgent")
   * @param userProvider the project's selected LLM provider
   * @return resolved model with actual provider (may differ from requested on failover)
   */
  public ResolvedModel resolve(String agentName, LlmProvider userProvider) {
    AgentTier tier = matrixProps.getAgentTierMap().get(agentName);
    if (tier == null) {
      log.warn(
          "[ModelResolver] No tier mapping for agent '{}' — defaulting to WORKHORSE", agentName);
      tier = AgentTier.WORKHORSE;
    }

    // Try the user's selected provider first — but prefer a healthy alternative with
    // materially better success rate (≥10 pp improvement with sufficient samples).
    if (healthTracker.isHealthy(userProvider)) {
      String primaryModel = lookupModel(userProvider, tier);
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
      boolean adaptiveAllowed =
          !strict
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
        return ResolvedModel.builder()
            .requestedProvider(userProvider)
            .actualProvider(adaptiveProvider)
            .model(adaptiveModel)
            .agentTier(tier)
            .fallback(true)
            .build();
      }
      return ResolvedModel.builder()
          .requestedProvider(userProvider)
          .actualProvider(userProvider)
          .model(primaryModel)
          .agentTier(tier)
          .fallback(false)
          .build();
    }

    // Failover: try providers in configured order
    List<String> failoverList =
        matrixProps.getFailoverOrder().get(userProvider.name().toLowerCase());
    if (failoverList != null) {
      for (String fallbackName : failoverList) {
        LlmProvider fallback = LlmProvider.valueOf(fallbackName.toUpperCase());
        if (healthTracker.isHealthy(fallback)) {
          String fallbackModel = lookupModel(fallback, tier);
          log.warn(
              "[ModelResolver] Failover: {} → {} for agent {} (tier={})",
              userProvider,
              fallback,
              agentName,
              tier);
          Counter.builder("executespec.routing.failover")
              .tag("from", userProvider.name())
              .tag("to", fallback.name())
              .tag("agent", agentName)
              .register(meterRegistry)
              .increment();
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
    log.warn(
        "[ModelResolver] All providers unhealthy — forcing {} for agent {}",
        userProvider,
        agentName);
    String model = lookupModel(userProvider, tier);
    return ResolvedModel.builder()
        .requestedProvider(userProvider)
        .actualProvider(userProvider)
        .model(model)
        .agentTier(tier)
        .fallback(false)
        .build();
  }

  /** Resolves a model at a specific tier override (e.g. for stall escalation). */
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

  /**
   * Resolves a fallback provider for mid-task recovery, excluding the failed provider. Uses the
   * failover chain but skips the specified provider name.
   *
   * @param agentName agent class name for tier lookup
   * @param userProvider the project's selected provider
   * @param excludeProvider provider name to exclude (the one that failed)
   * @return resolved model from a different provider
   * @throws IllegalStateException if no fallback is available
   */
  public ResolvedModel resolveFallbackExcluding(
      String agentName, LlmProvider userProvider, String excludeProvider) {
    AgentTier tier = matrixProps.getAgentTierMap().getOrDefault(agentName, AgentTier.WORKHORSE);

    List<String> failoverList =
        matrixProps.getFailoverOrder().get(userProvider.name().toLowerCase());
    if (failoverList != null) {
      for (String fallbackName : failoverList) {
        if (fallbackName.equalsIgnoreCase(excludeProvider)) continue;
        LlmProvider fallback = LlmProvider.valueOf(fallbackName.toUpperCase());
        if (healthTracker.isHealthy(fallback)) {
          String model = lookupModel(fallback, tier);
          log.info(
              "[ModelResolver] Mid-task fallback: excluding {} → {} for agent {} (tier={})",
              excludeProvider,
              fallback,
              agentName,
              tier);
          return ResolvedModel.builder()
              .requestedProvider(userProvider)
              .actualProvider(fallback)
              .model(model)
              .agentTier(tier)
              .fallback(true)
              .build();
        }
      }
    }

    // Also try the user's own provider if it wasn't the failed one
    if (!userProvider.name().equalsIgnoreCase(excludeProvider)
        && healthTracker.isHealthy(userProvider)) {
      String model = lookupModel(userProvider, tier);
      return ResolvedModel.builder()
          .requestedProvider(userProvider)
          .actualProvider(userProvider)
          .model(model)
          .agentTier(tier)
          .fallback(true)
          .build();
    }

    throw new IllegalStateException("No fallback provider available excluding " + excludeProvider);
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
      throw new IllegalStateException("No model matrix config for provider: " + provider);
    }
    return switch (tier) {
      case PLANNING -> models.getPlanning();
      case WORKHORSE -> models.getWorkhorse();
      case LIGHTWEIGHT -> models.getLightweight();
    };
  }
}

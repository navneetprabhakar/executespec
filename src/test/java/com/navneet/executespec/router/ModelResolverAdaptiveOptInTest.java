package com.navneet.executespec.router;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;

import com.navneet.executespec.config.featureflag.FeatureFlagService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * Pins the Fix G default behaviour: adaptive routing is OPT-IN, not opt-out (2026-05-03).
 *
 * <p>Diagnosis from run a076c876: every MEDIUM-tier worker call ran on {@code claude-sonnet-4-6}
 * instead of the matrix-configured {@code claude-haiku-4-5-20251001} because {@code
 * selectAdaptiveProvider} silently overrode the user's provider based on historical success rate.
 * Sonnet is ~3× the cost and ~2× the latency of Haiku → T7's 720-line test write took 17 minutes +
 * ~$2.50 instead of the expected ~6 minutes + ~$0.80.
 *
 * <p>This test pins the contract:
 *
 * <ul>
 *   <li>Default (no flag set, no JVM property): adaptive routing is OFF — user's provider is always
 *       used (subject only to circuit-breaker failover)
 *   <li>Feature flag ON: adaptive routing fires (back-compat for ops scenarios that want it)
 *   <li>JVM strict-provider property ON: adaptive routing skipped regardless of flag
 *   <li>FeatureFlagService unwired (null): adaptive routing OFF (test / standalone safety)
 * </ul>
 */
class ModelResolverAdaptiveOptInTest {

  private final ModelMatrixProperties matrixProps = new ModelMatrixProperties();
  private final ProviderHealthTracker healthTracker = mock(ProviderHealthTracker.class);
  private final ModelPerformanceTracker perfTracker = mock(ModelPerformanceTracker.class);
  private final FeatureFlagService featureFlagService = mock(FeatureFlagService.class);

  @AfterEach
  void clearStrictProviderProperty() {
    System.clearProperty("executespec.strict-provider");
  }

  /** Build a resolver with a minimal in-memory matrix. */
  private ModelResolver newResolver(boolean wireFeatureFlag) {
    matrixProps.setAgentTierMap(Map.of("Worker", AgentTier.WORKHORSE));
    Map<String, ModelMatrixProperties.ProviderModels> providers = new HashMap<>();
    ModelMatrixProperties.ProviderModels anth = new ModelMatrixProperties.ProviderModels();
    anth.setPlanning("claude-sonnet-4-6");
    anth.setWorkhorse("claude-haiku-4-5-20251001");
    anth.setLightweight("claude-haiku-4-5-20251001");
    providers.put("anthropic", anth);
    ModelMatrixProperties.ProviderModels gem = new ModelMatrixProperties.ProviderModels();
    gem.setPlanning("gemini-3-flash-preview");
    gem.setWorkhorse("gemini-3-flash-preview");
    gem.setLightweight("gemini-3-flash-preview");
    providers.put("gemini", gem);
    matrixProps.setProviders(providers);
    matrixProps.setFailoverOrder(Map.of("anthropic", java.util.List.of("gemini")));

    ModelResolver r =
        new ModelResolver(matrixProps, healthTracker, perfTracker, new SimpleMeterRegistry());
    if (wireFeatureFlag) {
      ReflectionTestUtils.setField(r, "featureFlagService", featureFlagService);
    }
    return r;
  }

  @Test
  void default_no_flag_user_provider_is_honoured_no_adaptive_call() {
    ModelResolver r = newResolver(true);
    when(healthTracker.isHealthy(LlmProvider.ANTHROPIC)).thenReturn(true);
    when(featureFlagService.isEnabled(ModelResolver.FLAG_ADAPTIVE_ROUTING)).thenReturn(false);

    ResolvedModel resolved = r.resolve("Worker", LlmProvider.ANTHROPIC);

    assertThat(resolved.getActualProvider()).isEqualTo(LlmProvider.ANTHROPIC);
    assertThat(resolved.getModel()).isEqualTo("claude-haiku-4-5-20251001");
    // Performance tracker must NOT be consulted when adaptive routing is gated off.
    verify(perfTracker, never())
        .isMateriallyBetter(anyString(), anyString(), anyString(), anyString());
  }

  @Test
  void feature_flag_on_enables_adaptive_routing_path() {
    ModelResolver r = newResolver(true);
    lenient().when(healthTracker.isHealthy(LlmProvider.ANTHROPIC)).thenReturn(true);
    lenient().when(healthTracker.isHealthy(LlmProvider.GEMINI)).thenReturn(true);
    when(featureFlagService.isEnabled(ModelResolver.FLAG_ADAPTIVE_ROUTING)).thenReturn(true);
    // Default isMateriallyBetter returns false → no adaptive switch fires; we only
    // care that the call HAPPENED, proving the gate opened.
    lenient()
        .when(perfTracker.isMateriallyBetter(anyString(), anyString(), anyString(), anyString()))
        .thenReturn(false);

    r.resolve("Worker", LlmProvider.ANTHROPIC);

    // Adaptive path consulted perf tracker — pin that behaviour to confirm the flag
    // actually opens the gate.
    verify(perfTracker, org.mockito.Mockito.atLeastOnce())
        .isMateriallyBetter(anyString(), anyString(), anyString(), anyString());
  }

  @Test
  void jvm_strict_provider_property_skips_adaptive_even_when_flag_on() {
    System.setProperty("executespec.strict-provider", "true");
    ModelResolver r = newResolver(true);
    when(healthTracker.isHealthy(LlmProvider.ANTHROPIC)).thenReturn(true);
    when(featureFlagService.isEnabled(ModelResolver.FLAG_ADAPTIVE_ROUTING)).thenReturn(true);

    ResolvedModel resolved = r.resolve("Worker", LlmProvider.ANTHROPIC);

    assertThat(resolved.getActualProvider()).isEqualTo(LlmProvider.ANTHROPIC);
    verify(perfTracker, never())
        .isMateriallyBetter(anyString(), anyString(), anyString(), anyString());
  }

  @Test
  void no_feature_flag_service_wired_means_adaptive_off() {
    // Standalone / test / Spring-context-less invocation — featureFlagService is null,
    // adaptive must default to OFF (fail-safe).
    ModelResolver r = newResolver(false);
    when(healthTracker.isHealthy(LlmProvider.ANTHROPIC)).thenReturn(true);

    ResolvedModel resolved = r.resolve("Worker", LlmProvider.ANTHROPIC);

    assertThat(resolved.getActualProvider()).isEqualTo(LlmProvider.ANTHROPIC);
    assertThat(resolved.getModel()).isEqualTo("claude-haiku-4-5-20251001");
    verify(perfTracker, never())
        .isMateriallyBetter(anyString(), anyString(), anyString(), anyString());
  }

  @Test
  void flag_constant_name_is_stable_for_admin_endpoint() {
    // The ops UI / admin endpoint uses this constant string to flip the flag.
    // If a future commit accidentally renames it, /api/feature-flags/{key} URLs
    // break for operators. Pin the literal.
    assertThat(ModelResolver.FLAG_ADAPTIVE_ROUTING).isEqualTo("routing.adaptive-routing.enabled");
  }
}

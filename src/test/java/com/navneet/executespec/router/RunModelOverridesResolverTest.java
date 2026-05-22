package com.navneet.executespec.router;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * F3 — proves the per-org override bound via {@link RunModelOverrides} wins over the YAML default
 * in {@link ModelResolver}, and that an unset cell / cleared binding falls straight through to the
 * matrix. Covers the worker path (agent → WORKHORSE tier).
 */
class RunModelOverridesResolverTest {

  private ModelResolver resolver;

  @BeforeEach
  void setUp() {
    ModelMatrixProperties.ProviderModels anthropic = new ModelMatrixProperties.ProviderModels();
    anthropic.setPlanning("claude-sonnet-4-6");
    anthropic.setWorkhorse("claude-haiku-4-5-20251001");
    anthropic.setLightweight("claude-haiku-4-5-20251001");

    ModelMatrixProperties props = new ModelMatrixProperties();
    props.setProviders(Map.of("anthropic", anthropic));
    props.setAgentTierMap(Map.of("WorkerAgent", AgentTier.WORKHORSE));
    props.setFailoverOrder(Map.of());

    ProviderHealthTracker health = mock(ProviderHealthTracker.class);
    when(health.isHealthy(any())).thenReturn(true);

    resolver =
        new ModelResolver(
            props, health, mock(ModelPerformanceTracker.class), new SimpleMeterRegistry());
  }

  @AfterEach
  void tearDown() {
    RunModelOverrides.clear();
  }

  @Test
  void noBinding_usesYamlDefault() {
    assertThat(resolver.resolve("WorkerAgent", LlmProvider.ANTHROPIC).getModel())
        .isEqualTo("claude-haiku-4-5-20251001");
  }

  @Test
  void boundOverride_winsOverYamlDefault() {
    RunModelOverrides.bind(Map.of("anthropic:workhorse", "claude-opus-4-6"));
    assertThat(resolver.resolve("WorkerAgent", LlmProvider.ANTHROPIC).getModel())
        .isEqualTo("claude-opus-4-6");
  }

  @Test
  void overrideForDifferentCell_doesNotLeak() {
    // Override the PLANNING cell; a WORKHORSE resolve must still get the YAML default.
    RunModelOverrides.bind(Map.of("anthropic:planning", "claude-opus-4-6"));
    assertThat(resolver.resolve("WorkerAgent", LlmProvider.ANTHROPIC).getModel())
        .isEqualTo("claude-haiku-4-5-20251001");
  }

  @Test
  void clear_restoresYamlDefault() {
    RunModelOverrides.bind(Map.of("anthropic:workhorse", "claude-opus-4-6"));
    RunModelOverrides.clear();
    assertThat(resolver.resolve("WorkerAgent", LlmProvider.ANTHROPIC).getModel())
        .isEqualTo("claude-haiku-4-5-20251001");
  }
}

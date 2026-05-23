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

    ModelMatrixProperties.ProviderModels openai = new ModelMatrixProperties.ProviderModels();
    openai.setPlanning("gpt-5.4");
    openai.setWorkhorse("gpt-5.4-mini");
    openai.setLightweight("gpt-5.4-mini");

    ModelMatrixProperties props = new ModelMatrixProperties();
    props.setProviders(Map.of("anthropic", anthropic, "openai", openai));
    props.setAgentTierMap(
        Map.of(
            "WorkerAgent", AgentTier.WORKHORSE,
            "OutlinePlannerAgent", AgentTier.PLANNING,
            "SmartClassifierAgent", AgentTier.LIGHTWEIGHT));
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

  // ── Per-run provider+model pin (Beta, Enterprise-only — 2026-05-22) ──────────

  /**
   * The per-run pin built by {@link RunModelOverrides#withRunPin} must win over BOTH an org-level
   * override (here on the WORKHORSE cell) AND the YAML default — for all three tiers of the pinned
   * provider (planner / worker / classifier).
   */
  @Test
  void runPin_winsOverOrgOverrideAndYamlDefault_forAllThreeTiers() {
    // Org has its own WORKHORSE override; planner/lightweight cells are unset (YAML default).
    Map<String, String> orgOverrides = Map.of("anthropic:workhorse", "claude-opus-4-6");

    RunModelOverrides.bind(
        RunModelOverrides.withRunPin(orgOverrides, LlmProvider.ANTHROPIC, "claude-sonnet-4-6-pin"));

    // PLANNING (vs YAML claude-sonnet-4-6)
    assertThat(resolver.resolve("OutlinePlannerAgent", LlmProvider.ANTHROPIC).getModel())
        .isEqualTo("claude-sonnet-4-6-pin");
    // WORKHORSE (vs org override claude-opus-4-6 — the pin must out-rank it)
    assertThat(resolver.resolve("WorkerAgent", LlmProvider.ANTHROPIC).getModel())
        .isEqualTo("claude-sonnet-4-6-pin");
    // LIGHTWEIGHT (vs YAML claude-haiku-4-5-20251001)
    assertThat(resolver.resolve("SmartClassifierAgent", LlmProvider.ANTHROPIC).getModel())
        .isEqualTo("claude-sonnet-4-6-pin");
  }

  /**
   * The pin is scoped to the run's provider only — a different provider keeps its own resolution.
   */
  @Test
  void runPin_isScopedToPinnedProvider() {
    Map<String, String> orgOverrides = Map.of("openai:planning", "gpt-5.4-org");

    RunModelOverrides.bind(
        RunModelOverrides.withRunPin(orgOverrides, LlmProvider.ANTHROPIC, "claude-opus-4-6"));

    // Anthropic (pinned) → pin across all tiers
    assertThat(resolver.resolve("WorkerAgent", LlmProvider.ANTHROPIC).getModel())
        .isEqualTo("claude-opus-4-6");
    // OpenAI (not pinned) → org override on PLANNING still applies; WORKHORSE falls through to YAML
    assertThat(resolver.resolve("OutlinePlannerAgent", LlmProvider.OPENAI).getModel())
        .isEqualTo("gpt-5.4-org");
    assertThat(resolver.resolve("WorkerAgent", LlmProvider.OPENAI).getModel())
        .isEqualTo("gpt-5.4-mini");
  }

  /** A null/blank pin is a no-op: the org map (and YAML fallthrough) are preserved unchanged. */
  @Test
  void withRunPin_nullPin_isNoOp() {
    Map<String, String> orgOverrides = Map.of("anthropic:workhorse", "claude-opus-4-6");

    RunModelOverrides.bind(RunModelOverrides.withRunPin(orgOverrides, LlmProvider.ANTHROPIC, null));
    assertThat(resolver.resolve("WorkerAgent", LlmProvider.ANTHROPIC).getModel())
        .isEqualTo("claude-opus-4-6"); // org override survives
    assertThat(resolver.resolve("OutlinePlannerAgent", LlmProvider.ANTHROPIC).getModel())
        .isEqualTo("claude-sonnet-4-6"); // unset cell → YAML default

    RunModelOverrides.bind(RunModelOverrides.withRunPin(orgOverrides, LlmProvider.ANTHROPIC, "  "));
    assertThat(resolver.resolve("WorkerAgent", LlmProvider.ANTHROPIC).getModel())
        .isEqualTo("claude-opus-4-6");
  }
}

package com.navneet.executespec.harness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agent.PlannedTask;
import com.navneet.executespec.config.featureflag.FeatureFlagService;
import com.navneet.executespec.context.ContextStore;
import com.navneet.executespec.cost.RunCostTracker;
import com.navneet.executespec.observability.Metrics;
import com.navneet.executespec.project.rag.RetrievalService;
import com.navneet.executespec.router.AgentTier;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.router.ModelMatrixProperties;
import com.navneet.executespec.router.ModelResolver;
import com.navneet.executespec.router.ResolvedModel;
import com.navneet.executespec.smoketest.WorkspaceTestRunner;
import com.navneet.executespec.sse.SseBroadcaster;
import com.navneet.executespec.state.repository.AgentTaskRepository;
import com.navneet.executespec.tool.BuildProjectTool;

import org.junit.jupiter.api.Test;

/**
 * No-spend A/B contract for model-specific capability profiles.
 *
 * <p>This test deliberately exercises the private tier-config seam used by the real sequential
 * executor. It proves three things that a registry-only test cannot prove: the resolved concrete
 * model is selected, a model key wins over its provider fallback, and the evidence flag makes the
 * disabled arm byte-equivalent to the complexity defaults. It does not authorize a production
 * rollout; the YAML and database flag remain fail-closed.
 */
class ModelCapabilityProfileABTest {

  private record Config(int warn, int force) {}

  @Test
  void modelProfileArmChangesOnlyTheResolvedModelAndDisabledArmIsBaseline() throws Exception {
    ModelMatrixProperties matrix = mock(ModelMatrixProperties.class);
    ModelMatrixProperties.CapabilityProfile provider = profile(18, 34);
    ModelMatrixProperties.CapabilityProfile model = profile(22, 47);
    when(matrix.getCapabilityProfiles())
        .thenReturn(Map.of("anthropic", provider, "anthropic/claude-sonnet-profiled", model));

    ModelResolver resolver = mock(ModelResolver.class);
    lenient()
        .when(resolver.resolve(anyString(), any(LlmProvider.class), any(AgentTier.class)))
        .thenReturn(
            ResolvedModel.builder()
                .requestedProvider(LlmProvider.ANTHROPIC)
                .actualProvider(LlmProvider.ANTHROPIC)
                .model("claude-sonnet-profiled")
                .agentTier(AgentTier.WORKHORSE)
                .build());

    FeatureFlagService enabled = mock(FeatureFlagService.class);
    when(enabled.isEnabled(SequentialExecutor.FLAG_CAPABILITY_PROFILES)).thenReturn(true);
    FeatureFlagService disabled = mock(FeatureFlagService.class);
    when(disabled.isEnabled(SequentialExecutor.FLAG_CAPABILITY_PROFILES)).thenReturn(false);

    Config baseline = tierConfig(newExecutor(resolver, matrix, disabled));
    Config profiled = tierConfig(newExecutor(resolver, matrix, enabled));

    // LOW complexity defaults are 10/16; no profile may alter the disabled arm.
    assertThat(baseline).isEqualTo(new Config(10, 16));
    // The concrete model key wins over the provider fallback (18/34), proving model selection is
    // actually consumed by SequentialExecutor rather than merely parsed by ModelRegistry.
    assertThat(profiled).isEqualTo(new Config(22, 47));
    assertThat(profiled).isNotEqualTo(new Config(18, 34));
  }

  private static ModelMatrixProperties.CapabilityProfile profile(int warn, int force) {
    var profile = new ModelMatrixProperties.CapabilityProfile();
    profile.setStagnationWarnAfter(warn);
    profile.setStagnationForceAfter(force);
    return profile;
  }

  private static Config tierConfig(SequentialExecutor executor) throws Exception {
    Method method =
        SequentialExecutor.class.getDeclaredMethod(
            "tierConfigFor",
            PlannedTask.Complexity.class,
            String.class,
            String.class,
            boolean.class,
            String.class);
    method.setAccessible(true);
    Object result = method.invoke(executor, PlannedTask.Complexity.LOW, "anthropic", "fallback", false, null);
    var warn = result.getClass().getDeclaredField("stagnationWarnAfter");
    var force = result.getClass().getDeclaredField("stagnationForceAfter");
    warn.setAccessible(true);
    force.setAccessible(true);
    return new Config(warn.getInt(result), force.getInt(result));
  }

  private static SequentialExecutor newExecutor(
      ModelResolver resolver, ModelMatrixProperties matrix, FeatureFlagService flags) {
    return new SequentialExecutor(
        mock(com.navneet.executespec.engine.WorkerAgent.class),
        mock(BuildProjectTool.class),
        new ObjectMapper(),
        mock(Metrics.class),
        mock(SseBroadcaster.class),
        mock(AgentTaskRepository.class),
        resolver,
        matrix,
        mock(RetrievalService.class),
        mock(WorkspaceTestRunner.class),
        flags,
        mock(ContextStore.class),
        mock(RunCostTracker.class),
        mock(com.navneet.executespec.deployment.GcsTaskCheckpointService.class),
        mock(com.navneet.executespec.router.AvailableProviderResolver.class),
        null,
        null);
  }
}

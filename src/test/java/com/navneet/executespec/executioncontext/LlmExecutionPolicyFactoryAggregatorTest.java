package com.navneet.executespec.executioncontext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.navneet.executespec.agent.ApiKeyResolver;
import com.navneet.executespec.agentsession.repository.AgentSessionRepository;
import com.navneet.executespec.auth.repository.OrgRepository;
import com.navneet.executespec.auth.repository.ProjectRepository;
import com.navneet.executespec.router.ModelMatrixProperties;
import com.navneet.executespec.router.ModelRegistry;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class LlmExecutionPolicyFactoryAggregatorTest {

  @Test
  void runRecoveryReusesImmutableContextAfterDerivedBudgetChanges() {
    LlmExecutionContextService contexts = mock(LlmExecutionContextService.class);
    PipelineRunRepository runs = mock(PipelineRunRepository.class);
    PipelineRun run = new PipelineRun();
    run.setRunId("run-1");
    run.setCreatedByUserId("user-1");
    run.setOrgId("org-1");
    run.setHardCapUsd(7.25);
    LlmExecutionScope scope = LlmExecutionScope.customer("org-1");
    LlmExecutionReference original = new LlmExecutionReference("llmctx-original", scope);
    when(runs.findById("run-1")).thenReturn(java.util.Optional.of(run));
    when(contexts.findReference(scope, "pipeline", "PIPELINE_RUN", "run-1", "route-v1"))
        .thenReturn(java.util.Optional.of(original));
    LlmExecutionPolicyFactory factory =
        new LlmExecutionPolicyFactory(
            contexts,
            runs,
            mock(AgentSessionRepository.class),
            mock(ProjectRepository.class),
            mock(OrgRepository.class),
            mock(ApiKeyResolver.class),
            mock(ModelRegistry.class));

    assertThat(factory.forRun("run-1")).isEqualTo(original);
    verify(contexts, never()).create(org.mockito.ArgumentMatchers.any());
  }

  @Test
  @SuppressWarnings("unchecked")
  void openRouterAllowListIncludesSupportedModelsOwnedByNativeProviders() {
    ModelRegistry registry = mock(ModelRegistry.class);
    ModelMatrixProperties.ModelConfig claude = new ModelMatrixProperties.ModelConfig();
    claude.setProvider("anthropic");
    when(registry.allModels()).thenReturn(Map.of("claude-sonnet-4-6", claude));
    LlmExecutionPolicyFactory factory =
        new LlmExecutionPolicyFactory(
            mock(LlmExecutionContextService.class),
            mock(PipelineRunRepository.class),
            mock(AgentSessionRepository.class),
            mock(ProjectRepository.class),
            mock(OrgRepository.class),
            mock(ApiKeyResolver.class),
            registry);

    Map<String, List<String>> models =
        ReflectionTestUtils.invokeMethod(factory, "modelsFor", Set.of("anthropic", "openrouter"));

    assertThat(models.get("anthropic")).containsExactly("claude-sonnet-4-6");
    assertThat(models.get("openrouter")).containsExactly("claude-sonnet-4-6");
  }
}

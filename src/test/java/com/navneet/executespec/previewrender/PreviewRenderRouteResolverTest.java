package com.navneet.executespec.previewrender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;

import com.navneet.executespec.router.AgentTier;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.router.ModelAccessService;
import com.navneet.executespec.router.ModelResolver;
import com.navneet.executespec.router.OrgModelOverrideService;
import com.navneet.executespec.router.ResolvedModel;
import com.navneet.executespec.router.RunModelOverrides;
import com.navneet.executespec.state.entity.PipelineRun;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class PreviewRenderRouteResolverTest {

  private final ModelResolver models = mock(ModelResolver.class);
  private final OrgModelOverrideService orgOverrides = mock(OrgModelOverrideService.class);
  private final ModelAccessService access = mock(ModelAccessService.class);
  private final PreviewRenderRouteResolver resolver =
      new PreviewRenderRouteResolver(models, orgOverrides, access);

  @AfterEach
  void clearRouteContext() {
    RunModelOverrides.clear();
  }

  @Test
  void exactPersistedPinIsValidatedAndReturnedUnchanged() {
    PipelineRun run = baseRun();
    run.setRoutePinned(true);
    run.setRoutingMode("EXACT_LOCK");
    run.setRequestedProvider("ANTHROPIC");
    run.setRequestedModel("claude-opus-non-default");

    ResolvedModel route = resolver.resolve(run);

    assertThat(route.getRequestedProvider()).isEqualTo(LlmProvider.ANTHROPIC);
    assertThat(route.getActualProvider()).isEqualTo(LlmProvider.ANTHROPIC);
    assertThat(route.getModel()).isEqualTo("claude-opus-non-default");
    assertThat(route.getAgentTier()).isEqualTo(AgentTier.WORKHORSE);
    assertThat(route.isFallback()).isFalse();
    verify(access).assertModelAllowed("user-1", "claude-opus-non-default");
    verify(access).assertProviderModelPair("ANTHROPIC", "claude-opus-non-default");
    verify(models, never()).resolveConfigured(LlmProvider.ANTHROPIC, AgentTier.WORKHORSE);
    verify(orgOverrides, never()).overridesForRun("billing-org");
  }

  @Test
  void unpinnedRunResolvesWorkhorseUnderPersistedBillingOrgOverride() {
    PipelineRun run = baseRun();
    String key = RunModelOverrides.key(LlmProvider.DEEPSEEK, AgentTier.WORKHORSE);
    when(orgOverrides.overridesForRun("billing-org"))
        .thenReturn(Map.of(key, "deepseek-org-workhorse"));
    when(models.resolveConfigured(LlmProvider.DEEPSEEK, AgentTier.WORKHORSE))
        .thenAnswer(
            ignored ->
                ResolvedModel.builder()
                    .requestedProvider(LlmProvider.DEEPSEEK)
                    .actualProvider(LlmProvider.DEEPSEEK)
                    .model(RunModelOverrides.lookup(LlmProvider.DEEPSEEK, AgentTier.WORKHORSE))
                    .agentTier(AgentTier.WORKHORSE)
                    .build());

    ResolvedModel route = resolver.resolve(run);

    assertThat(route.getModel()).isEqualTo("deepseek-org-workhorse");
    assertThat(RunModelOverrides.capture()).isEmpty();
    verify(access).assertProviderAllowed("user-1", "DEEPSEEK");
    verify(access).assertModelAllowed("user-1", "deepseek-org-workhorse");
    verify(access).assertProviderModelPair("DEEPSEEK", "deepseek-org-workhorse");
  }

  @Test
  void invalidPersistedProviderFailsClosedWithoutDefaultResolution() {
    PipelineRun run = baseRun();
    run.setRequestedProvider("retired-provider");

    assertThatThrownBy(() -> resolver.resolve(run))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("unknown persisted run provider");

    verify(models, never()).resolveConfigured(LlmProvider.DEEPSEEK, AgentTier.WORKHORSE);
  }

  private static PipelineRun baseRun() {
    PipelineRun run = new PipelineRun();
    run.setCreatedByUserId("user-1");
    run.setOrgId("host-org");
    run.setBillingOrgId("billing-org");
    run.setAiProvider("DEEPSEEK");
    run.setRequestedProvider("DEEPSEEK");
    run.setRoutePinned(false);
    return run;
  }
}

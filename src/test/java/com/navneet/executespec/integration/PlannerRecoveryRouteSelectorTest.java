package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;

import com.navneet.executespec.router.AgentTier;
import com.navneet.executespec.router.AvailableProviderResolver;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.router.ModelProviderCandidateService;
import com.navneet.executespec.router.ModelProviderScore;
import com.navneet.executespec.router.ModelResolver;
import com.navneet.executespec.router.ResolvedModel;

import org.junit.jupiter.api.Test;

class PlannerRecoveryRouteSelectorTest {

  @Test
  void exactRunPinBlocksBeforeCandidateCollection() {
    Fixture fixture = fixture();

    PlannerRecoveryRouteSelector.Selection result =
        fixture
            .selector()
            .select(
                "run",
                route(LlmProvider.OPENROUTER, "deepseek-v4-pro"),
                new PlannerRecoveryRouteSelector.Policy(true, false, false, 1, 1));

    assertThat(result.selected()).isFalse();
    assertThat(result.blockReason())
        .isEqualTo(PlannerRecoveryRouteSelector.BlockReason.ROUTE_PINNED);
    verify(fixture.candidates(), never()).buildCandidates(any());
  }

  @Test
  void confirmationPolicyBlocksAutomaticEscalation() {
    Fixture fixture = fixture();

    PlannerRecoveryRouteSelector.Selection result =
        fixture
            .selector()
            .select(
                "run",
                route(LlmProvider.OPENROUTER, "deepseek-v4-pro"),
                new PlannerRecoveryRouteSelector.Policy(false, true, false, 1, 1));

    assertThat(result.blockReason())
        .isEqualTo(PlannerRecoveryRouteSelector.BlockReason.CONFIRMATION_REQUIRED);
    verify(fixture.available(), never()).resolve(any());
  }

  @Test
  void selectsHigherQualityRouteOnlyFromEffectivePlanningMatrix() {
    Fixture fixture = fixture();
    ResolvedModel current = route(LlmProvider.OPENROUTER, "deepseek-v4-pro");
    ResolvedModel matrixStepUp = route(LlmProvider.ANTHROPIC, "claude-sonnet-4-6");
    when(fixture.available().resolve("run"))
        .thenReturn(Set.of(LlmProvider.OPENROUTER, LlmProvider.ANTHROPIC));
    when(fixture
            .models()
            .resolve("OutlinePlannerAgent", LlmProvider.OPENROUTER, AgentTier.PLANNING))
        .thenReturn(current);
    when(fixture.models().resolve("OutlinePlannerAgent", LlmProvider.ANTHROPIC, AgentTier.PLANNING))
        .thenReturn(matrixStepUp);
    when(fixture.candidates().buildCandidates(any()))
        .thenReturn(
            List.of(
                score("openrouter", "deepseek-v4-pro", 70, false),
                score("anthropic", "claude-sonnet-4-6", 92, true),
                // Higher score but not the Enterprise/effective PLANNING cell: must be ignored.
                score("anthropic", "claude-opus-4-7", 99, true)));

    PlannerRecoveryRouteSelector.Selection result =
        fixture
            .selector()
            .select(
                "run",
                current,
                PlannerRecoveryRouteSelector.Policy.runAuthorized(false, false, false));

    assertThat(result.selected()).isTrue();
    assertThat(result.route()).isSameAs(matrixStepUp);
  }

  @Test
  void benchmarkRequirementBlocksUnbenchmarkedMatrixStepUp() {
    Fixture fixture = fixture();
    ResolvedModel current = route(LlmProvider.OPENROUTER, "deepseek-v4-pro");
    ResolvedModel stepUp = route(LlmProvider.ANTHROPIC, "claude-sonnet-4-6");
    when(fixture.available().resolve("run"))
        .thenReturn(Set.of(LlmProvider.OPENROUTER, LlmProvider.ANTHROPIC));
    when(fixture.models().resolve(eq("OutlinePlannerAgent"), any(), eq(AgentTier.PLANNING)))
        .thenAnswer(
            invocation -> invocation.getArgument(1) == LlmProvider.OPENROUTER ? current : stepUp);
    when(fixture.candidates().buildCandidates(any()))
        .thenReturn(
            List.of(
                score("openrouter", "deepseek-v4-pro", 70, false),
                score("anthropic", "claude-sonnet-4-6", 92, false)));

    PlannerRecoveryRouteSelector.Selection result =
        fixture
            .selector()
            .select(
                "run",
                current,
                PlannerRecoveryRouteSelector.Policy.runAuthorized(false, false, true));

    assertThat(result.blockReason())
        .isEqualTo(PlannerRecoveryRouteSelector.BlockReason.BENCHMARK_EVIDENCE_REQUIRED);
  }

  private static Fixture fixture() {
    AvailableProviderResolver available = mock(AvailableProviderResolver.class);
    ModelResolver models = mock(ModelResolver.class);
    ModelProviderCandidateService candidates = mock(ModelProviderCandidateService.class);
    return new Fixture(
        new PlannerRecoveryRouteSelector(available, models, candidates),
        available,
        models,
        candidates);
  }

  private static ResolvedModel route(LlmProvider provider, String model) {
    return ResolvedModel.builder()
        .requestedProvider(provider)
        .actualProvider(provider)
        .model(model)
        .agentTier(AgentTier.PLANNING)
        .build();
  }

  private static ModelProviderScore score(
      String provider, String model, double quality, boolean benchmark) {
    return new ModelProviderScore(
        provider,
        model,
        "PLANNING_CONTRACT_REPAIR",
        quality,
        1.0,
        100,
        200,
        0,
        benchmark,
        true,
        true,
        List.of("test"),
        true,
        0);
  }

  private record Fixture(
      PlannerRecoveryRouteSelector selector,
      AvailableProviderResolver available,
      ModelResolver models,
      ModelProviderCandidateService candidates) {}
}

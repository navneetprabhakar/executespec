package com.navneet.executespec.cost;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.util.List;

import com.navneet.executespec.agent.PlanStrategy;
import com.navneet.executespec.pricing.ModelPricingRepository;
import com.navneet.executespec.router.AgentTier;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.router.ModelResolver;
import com.navneet.executespec.router.ResolvedModel;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RunCostTrackerPinHistoryCompatibilityTest {

  private static final String PRO = "deepseek-v4-pro-native";
  private static final String FLASH = "deepseek-v4-flash-native";

  private RunCostTracker tracker;

  @BeforeEach
  void setUp() {
    ModelResolver resolver = mock(ModelResolver.class);
    route(resolver, AgentTier.PLANNING, PRO);
    route(resolver, AgentTier.WORKHORSE, PRO);
    route(resolver, AgentTier.LIGHTWEIGHT, FLASH);
    tracker = new RunCostTracker(mock(ModelPricingRepository.class), resolver);
  }

  @Test
  void deepSeekLowSingleShotIncludesTheLightweightTitleSummarizerRoute() {
    assertThat(
            tracker.expectedRouteFingerprint(
                "deepseek", List.of("LOW"), PlanStrategy.SINGLE_SHOT, null))
        .as("SINGLE_SHOT still performs the successful LIGHTWEIGHT title-summary call")
        .isEqualTo("DEEPSEEK:" + FLASH + ",DEEPSEEK:" + PRO);
  }

  @Test
  void pinThatDiffersFromAUsedTierCannotReuseModelBlindHistory() {
    assertThat(
            tracker.expectedRouteFingerprint("deepseek", List.of("LOW"), PlanStrategy.SMALL, null))
        .as("SMALL pays for both Flash classifier and Pro planner/reviewer")
        .isEqualTo("DEEPSEEK:" + FLASH + ",DEEPSEEK:" + PRO);
  }

  @Test
  void lowLightweightStartIsIncludedInTheExpectedPhysicalRouteSet() {
    assertThat(
            tracker.expectedRouteFingerprint(
                "deepseek", List.of("LOW"), PlanStrategy.SINGLE_SHOT, "LIGHTWEIGHT"))
        .isEqualTo("DEEPSEEK:" + FLASH + ",DEEPSEEK:" + PRO);
  }

  private static void route(ModelResolver resolver, AgentTier tier, String model) {
    ResolvedModel resolved =
        ResolvedModel.builder()
            .requestedProvider(LlmProvider.DEEPSEEK)
            .actualProvider(LlmProvider.DEEPSEEK)
            .model(model)
            .agentTier(tier)
            .build();
    lenient()
        .when(resolver.resolveConfigured(eq(LlmProvider.DEEPSEEK), eq(tier)))
        .thenReturn(resolved);
  }
}

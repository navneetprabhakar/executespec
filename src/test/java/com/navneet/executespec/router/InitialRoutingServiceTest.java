package com.navneet.executespec.router;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

class InitialRoutingServiceTest {

  private final ModelProviderCandidateService candidates =
      mock(ModelProviderCandidateService.class);
  private final InitialRoutingService service = new InitialRoutingService(candidates);

  @Test
  void observedTaskOutcomeQualityWinsBeforeCostAndLatency() {
    ModelProviderScore cheapFastButWeak =
        score(
            "deepseek",
            "cheap",
            82.0,
            0.10,
            100,
            "persisted BUG_FIX validation pass rate 82.00% from 10 tasks");
    ModelProviderScore knownStrong =
        score(
            "anthropic",
            "sonnet",
            94.0,
            5.00,
            2_000,
            "persisted BUG_FIX validation pass rate 94.00% from 10 tasks");
    when(candidates.buildCandidates(null)).thenReturn(List.of(cheapFastButWeak, knownStrong));

    InitialRoutingDecision decision = service.decide("deepseek", "cheap", null);

    assertThat(decision.routeLabel()).isEqualTo("anthropic/sonnet");
    assertThat(decision.changed()).isTrue();
    assertThat(decision.observedQualityEvidenceUsed()).isTrue();
    assertThat(decision.reasons()).anyMatch(reason -> reason.contains("observed quality evidence"));
  }

  @Test
  void unavailableProviderIsRejectedEvenWhenItsScoreWouldOtherwiseQualify() {
    ModelProviderScore unavailable =
        score(
            "openai",
            "gpt",
            99.0,
            0.01,
            1,
            "persisted BUG_FIX validation pass rate 99.00% from 10 tasks",
            "provider unavailable; candidate is forced below threshold");
    ModelProviderScore available =
        score(
            "anthropic",
            "sonnet",
            80.0,
            1.00,
            100,
            "persisted BUG_FIX validation pass rate 80.00% from 10 tasks");
    when(candidates.buildCandidates(null)).thenReturn(List.of(unavailable, available));

    InitialRoutingDecision decision = service.decide("anthropic", "sonnet", null);

    assertThat(decision.routeLabel()).isEqualTo("anthropic/sonnet");
    assertThat(decision.rejectedCandidates())
        .extracting(ModelProviderScore::label)
        .containsExactly("openai/gpt");
  }

  @Test
  void fallbackQualityDoesNotOverrideCurrentRouteAndDecisionExplainsWhy() {
    ModelProviderScore fallback =
        score(
            "anthropic",
            "sonnet",
            90.0,
            1.00,
            100,
            "quality 90.00 from fallback score; benchmark/local validation signals unavailable");
    when(candidates.buildCandidates(null)).thenReturn(List.of(fallback));

    InitialRoutingDecision decision = service.decide("deepseek", "cheap", null);

    assertThat(decision.routeLabel()).isEqualTo("deepseek/cheap");
    assertThat(decision.changed()).isFalse();
    assertThat(decision.observedQualityEvidenceUsed()).isFalse();
    assertThat(decision.reasons())
        .anyMatch(reason -> reason.contains("no observed quality evidence"));
  }

  private static ModelProviderScore score(
      String provider, String model, double quality, double cost, long latency, String... reasons) {
    return new ModelProviderScore(
        provider, model, "BUG_FIX", quality, cost, latency, latency, 70.0, false, List.of(reasons));
  }
}

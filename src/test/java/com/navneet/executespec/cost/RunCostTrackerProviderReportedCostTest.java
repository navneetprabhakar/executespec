package com.navneet.executespec.cost;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.util.Map;
import java.util.Optional;

import com.navneet.executespec.pricing.ModelPricing;
import com.navneet.executespec.pricing.ModelPricingRepository;
import com.navneet.executespec.router.ModelResolver;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;

@DisplayName("RunCostTracker — provider-reported billing")
class RunCostTrackerProviderReportedCostTest {

  private ModelPricingRepository pricingRepo;
  private RunCostTracker tracker;

  @BeforeEach
  void setUp() {
    pricingRepo = mock(ModelPricingRepository.class);
    tracker = new RunCostTracker(pricingRepo, mock(ModelResolver.class));
  }

  @Test
  @DisplayName("OpenRouter uses provider-reported cost when returned by the gateway")
  void openRouterUsesProviderReportedCost() {
    seedPricing("openrouter", "glm-5.2", 0.93, 0.93, 3.00);

    RunCostTracker.CallUsage usage =
        tracker.record(
            "run-or",
            "openrouter",
            "glm-5.2",
            responseWith(
                1_000_000,
                100_000,
                Map.of("cache_read_input_tokens", 900_000, "provider_reported_cost_usd", 0.42)));

    assertThat(usage.costUsd()).isEqualTo(0.42, within(0.000001));
    assertThat(tracker.snapshot("run-or").costUsd()).isEqualTo(0.42, within(0.000001));
  }

  @Test
  @DisplayName("Direct providers ignore provider-reported cost metadata")
  void directProviderIgnoresProviderReportedCost() {
    seedPricing("openai", "gpt-5.4", 2.50, 1.25, 15.00);

    RunCostTracker.CallUsage usage =
        tracker.record(
            "run-oa",
            "openai",
            "gpt-5.4",
            responseWith(
                1_000_000,
                100_000,
                Map.of("cache_read_input_tokens", 900_000, "provider_reported_cost_usd", 0.42)));

    assertThat(usage.costUsd()).isEqualTo(2.875, within(0.000001));
    assertThat(tracker.snapshot("run-oa").costUsd()).isEqualTo(2.875, within(0.000001));
  }

  private void seedPricing(
      String provider, String model, double inRate, double cachedRate, double outRate) {
    ModelPricing p = new ModelPricing();
    p.setInputCostPer1m(inRate);
    p.setCachedInputCostPer1m(cachedRate);
    p.setOutputCostPer1m(outRate);
    lenient()
        .when(pricingRepo.findByIdProviderAndIdModel(provider, model))
        .thenReturn(Optional.of(p));
  }

  private ChatResponse responseWith(int prompt, int completion, Map<String, Object> metadata) {
    Usage usage = mock(Usage.class);
    lenient().when(usage.getPromptTokens()).thenReturn(prompt);
    lenient().when(usage.getCompletionTokens()).thenReturn(completion);

    ChatResponseMetadata meta = mock(ChatResponseMetadata.class);
    lenient().when(meta.getUsage()).thenReturn(usage);
    lenient().when(meta.get(anyString())).thenAnswer(inv -> metadata.get(inv.getArgument(0)));

    ChatResponse response = mock(ChatResponse.class);
    lenient().when(response.getMetadata()).thenReturn(meta);
    return response;
  }
}

package com.navneet.executespec.router;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class ProviderAvailabilityMapTest {

  @Test
  void mapsKnownProvidersToAvailabilityAndKeepsGeminiAliasesAvailable() {
    var availability =
        ProviderAvailabilityMap.fromAvailableProviders(
            Set.of(LlmProvider.ANTHROPIC, LlmProvider.GEMINI),
            List.of("anthropic", "openai", "gemini", "google"));

    assertThat(availability)
        .containsEntry("anthropic", true)
        .containsEntry("openai", false)
        .containsEntry("gemini", true)
        .containsEntry("google", true);
  }

  @Test
  void returnsEmptyWhenResolverHasNoProviderEvidence() {
    assertThat(ProviderAvailabilityMap.fromAvailableProviders(Set.of(), List.of("anthropic")))
        .isEmpty();
  }
}

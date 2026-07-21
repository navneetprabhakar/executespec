package com.navneet.executespec.router;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ResolvedModelTest {

  @Test
  void providerNameReturnsTheActualProviderWhenPresent() {
    ResolvedModel resolved =
        ResolvedModel.builder()
            .requestedProvider(LlmProvider.ANTHROPIC)
            .actualProvider(LlmProvider.ANTHROPIC)
            .model("claude-sonnet-5")
            .agentTier(AgentTier.WORKHORSE)
            .build();

    assertThat(resolved.providerName()).isEqualTo("anthropic");
  }

  @Test
  void providerNameFailsClosedWhenActualProviderIsMissing() {
    // RR-364: a ResolvedModel with no actual provider is a broken routing invariant. It must throw
    // rather than silently returning "openai" and spending against a provider that was never
    // chosen.
    ResolvedModel broken =
        ResolvedModel.builder()
            .requestedProvider(LlmProvider.ANTHROPIC)
            .model("claude-sonnet-5")
            .agentTier(AgentTier.WORKHORSE)
            .build();

    assertThatThrownBy(broken::providerName)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no actual provider");
  }
}

package com.navneet.executespec.benchmark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/** RR-954 default-model route truth + RR-1356 leaderboard-URL allowlist. */
class BenchmarkRouteAndUrlHardeningTest {
  private final com.navneet.executespec.router.ModelRegistry registry =
      org.mockito.Mockito.mock(com.navneet.executespec.router.ModelRegistry.class);

  @org.junit.jupiter.api.BeforeEach
  void configuredRoutes() {
    org.mockito.Mockito.when(registry.planningModelFor("anthropic")).thenReturn("claude-sonnet-5");
    org.mockito.Mockito.when(registry.planningModelFor("openai")).thenReturn("gpt-5.4");
    org.mockito.Mockito.when(registry.planningModelFor("openrouter")).thenReturn("deepseek-v4-pro");
    org.mockito.Mockito.when(registry.planningModelFor("fireworks"))
        .thenReturn("deepseek-v4p1-flash-fw");
  }

  // RR-954 — no non-Anthropic provider may silently resolve to an Anthropic model.
  @Test
  void defaultPlanningModelMapsEachProviderToItsOwnRoute() {
    assertThat(BenchmarkGenerationService.defaultPlanningModel(registry, "anthropic"))
        .isEqualTo("claude-sonnet-5");
    assertThat(BenchmarkGenerationService.defaultPlanningModel(registry, "openai"))
        .isEqualTo("gpt-5.4");
    assertThat(BenchmarkGenerationService.defaultPlanningModel(registry, "openrouter"))
        .isEqualTo("deepseek-v4-pro");
    assertThat(BenchmarkGenerationService.defaultPlanningModel(registry, "fireworks"))
        .isEqualTo("deepseek-v4p1-flash-fw");
  }

  // RR-954 — an unknown or blank provider fails closed instead of defaulting to claude-sonnet-5.
  @Test
  void defaultPlanningModelFailsClosedOnUnknownProvider() {
    assertThatThrownBy(() -> BenchmarkGenerationService.defaultPlanningModel(registry, "mystery"))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> BenchmarkGenerationService.defaultPlanningModel(registry, " "))
        .isInstanceOf(ResponseStatusException.class);
  }

  // RR-1356 — only well-formed https URLs survive; every other scheme/shape is dropped.
  @Test
  void leaderboardUrlAllowlistsHttpsOnly() {
    assertThat(BenchmarkTrackingService.sanitizeLeaderboardUrl("https://swebench.com/run/1"))
        .isEqualTo("https://swebench.com/run/1");
    assertThat(BenchmarkTrackingService.sanitizeLeaderboardUrl("http://swebench.com/run/1"))
        .isNull();
    assertThat(BenchmarkTrackingService.sanitizeLeaderboardUrl("javascript:alert(1)")).isNull();
    assertThat(BenchmarkTrackingService.sanitizeLeaderboardUrl("data:text/html,<b>x</b>")).isNull();
    assertThat(BenchmarkTrackingService.sanitizeLeaderboardUrl("not a url")).isNull();
    assertThat(BenchmarkTrackingService.sanitizeLeaderboardUrl("https://")).isNull();
    assertThat(BenchmarkTrackingService.sanitizeLeaderboardUrl(null)).isNull();
  }
}

package com.navneet.executespec.benchmark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/** RR-954 default-model route truth + RR-1356 leaderboard-URL allowlist. */
class BenchmarkRouteAndUrlHardeningTest {

  // RR-954 — no non-Anthropic provider may silently resolve to an Anthropic model.
  @Test
  void defaultPlanningModelMapsEachProviderToItsOwnRoute() {
    assertThat(BenchmarkGenerationService.defaultPlanningModel("anthropic"))
        .isEqualTo("claude-sonnet-5");
    assertThat(BenchmarkGenerationService.defaultPlanningModel("openai")).isEqualTo("gpt-5.4");
    assertThat(BenchmarkGenerationService.defaultPlanningModel("openrouter"))
        .isEqualTo("deepseek-v4-pro");
    assertThat(BenchmarkGenerationService.defaultPlanningModel("fireworks"))
        .isEqualTo("deepseek-v4-pro-fw");
  }

  // RR-954 — an unknown or blank provider fails closed instead of defaulting to claude-sonnet-5.
  @Test
  void defaultPlanningModelFailsClosedOnUnknownProvider() {
    assertThatThrownBy(() -> BenchmarkGenerationService.defaultPlanningModel("mystery"))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> BenchmarkGenerationService.defaultPlanningModel(" "))
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

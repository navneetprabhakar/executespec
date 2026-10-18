package com.navneet.executespec.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;

import com.navneet.executespec.tool.ExecutionContext;

import org.junit.jupiter.api.Test;

class OpenRouterBoundedReasoningTest {
  @Test
  void deepSeekReviewAndStructuredTransformsDisableReasoningWithoutDroppingRoutingHints() {
    var review = request("deepseek-v4-flash", "__root__-reviewer-general", "ReviewerAgent");
    assertThat(LlmRouter.openRouterExtraBody(review))
        .containsEntry("reasoning", Map.of("enabled", false))
        .containsEntry("provider", Map.of("sort", "latency"))
        .containsEntry("usage", Map.of("include", true));
    assertThat(
            LlmRouter.openRouterExtraBody(
                request("deepseek-v4-flash", "T1", "OutlinePlannerAgent")))
        .containsEntry("reasoning", Map.of("enabled", false));
  }

  @Test
  void generationAndOtherModelsKeepTheirExistingReasoningPolicy() {
    assertThat(LlmRouter.openRouterExtraBody(request("deepseek-v4-flash", "T1", "WorkerAgent")))
        .doesNotContainKey("reasoning");
    assertThat(
            LlmRouter.openRouterExtraBody(
                request("gpt-5.4", "__root__-reviewer-general", "ReviewerAgent")))
        .doesNotContainKey("reasoning");
  }

  @Test
  void fireworksUsesItsOwnBoundedReasoningParameterAndPreservesNormalWorkers() {
    assertThat(
            LlmRouter.fireworksExtraBody(
                request("deepseek-v4p1-flash-fw", "__root__-reviewer-general", "ReviewerAgent")))
        .containsEntry("reasoning_effort", "none");
    assertThat(LlmRouter.fireworksExtraBody(request("deepseek-v4p1-flash-fw", "T1", "WorkerAgent")))
        .isEmpty();
  }

  private LlmRequest request(String model, String task, String role) {
    return LlmRequest.builder()
        .provider("openrouter")
        .model(model)
        .agentRole(role)
        .messages(java.util.List.of(new org.springframework.ai.chat.messages.UserMessage("review")))
        .context(new ExecutionContext("run-1", task, Path.of("/tmp/workspace")))
        .build();
  }
}

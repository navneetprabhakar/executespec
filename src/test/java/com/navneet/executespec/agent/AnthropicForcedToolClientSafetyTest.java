package com.navneet.executespec.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.api.AnthropicApi;

/**
 * RR-1181 — the forced Anthropic salvage path must expose tool calls only when the provider
 * terminated with the documented complete {@code tool_use} stop reason. An abnormal stop reason
 * (max_tokens, refusal, …) must fail closed so no truncated/declined response is dispatched as an
 * authorized mutation.
 */
class AnthropicForcedToolClientSafetyTest {

  private final AnthropicForcedToolClient client =
      new AnthropicForcedToolClient(new ObjectMapper());

  private static AnthropicApi.ChatCompletionResponse responseWithToolUse(String stopReason) {
    AnthropicApi.ContentBlock toolUse =
        new AnthropicApi.ContentBlock(
            AnthropicApi.ContentBlock.Type.TOOL_USE, "tool-id-1", "editFile", Map.of("path", "a"));
    return new AnthropicApi.ChatCompletionResponse(
        "resp-1",
        "message",
        AnthropicApi.Role.ASSISTANT,
        List.of(toolUse),
        "claude-test",
        stopReason,
        null,
        null,
        null);
  }

  @Test
  void usableWhenStopReasonIsToolUse() throws Exception {
    var outcome = client.buildForcedOutcome(responseWithToolUse("tool_use"), "claude-test");
    assertThat(outcome.usable()).isTrue();
    assertThat(outcome.attempted()).isTrue();
  }

  @Test
  void failsClosedWhenStopReasonIsMaxTokens() throws Exception {
    var outcome = client.buildForcedOutcome(responseWithToolUse("max_tokens"), "claude-test");
    // Response is retained for audit/usage, but NOT usable — LlmRouter falls through to auto.
    assertThat(outcome.usable()).isFalse();
    assertThat(outcome.attempted()).isTrue();
    assertThat(outcome.response()).isNotNull();
  }

  @Test
  void failsClosedWhenStopReasonIsRefusal() throws Exception {
    var outcome = client.buildForcedOutcome(responseWithToolUse("refusal"), "claude-test");
    assertThat(outcome.usable()).isFalse();
  }
}

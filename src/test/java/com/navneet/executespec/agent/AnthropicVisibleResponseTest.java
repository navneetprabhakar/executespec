package com.navneet.executespec.agent;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

class AnthropicVisibleResponseTest {
  @Test
  void thinkingAndRedactedBlocksCannotMaskVisibleJsonOrLeakIntoIt() {
    var metadata = ChatResponseMetadata.builder().usage(new DefaultUsage(120, 80)).build();
    var response =
        new ChatResponse(
            List.of(
                new Generation(
                    AssistantMessage.builder()
                        .content("private reasoning")
                        .properties(Map.of("signature", "opaque-signature"))
                        .build()),
                new Generation(
                    AssistantMessage.builder()
                        .content("")
                        .properties(Map.of("data", "opaque-redacted-data"))
                        .build()),
                new Generation(new AssistantMessage("{\"strategy\":\"SMALL\"}"))),
            metadata);
    var visible = AnthropicVisibleResponse.normalize(response);
    assertThat(visible.getResult().getOutput().getText()).isEqualTo("{\"strategy\":\"SMALL\"}");
    assertThat(visible.getResult().getOutput().getMetadata())
        .doesNotContainKeys("signature", "data");
    assertThat(visible.getMetadata()).isSameAs(metadata);
    assertThat(visible.getMetadata().getUsage().getTotalTokens()).isEqualTo(200);
  }

  @Test
  void allVisibleTextAndToolCallsSurviveNormalization() {
    var call =
        new AssistantMessage.ToolCall("call-1", "function", "readFile", "{\"path\":\"main.py\"}");
    var response =
        new ChatResponse(
            List.of(
                new Generation(new AssistantMessage("First")),
                new Generation(
                    AssistantMessage.builder()
                        .content("Second")
                        .toolCalls(List.of(call))
                        .build())));
    var visible = AnthropicVisibleResponse.normalize(response).getResult().getOutput();
    assertThat(visible.getText()).isEqualTo("First\nSecond");
    assertThat(visible.getToolCalls()).containsExactly(call);
  }

  @Test
  void thinkingOnlyOutputDoesNotBecomeAnAnswerAndRetainsTheStopReason() {
    var response =
        new ChatResponse(
            List.of(
                new Generation(
                    AssistantMessage.builder()
                        .content("secret")
                        .properties(Map.of("signature", "opaque"))
                        .build(),
                    ChatGenerationMetadata.builder().finishReason("max_tokens").build())));
    var visible = AnthropicVisibleResponse.normalize(response);
    assertThat(visible.getResult().getOutput().getText()).isEmpty();
    assertThat(visible.getResult().getMetadata().getFinishReason()).isEqualTo("max_tokens");
  }
}

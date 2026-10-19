package com.navneet.executespec.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

/** Spring AI emits thinking as separate generations; only visible content is application output. */
final class AnthropicVisibleResponse {
  private AnthropicVisibleResponse() {}

  static ChatResponse normalize(ChatResponse response) {
    if (response == null || response.getResults().isEmpty()) return response;
    var text = new ArrayList<String>();
    var calls = new ArrayList<AssistantMessage.ToolCall>();
    var properties = new LinkedHashMap<String, Object>();
    Generation visible = null;
    for (var generation : response.getResults()) {
      var output = generation.getOutput();
      // These are the exact properties Spring AI 1.1 assigns to thinking/redacted_thinking blocks.
      if (output.getMetadata().containsKey("signature") || output.getMetadata().containsKey("data"))
        continue;
      if (visible == null) visible = generation;
      if (output.getText() != null && !output.getText().isBlank()) text.add(output.getText());
      if (output.getToolCalls() != null) calls.addAll(output.getToolCalls());
      properties.putAll(output.getMetadata());
    }
    var assistant =
        AssistantMessage.builder()
            .content(String.join("\n", text))
            .toolCalls(calls)
            .properties(properties)
            .build();
    var metadata = visible == null ? response.getResult().getMetadata() : visible.getMetadata();
    return new ChatResponse(List.of(new Generation(assistant, metadata)), response.getMetadata());
  }
}

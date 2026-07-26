package com.navneet.executespec.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

class OpenAiMessageSerializerTest {
  private final ArrayNode messages = new ObjectMapper().createArrayNode();

  @Test
  void preservesConversationRolesToolIdsAndDeepSeekReasoning() {
    OpenAiMessageSerializer.append(messages, new SystemMessage("system"));
    OpenAiMessageSerializer.append(messages, new UserMessage("question"));
    OpenAiMessageSerializer.append(
        messages,
        AssistantMessage.builder()
            .content("")
            .properties(
                Map.of(OpenAiNativeToolClient.REASONING_CONTENT_META_KEY, "private-reasoning"))
            .toolCalls(
                List.of(
                    new AssistantMessage.ToolCall(
                        "call-1", "function", "readFile", "{\"path\":\"README.md\"}")))
            .build());
    OpenAiMessageSerializer.append(
        messages,
        ToolResponseMessage.builder()
            .responses(
                List.of(
                    new ToolResponseMessage.ToolResponse("call-1", "readFile", "{\"ok\":true}")))
            .build());

    assertThat(messages).hasSize(4);
    assertThat(messages.get(0).path("role").asText()).isEqualTo("system");
    assertThat(messages.get(1).path("role").asText()).isEqualTo("user");
    assertThat(messages.get(2).path("reasoning_content").asText()).isEqualTo("private-reasoning");
    assertThat(messages.get(2).path("tool_calls").get(0).path("id").asText()).isEqualTo("call-1");
    assertThat(messages.get(3).path("tool_call_id").asText()).isEqualTo("call-1");
  }
}

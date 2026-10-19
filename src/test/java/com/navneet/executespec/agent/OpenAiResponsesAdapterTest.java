package com.navneet.executespec.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.test.util.ReflectionTestUtils;

class OpenAiResponsesAdapterTest {
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void selectsResponsesOnlyForCurrentDirectOpenAiModels() {
    for (String model : List.of("gpt-6-luna", "gpt-6.1-sol", "gpt-6-astra")) {
      assertThat(OpenAiResponsesAdapter.required(model, "https://api.openai.com/v1")).isTrue();
      assertThat(OpenAiResponsesAdapter.required(model, "https://openrouter.ai/api")).isFalse();
    }
    assertThat(OpenAiResponsesAdapter.required("gpt-5.4", "https://api.openai.com")).isFalse();
    assertThat(
            OpenAiResponsesAdapter.required(
                "gpt-6-luna", "https://api.openai.com.attacker.invalid"))
        .isFalse();
  }

  @Test
  void serializesMultiTurnToolIdentityAndReplaysModelBoundReasoning() throws Exception {
    var output =
        mapper.readTree(
            """
            [{"type":"reasoning","id":"rs_one","summary":[],"encrypted_content":"opaque"},
            {"type":"function_call","call_id":"call_one","name":"lookup","arguments":"{}"}]
            """);
    java.util.List<org.springframework.ai.chat.messages.Message> messages =
        List.of(
            new UserMessage("look it up"),
            AssistantMessage.builder()
                .content("")
                .toolCalls(
                    List.of(new AssistantMessage.ToolCall("call_one", "function", "lookup", "{}")))
                .properties(
                    Map.of(
                        OpenAiResponsesAdapter.OUTPUT,
                        mapper.convertValue(output, List.class),
                        OpenAiResponsesAdapter.MODEL,
                        "gpt-6.1-sol"))
                .build(),
            ToolResponseMessage.builder()
                .responses(
                    List.of(new ToolResponseMessage.ToolResponse("call_one", "lookup", "42")))
                .build());
    var request =
        OpenAiNativeToolClient.OpenAiToolCallRequest.builder()
            .model("gpt-6.1-sol")
            .messages(messages)
            .tools(List.of())
            .temperature(0.2)
            .maxCompletionTokens(1500)
            .build();
    var client = new OpenAiNativeToolClient(mapper, null);
    ObjectNode chat = ReflectionTestUtils.invokeMethod(client, "buildRequestBody", request);
    var body = OpenAiResponsesAdapter.request(mapper, chat, request);
    assertThat(body.has("messages")).isFalse();
    assertThat(body.has("temperature")).isFalse();
    assertThat(body.path("store").asBoolean()).isFalse();
    assertThat(body.path("max_output_tokens").asInt()).isEqualTo(1500);
    assertThat(body.path("input").get(1).path("encrypted_content").asText()).isEqualTo("opaque");
    assertThat(body.path("input").get(2).path("call_id").asText()).isEqualTo("call_one");
    assertThat(body.path("input").get(3).path("type").asText()).isEqualTo("function_call_output");
    var other =
        OpenAiNativeToolClient.OpenAiToolCallRequest.builder()
            .model("gpt-6-luna")
            .messages(messages)
            .tools(List.of())
            .build();
    var changed = OpenAiResponsesAdapter.request(mapper, chat, other);
    assertThat(changed.toString()).doesNotContain("opaque");
    assertThat(changed.toString()).contains("call_one");
  }

  @Test
  void normalizesToolsAndCachedUsageAndPreservesEncryptedReasoning() throws Exception {
    var raw =
        mapper.readTree(
            """
{"id":"resp_one","model":"gpt-6.1-sol","status":"completed","output":[
{"type":"reasoning","id":"rs_one","summary":[],"encrypted_content":"opaque"},
{"type":"function_call","call_id":"call_one","name":"lookup","arguments":"{}"}],
"usage":{"input_tokens":100,"output_tokens":20,"input_tokens_details":{"cached_tokens":50}}}
""");
    ObjectNode normalized = OpenAiResponsesAdapter.response(mapper, raw);
    var client = new OpenAiNativeToolClient(mapper, null);
    org.springframework.ai.chat.model.ChatResponse parsed =
        ReflectionTestUtils.invokeMethod(client, "parseResponse", normalized, "gpt-6.1-sol");
    assertThat(parsed.getResult().getOutput().getToolCalls().getFirst().id()).isEqualTo("call_one");
    assertThat(parsed.getResult().getOutput().getMetadata())
        .containsKey(OpenAiResponsesAdapter.OUTPUT);
    assertThat(parsed.getMetadata().getUsage().getPromptTokens()).isEqualTo(100);
    assertThat((Integer) parsed.getMetadata().get("cache_read_input_tokens")).isEqualTo(50);
    ((ObjectNode) raw).put("status", "incomplete");
    assertThat(
            OpenAiResponsesAdapter.response(mapper, raw)
                .path("choices")
                .get(0)
                .path("message")
                .has(OpenAiResponsesAdapter.OUTPUT))
        .isFalse();
    assertThat(
            OpenAiResponsesAdapter.response(mapper, raw)
                .path("choices")
                .get(0)
                .path("message")
                .path("tool_calls"))
        .isEmpty();
  }

  @Test
  void absentUsageIsNotFabricatedAsZeroTokenSuccess() throws Exception {
    var raw =
        mapper.readTree(
            "{\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"done\"}]}]}");
    assertThat(OpenAiResponsesAdapter.response(mapper, raw).has("usage")).isFalse();
  }

  @Test
  void refusesMalformedOrFailedResponsesBeforeToolExecution() throws Exception {
    for (String body :
        List.of(
            "{}",
            "{\"status\":\"completed\",\"output\":[]}",
            "{\"status\":\"failed\",\"output\":[]}",
            "{\"status\":\"completed\",\"output\":[{\"type\":\"function_call\",\"name\":\"write\"}]}")) {
      var raw = mapper.readTree(body);
      assertThatThrownBy(() -> OpenAiResponsesAdapter.response(mapper, raw))
          .isInstanceOf(IllegalStateException.class);
    }
  }
}

package com.navneet.executespec.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.test.util.ReflectionTestUtils;

/** Protocol / fail-closed behaviour of the OpenAI-wire response parser (RR-1082, RR-1083). */
class OpenAiNativeToolClientProtocolTest {

  private final ObjectMapper mapper = new ObjectMapper();
  private final OpenAiNativeToolClient client = new OpenAiNativeToolClient(mapper);

  private ChatResponse parse(String json) throws Exception {
    JsonNode node = mapper.readTree(json);
    return ReflectionTestUtils.invokeMethod(client, "parseResponse", node, "gpt-test");
  }

  @Test
  void synthesizesStableIdWhenToolCallOmitsIt() throws Exception {
    // RR-1082 — a tool call with no id must receive one stable id at normalization so dispatch and
    // the tool result pair correctly on the next turn.
    ChatResponse resp =
        parse(
            """
            {"choices":[{"message":{"content":null,"tool_calls":[
              {"type":"function","function":{"name":"editFile","arguments":"{}"}}]},
              "finish_reason":"tool_calls"}]}
            """);

    AssistantMessage msg = (AssistantMessage) resp.getResults().get(0).getOutput();
    assertThat(msg.getToolCalls()).hasSize(1);
    String id = msg.getToolCalls().get(0).id();
    assertThat(id).isNotBlank();
    assertThat(id).startsWith("call_");
  }

  @Test
  void emptyChoicesArrayFailsClosed() {
    // RR-1083 — a 2xx body with empty choices is malformed, not a zero-content success.
    assertThatThrownBy(() -> parse("{\"choices\":[]}"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("empty choices");
  }

  @Test
  void toolCallWithBlankNameFailsClosed() {
    // RR-1083 — an un-dispatchable tool call must raise a protocol failure, not project SUCCESS.
    assertThatThrownBy(
            () ->
                parse(
                    """
                    {"choices":[{"message":{"tool_calls":[
                      {"type":"function","function":{"arguments":"{}"}}]},
                      "finish_reason":"tool_calls"}]}
                    """))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no function name");
  }

  @Test
  void truncatedToolCallCandidateIsSuppressedBeforeDispatch() throws Exception {
    ChatResponse response =
        parse(
            """
            {"choices":[{"message":{"content":null,"tool_calls":[
              {"id":"call-1","type":"function",
               "function":{"name":"writeFile","arguments":"{\\"path\\":\\"partial.txt\\"}"}}]},
              "finish_reason":"length"}],
             "usage":{"prompt_tokens":10,"completion_tokens":20}}
            """);

    AssistantMessage output = response.getResult().getOutput();
    assertThat(output.getToolCalls()).isEmpty();
    assertThat(output.getText()).contains("tool call(s) suppressed");
    assertThat(response.getMetadata().getUsage().getCompletionTokens()).isEqualTo(20);
  }
}

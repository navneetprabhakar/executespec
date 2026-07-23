package com.navneet.executespec.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.config.featureflag.FeatureFlagService;
import com.navneet.executespec.cost.RunCostTracker;
import com.navneet.executespec.cost.RunStepUsageRecorder;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * RR-511 — a Gemini candidate that terminates abnormally must not dispatch its tool calls even when
 * it contains a {@code functionCall}. Fail closed: drop the calls, surface the block as text.
 */
class GeminiNativeToolClientSafetyTest {

  private final ObjectMapper mapper = new ObjectMapper();
  private final GeminiNativeToolClient client =
      new GeminiNativeToolClient(
          mapper,
          mock(FeatureFlagService.class),
          mock(RunCostTracker.class),
          mock(RunStepUsageRecorder.class));

  private ChatResponse parse(String json) throws Exception {
    JsonNode node = mapper.readTree(json);
    return ReflectionTestUtils.invokeMethod(client, "parseResponse", node, "gemini-test");
  }

  private static AssistantMessage output(ChatResponse resp) {
    return (AssistantMessage) resp.getResults().get(0).getOutput();
  }

  @Test
  void dropsToolCallsOnAbnormalFinishReason() throws Exception {
    ChatResponse resp =
        parse(
            """
            {"candidates":[{"content":{"parts":[
              {"functionCall":{"name":"editFile","args":{"path":"a.txt"}}}]},
              "finishReason":"MAX_TOKENS"}]}
            """);

    AssistantMessage msg = output(resp);
    assertThat(msg.getToolCalls()).isEmpty();
    assertThat(msg.getText()).contains("finishReason=MAX_TOKENS");
    assertThat(msg.getText()).contains("suppressed");
  }

  @Test
  void dropsToolCallsOnSafetyBlock() throws Exception {
    ChatResponse resp =
        parse(
            """
            {"candidates":[{"content":{"parts":[
              {"functionCall":{"name":"shell","args":{"cmd":"rm -rf /"}}}]},
              "finishReason":"SAFETY"}]}
            """);

    assertThat(output(resp).getToolCalls()).isEmpty();
  }

  @Test
  void keepsToolCallsOnCompleteStop() throws Exception {
    // A normal STOP termination must still deliver its tool call unchanged.
    ChatResponse resp =
        parse(
            """
            {"candidates":[{"content":{"parts":[
              {"functionCall":{"name":"editFile","args":{"path":"a.txt"}}}]},
              "finishReason":"STOP"}]}
            """);

    AssistantMessage msg = output(resp);
    assertThat(msg.getToolCalls()).hasSize(1);
    assertThat(msg.getToolCalls().get(0).name()).isEqualTo("editFile");
  }
}

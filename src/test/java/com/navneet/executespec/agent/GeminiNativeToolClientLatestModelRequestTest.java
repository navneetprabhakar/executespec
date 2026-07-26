package com.navneet.executespec.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.config.featureflag.FeatureFlagService;
import com.navneet.executespec.cost.RunCostTracker;
import com.navneet.executespec.cost.RunStepUsageRecorder;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.test.util.ReflectionTestUtils;

class GeminiNativeToolClientLatestModelRequestTest {

  private final GeminiNativeToolClient client =
      new GeminiNativeToolClient(
          new ObjectMapper(),
          mock(FeatureFlagService.class),
          mock(RunCostTracker.class),
          mock(RunStepUsageRecorder.class));

  @Test
  void latestModelsOmitSamplingParametersAndRoundTripFunctionCallIds() {
    for (String model : List.of("gemini-3.6-flash", "gemini-3.5-flash-lite")) {
      JsonNode body = body(model);

      assertThat(body.path("generationConfig").has("temperature")).isFalse();
      assertThat(
              body.path("contents")
                  .get(1)
                  .path("parts")
                  .get(0)
                  .path("functionCall")
                  .path("id")
                  .asText())
          .isEqualTo("call-1");
      assertThat(
              body.path("contents")
                  .get(2)
                  .path("parts")
                  .get(0)
                  .path("functionResponse")
                  .path("id")
                  .asText())
          .isEqualTo("call-1");
    }
  }

  @Test
  void earlierModelsKeepTheirExistingRequestShape() {
    JsonNode body = body("gemini-3.5-flash");

    assertThat(body.path("generationConfig").path("temperature").asDouble()).isEqualTo(0.2);
    assertThat(body.path("contents").get(1).path("parts").get(0).path("functionCall").has("id"))
        .isFalse();
    assertThat(body.path("contents").get(2).path("parts").get(0).path("functionResponse").has("id"))
        .isFalse();
  }

  @Test
  void providerNeutralToolChoiceOverridesLegacyForceHint() {
    assertThat(GeminiNativeToolClient.toolMode(toolRequest("required", false))).isEqualTo("ANY");
    assertThat(GeminiNativeToolClient.toolMode(toolRequest("none", true))).isEqualTo("NONE");
    assertThat(GeminiNativeToolClient.toolMode(toolRequest("auto", true))).isEqualTo("AUTO");
    assertThat(GeminiNativeToolClient.toolMode(toolRequest(null, true))).isEqualTo("ANY");
    assertThat(GeminiNativeToolClient.toolMode(toolRequest(null, false))).isEqualTo("AUTO");
  }

  private GeminiNativeToolClient.GeminiToolCallRequest toolRequest(
      String toolChoice, boolean legacyForce) {
    return GeminiNativeToolClient.GeminiToolCallRequest.builder()
        .model("gemini-3.6-flash")
        .messages(List.of(new UserMessage("inspect")))
        .tools(List.of())
        .forceToolCall(legacyForce)
        .toolChoice(toolChoice)
        .build();
  }

  private JsonNode body(String model) {
    AssistantMessage.ToolCall call =
        new AssistantMessage.ToolCall("call-1", "function", "readFile", "{\"path\":\"a.txt\"}");
    List<Message> messages =
        List.of(
            new UserMessage("inspect"),
            AssistantMessage.builder().content("").toolCalls(List.of(call)).build(),
            ToolResponseMessage.builder()
                .responses(
                    List.of(
                        new ToolResponseMessage.ToolResponse(
                            "call-1", "readFile", "{\"ok\":true}")))
                .build());
    GeminiNativeToolClient.GeminiToolCallRequest request =
        GeminiNativeToolClient.GeminiToolCallRequest.builder()
            .model(model)
            .messages(messages)
            .tools(List.of())
            .temperature(0.2)
            .maxOutputTokens(1024)
            .build();
    return ReflectionTestUtils.invokeMethod(client, "buildRequestBody", request, null);
  }
}

package com.navneet.executespec.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class OpenAiNativeToolClientRequestBodyTest {

  @Test
  void nativeDeepSeekUsesItsSupportedMaxTokensField() {
    ObjectNode body = requestBody("https://api.deepseek.com", 1_500);

    assertThat(body.path("max_tokens").asInt()).isEqualTo(1_500);
    assertThat(body.has("max_completion_tokens")).isFalse();
  }

  @Test
  void openAiWireProvidersRetainMaxCompletionTokensField() {
    ObjectNode body = requestBody("https://api.openai.com", 1_500);

    assertThat(body.path("max_completion_tokens").asInt()).isEqualTo(1_500);
    assertThat(body.has("max_tokens")).isFalse();
  }

  @Test
  void gpt56OmitsUnsupportedTemperature() {
    ObjectNode body = requestBody("https://api.openai.com", "gpt-5.6-terra", 1_500, 0.2);

    assertThat(body.has("temperature")).isFalse();
  }

  @Test
  void earlierOpenAiModelsRetainTemperature() {
    ObjectNode body = requestBody("https://api.openai.com", "gpt-5.4", 1_500, 0.2);

    assertThat(body.path("temperature").asDouble()).isEqualTo(0.2);
  }

  @Test
  void deepSeekStructuredMetadataCoherenceAndReviewerDisableThinkingWithoutChangingWorkers() {
    LlmRequest coherence = request("CoherencePlannerAgent");
    LlmRequest classifier = request("SmartClassifierAgent");
    LlmRequest outline = request("OutlinePlannerAgent");
    LlmRequest expander = request("TaskExpanderAgent");
    LlmRequest worker = request("WorkerAgent");
    LlmRequest forcedWorker = request("WorkerAgent").toBuilder().toolChoice("required").build();
    LlmRequest reviewer = request("WorkerAgent", "run-reviewer-general");

    assertThat(LlmRouter.deepSeekExtraBody(coherence))
        .containsEntry("thinking", Map.of("type", "disabled"));
    assertThat(LlmRouter.deepSeekExtraBody(classifier))
        .containsEntry("thinking", Map.of("type", "disabled"));
    assertThat(LlmRouter.deepSeekExtraBody(outline))
        .containsEntry("thinking", Map.of("type", "disabled"));
    assertThat(LlmRouter.deepSeekExtraBody(expander))
        .containsEntry("thinking", Map.of("type", "disabled"));
    assertThat(LlmRouter.deepSeekExtraBody(reviewer))
        .containsEntry("thinking", Map.of("type", "disabled"));
    assertThat(LlmRouter.deepSeekExtraBody(forcedWorker))
        .containsEntry("thinking", Map.of("type", "disabled"));
    assertThat(LlmRouter.deepSeekExtraBody(worker)).isEmpty();
  }

  @Test
  void deepSeekAllowsToolChoiceOnlyWhenThinkingIsExplicitlyDisabled() {
    OpenAiNativeToolClient.OpenAiToolCallRequest thinking =
        wireRequest("https://api.deepseek.com", Map.of());
    OpenAiNativeToolClient.OpenAiToolCallRequest nonThinking =
        wireRequest("https://api.deepseek.com", Map.of("thinking", Map.of("type", "disabled")));

    assertThat(OpenAiNativeToolClient.supportsToolChoice(thinking)).isFalse();
    assertThat(OpenAiNativeToolClient.supportsToolChoice(nonThinking)).isTrue();
  }

  private static ObjectNode requestBody(String baseUrl, int maxTokens) {
    return requestBody(baseUrl, "test-model", maxTokens, null);
  }

  private static ObjectNode requestBody(
      String baseUrl, String model, int maxTokens, Double temperature) {
    OpenAiNativeToolClient client = new OpenAiNativeToolClient(new ObjectMapper(), null);
    OpenAiNativeToolClient.OpenAiToolCallRequest request =
        OpenAiNativeToolClient.OpenAiToolCallRequest.builder()
            .model(model)
            .messages(List.of())
            .baseUrl(baseUrl)
            .temperature(temperature)
            .maxCompletionTokens(maxTokens)
            .build();
    return ReflectionTestUtils.invokeMethod(client, "buildRequestBody", request);
  }

  private static LlmRequest request(String agentRole) {
    return request(agentRole, "task");
  }

  private static LlmRequest request(String agentRole, String taskId) {
    return LlmRequest.builder()
        .provider("deepseek")
        .model("deepseek-v4-flash-native")
        .messages(List.of(new org.springframework.ai.chat.messages.UserMessage("test")))
        .context(
            new com.navneet.executespec.tool.ExecutionContext(
                "run", taskId, java.nio.file.Path.of("/tmp")))
        .agentRole(agentRole)
        .build();
  }

  private static OpenAiNativeToolClient.OpenAiToolCallRequest wireRequest(
      String baseUrl, Map<String, Object> extraBody) {
    return OpenAiNativeToolClient.OpenAiToolCallRequest.builder()
        .model("deepseek-v4-flash")
        .messages(List.of())
        .baseUrl(baseUrl)
        .extraBody(extraBody)
        .build();
  }
}

package com.navneet.executespec.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

class AnthropicModelOptionsTest {

  @Test
  void omitsTemperatureForKnownDeprecatedModelFamilies() {
    assertThat(AnthropicModelOptions.shouldOmitTemperature("claude-sonnet-5")).isTrue();
    assertThat(AnthropicModelOptions.shouldOmitTemperature("claude-sonnet-5-20260702")).isTrue();
    assertThat(AnthropicModelOptions.shouldOmitTemperature("claude-opus-4-8")).isTrue();
    assertThat(AnthropicModelOptions.shouldOmitTemperature("claude-fable-5")).isTrue();
    assertThat(AnthropicModelOptions.shouldOmitTemperature("claude-sonnet-4-6")).isFalse();
  }

  @Test
  void recognizesAnthropicBacktickWrappedTemperatureDeprecation() {
    RuntimeException ex =
        new RuntimeException(
            "400 - {\"error\":{\"message\":\"`temperature` is deprecated for this model.\"}}");

    assertThat(AnthropicModelOptions.isTemperatureDeprecatedError(ex)).isTrue();
  }

  @Test
  void modelSpecificDefaultsOmitTemperatureFromSpringAiWireRequest() throws Exception {
    AnthropicChatModel model =
        AnthropicChatModel.builder()
            .anthropicApi(AnthropicApi.builder().apiKey("test-key").build())
            .defaultOptions(
                AnthropicChatOptions.builder().model("claude-sonnet-5").maxTokens(1024).build())
            .build();

    Method createRequest =
        AnthropicChatModel.class.getDeclaredMethod("createRequest", Prompt.class, boolean.class);
    createRequest.setAccessible(true);
    AnthropicApi.ChatCompletionRequest request =
        (AnthropicApi.ChatCompletionRequest)
            createRequest.invoke(
                model,
                new Prompt(
                    List.of(new UserMessage("hello")),
                    AnthropicChatOptions.builder().model("claude-sonnet-5").build()),
                false);

    assertThat(request.temperature()).as("deprecated models must not receive temperature").isNull();
  }
}

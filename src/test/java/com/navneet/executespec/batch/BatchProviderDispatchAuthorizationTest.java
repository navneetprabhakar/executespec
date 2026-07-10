package com.navneet.executespec.batch;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agent.ApiKeyResolver;
import com.navneet.executespec.agent.ProviderDispatchAuthorization;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;

class BatchProviderDispatchAuthorizationTest {

  @Test
  void everyConcreteBatchSubmitAuthorizesBeforeHttpTransport() {
    ProviderDispatchAuthorization authorization = mock(ProviderDispatchAuthorization.class);
    doThrow(new IllegalStateException("dispatch denied"))
        .when(authorization)
        .authorize(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any());
    ObjectMapper mapper = new ObjectMapper();
    ApiKeyResolver keys = mock(ApiKeyResolver.class);
    PipelineRunRepository runs = mock(PipelineRunRepository.class);

    assertDenied(
        new AnthropicBatchClient(
            mapper, keys, runs, authorization, "https://invalid.example", "key"),
        "claude-sonnet-4-6");
    assertDenied(
        new OpenAiBatchClient(mapper, keys, runs, authorization, "https://invalid.example", "key"),
        "gpt-5.4");
    assertDenied(
        new GeminiBatchClient(mapper, keys, runs, authorization, "https://invalid.example", "key"),
        "gemini-3-flash");

    verify(authorization).authorize("anthropic", "claude-sonnet-4-6", "run-1");
    verify(authorization).authorize("openai", "gpt-5.4", "run-1");
    verify(authorization).authorize("gemini", "gemini-3-flash", "run-1");
  }

  private static void assertDenied(BatchClient client, String model) {
    BatchRequest request =
        new BatchRequest("T1", model, List.of(new UserMessage("test")), List.of(), null, 128);
    assertThatThrownBy(() -> client.submit("run-1", List.of(request)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("dispatch denied");
  }
}

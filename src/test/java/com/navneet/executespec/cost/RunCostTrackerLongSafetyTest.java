package com.navneet.executespec.cost;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.navneet.executespec.pricing.ModelPricingRepository;
import com.navneet.executespec.router.ModelResolver;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;

class RunCostTrackerLongSafetyTest {

  @Test
  void accumulatedTokensAndCallsDoNotWrapAtIntegerMax() {
    RunCostTracker tracker =
        new RunCostTracker(mock(ModelPricingRepository.class), mock(ModelResolver.class));
    ChatResponse response = response(Integer.MAX_VALUE, Integer.MAX_VALUE);

    tracker.record("run-long", "task", "openai", "gpt", response);
    tracker.record("run-long", "task", "openai", "gpt", response);

    long expected = 2L * Integer.MAX_VALUE;
    assertThat(tracker.snapshot("run-long").inputTokens()).isEqualTo(expected);
    assertThat(tracker.snapshot("run-long").outputTokens()).isEqualTo(expected);
    assertThat(tracker.snapshotTask("run-long", "task").inputTokens()).isEqualTo(expected);
    assertThat(tracker.snapshot("run-long").calls()).isEqualTo(2L);
    assertThat(RunCostTracker.saturatedInt(expected)).isEqualTo(Integer.MAX_VALUE);
  }

  private static ChatResponse response(int promptTokens, int completionTokens) {
    Usage usage = mock(Usage.class);
    when(usage.getPromptTokens()).thenReturn(promptTokens);
    when(usage.getCompletionTokens()).thenReturn(completionTokens);
    ChatResponseMetadata metadata = mock(ChatResponseMetadata.class);
    when(metadata.getUsage()).thenReturn(usage);
    ChatResponse response = mock(ChatResponse.class);
    when(response.getMetadata()).thenReturn(metadata);
    return response;
  }
}

package com.navneet.executespec.cost;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import com.navneet.executespec.pricing.ModelPricingRepository;
import com.navneet.executespec.router.ModelResolver;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;

/**
 * W0 (vix-leverage telemetry) — pins reasoning-token capture: Gemini {@code thinking_tokens} and
 * the OpenAI/DeepSeek {@code reasoning_tokens} metadata keys are now accumulated as a first-class
 * {@code Snapshot.reasoningTokens} metric (per run + per task), and the Snapshot's
 * backward-compatible 6-arg constructor defaults the new field to 0 so existing call sites are
 * unaffected.
 */
@DisplayName("RunCostTracker — W0 reasoning-token capture")
class RunCostTrackerReasoningTest {

  private RunCostTracker tracker;

  @BeforeEach
  void setUp() {
    tracker = new RunCostTracker(mock(ModelPricingRepository.class), mock(ModelResolver.class));
  }

  private ChatResponse responseWith(int prompt, int completion, String key, Integer val) {
    Usage usage = mock(Usage.class);
    lenient().when(usage.getPromptTokens()).thenReturn(prompt);
    lenient().when(usage.getCompletionTokens()).thenReturn(completion);
    ChatResponseMetadata meta = mock(ChatResponseMetadata.class);
    lenient().when(meta.getUsage()).thenReturn(usage);
    if (key != null) {
      lenient().when(meta.<Integer>get(key)).thenReturn(val);
    }
    ChatResponse resp = mock(ChatResponse.class);
    lenient().when(resp.getMetadata()).thenReturn(meta);
    return resp;
  }

  @Test
  @DisplayName("Gemini thinking_tokens captured as reasoningTokens (per run + per task)")
  void geminiThinking() {
    tracker.record(
        "run-g", "T1", "gemini", "gemini-3-flash", responseWith(100, 50, "thinking_tokens", 30));
    assertThat(tracker.snapshot("run-g").reasoningTokens()).isEqualTo(30);
    assertThat(tracker.snapshotTask("run-g", "T1").reasoningTokens()).isEqualTo(30);
  }

  @Test
  @DisplayName("reasoning_tokens key captured when thinking_tokens absent (OpenAI/DeepSeek)")
  void reasoningTokensKey() {
    tracker.record("run-o", "openai", "gpt-5.4", responseWith(100, 50, "reasoning_tokens", 40));
    assertThat(tracker.snapshot("run-o").reasoningTokens()).isEqualTo(40);
  }

  @Test
  @DisplayName("no reasoning metadata → reasoningTokens 0")
  void none() {
    tracker.record("run-a", "anthropic", "claude-sonnet-4-6", responseWith(100, 50, null, null));
    assertThat(tracker.snapshot("run-a").reasoningTokens()).isZero();
  }

  @Test
  @DisplayName("Snapshot 6-arg constructor defaults reasoningTokens to 0; 7-arg sets it")
  void snapshotCompat() {
    assertThat(new RunCostTracker.Snapshot(1, 2, 0, 0, 0.0, 1).reasoningTokens()).isZero();
    assertThat(new RunCostTracker.Snapshot(1, 2, 0, 0, 0.0, 1, 7).reasoningTokens()).isEqualTo(7);
  }
}

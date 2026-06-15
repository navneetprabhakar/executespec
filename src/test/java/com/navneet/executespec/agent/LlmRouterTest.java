package com.navneet.executespec.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.navneet.executespec.config.featureflag.FeatureFlagService;
import com.navneet.executespec.cost.RunCostTracker;
import com.navneet.executespec.observability.Metrics;
import com.navneet.executespec.router.ModelRegistry;
import com.navneet.executespec.sse.SseBroadcaster;
import com.navneet.executespec.tool.ExecutionContext;
import com.navneet.executespec.tool.ToolRegistry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

@DisplayName("LlmRouter dispatch / retry / failover")
class LlmRouterTest {

  private OpenAiNativeToolClient openai;
  private GeminiNativeToolClient gemini;
  private RunKeyContextStore keyStore;
  private ModelRegistry registry;
  private FeatureFlagService flags;
  private LlmRouter router; // a spy with backoffSleep no-op'd

  private LlmRouter build() {
    ToolRegistry toolRegistry = mock(ToolRegistry.class);
    lenient().when(toolRegistry.bind(any())).thenReturn(List.of());
    openai = mock(OpenAiNativeToolClient.class);
    gemini = mock(GeminiNativeToolClient.class);
    Metrics metrics = mock(Metrics.class);
    RunCostTracker cost = mock(RunCostTracker.class);
    com.navneet.executespec.cost.RunStepUsageRecorder stepUsageRecorder =
        mock(com.navneet.executespec.cost.RunStepUsageRecorder.class);
    lenient()
        .when(cost.snapshot(anyString()))
        .thenReturn(new RunCostTracker.Snapshot(0, 0, 0, 0, 0.0, 0));
    SseBroadcaster broadcaster = mock(SseBroadcaster.class);
    keyStore = mock(RunKeyContextStore.class);
    lenient().when(keyStore.get(anyString())).thenReturn(null); // platform keys by default
    AnthropicForcedToolClient forced = mock(AnthropicForcedToolClient.class);
    registry = mock(ModelRegistry.class);
    flags = mock(FeatureFlagService.class); // isEnabled defaults to false
    LlmRouter real =
        new LlmRouter(
            toolRegistry,
            openai,
            gemini,
            metrics,
            cost,
            stepUsageRecorder,
            broadcaster,
            keyStore,
            forced,
            registry,
            flags);
    router = spy(real);
    doNothing().when(router).backoffSleep(anyLong()); // keep retry tests fast
    return router;
  }

  private static ChatResponse ok() {
    return new ChatResponse(
        List.of(new Generation(AssistantMessage.builder().content("ok").build())));
  }

  private static LlmRequest req(String provider, String model) {
    return LlmRequest.builder()
        .provider(provider)
        .model(model)
        .messages(List.of(new UserMessage("hi")))
        .context(new ExecutionContext("run-1", "T1", Path.of("/tmp/router-test")))
        .build();
  }

  @Test
  void success_returns_response() {
    build();
    when(openai.call(any())).thenReturn(ok());
    assertThat(router.call(req("openai", "gpt-x"))).isNotNull();
    verify(openai, times(1)).call(any());
  }

  @Test
  void transient_error_retries_same_provider_then_succeeds() {
    build();
    when(openai.call(any()))
        .thenThrow(new RuntimeException("503 service unavailable"))
        .thenReturn(ok());
    assertThat(router.call(req("openai", "gpt-x"))).isNotNull();
    verify(openai, times(2)).call(any());
  }

  @Test
  void permanent_error_is_not_retried() {
    build();
    when(openai.call(any())).thenThrow(new RuntimeException("400 bad request"));
    assertThatThrownBy(() -> router.call(req("openai", "gpt-x")))
        .isInstanceOf(RuntimeException.class);
    verify(openai, times(1)).call(any()); // no retry on a permanent 4xx
  }

  @Test
  void context_length_error_is_not_retried_or_failed_over() {
    // A context-window-exceeded error is permanent (the next call would be identical) — must fail
    // fast, not burn 4 retries. The real fix for the cause is history compaction (WS3).
    build();
    when(flags.isEnabled(LlmRouter.FAILOVER_FLAG)).thenReturn(true);
    when(openai.call(any())).thenThrow(new RuntimeException("400 maximum context length exceeded"));
    assertThatThrownBy(() -> router.call(req("openai", "gpt-x")))
        .isInstanceOf(RuntimeException.class);
    verify(openai, times(1)).call(any());
    verify(gemini, never()).call(any()); // not transient -> no failover either
  }

  @Test
  void failover_disabled_does_not_cross_provider() {
    build();
    when(flags.isEnabled(LlmRouter.FAILOVER_FLAG)).thenReturn(false);
    when(openai.call(any())).thenThrow(new RuntimeException("503 service unavailable"));
    assertThatThrownBy(() -> router.call(req("openai", "gpt-x")))
        .isInstanceOf(RuntimeException.class);
    verify(gemini, never()).call(any());
  }

  @Test
  void failover_enabled_crosses_to_next_provider_in_chain() {
    build();
    when(flags.isEnabled(LlmRouter.FAILOVER_FLAG)).thenReturn(true);
    when(registry.fallbackChainFor(anyString()))
        .thenReturn(List.of(new String[] {"openai", "gpt-x"}, new String[] {"gemini", "gem-x"}));
    when(openai.call(any())).thenThrow(new RuntimeException("503 service unavailable"));
    when(gemini.call(any())).thenReturn(ok());

    assertThat(router.call(req("openai", "gpt-x"))).isNotNull();
    verify(gemini, times(1)).call(any());
  }

  @Test
  void byok_run_does_not_fail_over_to_an_unkeyed_provider() {
    build();
    when(flags.isEnabled(LlmRouter.FAILOVER_FLAG)).thenReturn(true);
    // Customer keyed OpenAI only (USER source); no Gemini key.
    RunKeyContext keys =
        new RunKeyContext(
            null,
            "sk-user-openai",
            null,
            null,
            null,
            RunKeyContext.Source.USER,
            null,
            null,
            "u1",
            "o1");
    when(keyStore.get(anyString())).thenReturn(keys);
    when(registry.fallbackChainFor(anyString()))
        .thenReturn(List.of(new String[] {"openai", "gpt-x"}, new String[] {"gemini", "gem-x"}));
    when(openai.call(any())).thenThrow(new RuntimeException("503 service unavailable"));

    assertThatThrownBy(() -> router.call(req("openai", "gpt-x")))
        .isInstanceOf(RuntimeException.class);
    verify(gemini, never())
        .call(any()); // BYOK run never crosses to an unkeyed provider's platform key
  }

  @Test
  void excluded_providers_are_never_failover_targets() {
    build();
    when(flags.isEnabled(LlmRouter.FAILOVER_FLAG)).thenReturn(true);
    // Only deepseek as an alternate -> excluded -> no failover target -> rethrow primary.
    when(registry.fallbackChainFor(anyString()))
        .thenReturn(List.of(new String[] {"openai", "gpt-x"}, new String[] {"deepseek", "ds-x"}));
    when(openai.call(any())).thenThrow(new RuntimeException("503 service unavailable"));
    assertThatThrownBy(() -> router.call(req("openai", "gpt-x")))
        .isInstanceOf(RuntimeException.class);
    verify(gemini, never()).call(any());
  }

  @Test
  @SuppressWarnings("unchecked")
  void openRouterRequestsLatencySortedProviderRouting() {
    build();
    when(openai.call(any())).thenReturn(ok());
    RunKeyContext keys =
        new RunKeyContext(
            null, null, null, "sk-or", null, null, null, RunKeyContext.Source.ORG, "u1", "o1");
    when(keyStore.get(anyString())).thenReturn(keys);

    assertThat(router.call(req("openrouter", "gpt-5.4"))).isNotNull();

    ArgumentCaptor<OpenAiNativeToolClient.OpenAiToolCallRequest> captor =
        ArgumentCaptor.forClass(OpenAiNativeToolClient.OpenAiToolCallRequest.class);
    verify(openai).call(captor.capture());
    Map<String, Object> extraBody = captor.getValue().extraBody();
    assertThat(extraBody).containsKey("provider");
    assertThat((Map<String, Object>) extraBody.get("provider")).containsEntry("sort", "latency");
  }
}

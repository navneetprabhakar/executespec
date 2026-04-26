package com.navneet.executespec.agent;

import com.navneet.executespec.state.InboxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("LlmRouter")
class LlmRouterTest {

    @Mock private ApplicationContext ctx;
    @Mock private InboxService inboxService;
    @Mock private RunKeyStore runKeyStore;
    @Mock private com.navneet.executespec.config.AgentProperties agentProperties;
    // Use a real registry; the router's metrics path constructs counters
    // and increments them. A mock would NPE on counter.increment().
    private io.micrometer.core.instrument.MeterRegistry meterRegistry =
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
    @Mock private com.navneet.executespec.router.ProviderHealthTracker providerHealthTracker;
    @Mock private com.navneet.executespec.router.ModelRegistry modelRegistry;
    @Mock private com.navneet.executespec.router.RunProviderPinStore pinStore;
    @Mock private com.navneet.executespec.router.ModelPerformanceTracker modelPerfTracker;
    @Mock private com.navneet.executespec.sse.SseBroadcaster sseBroadcaster;
    @Mock private com.navneet.executespec.v2.agent.OpenAiNativeToolClient openAiNativeToolClient;
    @Mock private AnthropicChatModel anthropicChatModel;
    @Mock private ChatResponse chatResponse;
    @Mock private org.springframework.ai.chat.model.Generation generation;
    @Mock private org.springframework.ai.chat.messages.AssistantMessage assistantMessage;

    private LlmRouter router;

    @BeforeEach
    void setUp() {
        // LlmRouter reads agentProperties.getTokenBudget().getMaxTokens() for Anthropic calls
        var tokenBudget = new com.navneet.executespec.config.AgentProperties.TokenBudget();
        tokenBudget.setMaxTokens(8192);
        org.mockito.Mockito.lenient().when(agentProperties.getTokenBudget()).thenReturn(tokenBudget);

        // RunKeyStore must return resolved keys for the run
        org.mockito.Mockito.lenient().when(runKeyStore.get(any())).thenReturn(ResolvedKeys.SYSTEM_ONLY);

        // ChatResponse chain: response.getResult().getOutput() must be non-null
        org.mockito.Mockito.lenient().when(chatResponse.getResult()).thenReturn(generation);
        org.mockito.Mockito.lenient().when(generation.getOutput()).thenReturn(assistantMessage);
        org.mockito.Mockito.lenient().when(assistantMessage.getText()).thenReturn("test response");

        router = new LlmRouter(ctx, inboxService, runKeyStore, agentProperties, meterRegistry, providerHealthTracker, modelRegistry, pinStore, modelPerfTracker, sseBroadcaster, openAiNativeToolClient);
    }

    @Nested
    @DisplayName("Chain resolution")
    class ChainResolution {

        @Test
        @DisplayName("claude-opus-4-6 uses ARCHITECT chain")
        void opusUsesArchitectChain() {
            when(ctx.getBean(AnthropicChatModel.class)).thenReturn(anthropicChatModel);
            when(anthropicChatModel.call(any(Prompt.class))).thenReturn(chatResponse);

            var result = router.callWithFallbackTracked(
                "anthropic", "claude-opus-4-6",
                new Prompt("test"),
                "run-1", "planner",
                LlmRouter.FallbackMode.IMMEDIATE, 0, false
            );

            assertThat(result.provider()).isEqualTo("anthropic");
            assertThat(result.model()).isEqualTo("claude-opus-4-6");
        }

        @Test
        @DisplayName("claude-sonnet uses CAPABLE chain")
        void sonnetUsesCapableChain() {
            when(ctx.getBean(AnthropicChatModel.class)).thenReturn(anthropicChatModel);
            when(anthropicChatModel.call(any(Prompt.class))).thenReturn(chatResponse);

            var result = router.callWithFallbackTracked(
                "anthropic", "claude-sonnet-4-5",
                new Prompt("test"),
                "run-1", "worker",
                LlmRouter.FallbackMode.TASK_BOUNDARY, 0, false
            );

            assertThat(result.provider()).isEqualTo("anthropic");
        }
    }

    @Nested
    @DisplayName("OpenAI Spring AI 1.1.4 extra_body bypass (2026-04-26)")
    class OpenAiNativeBypass {

        @org.junit.jupiter.api.BeforeEach
        void stubOpenAiChain() {
            // resolveChain falls through fallbackChainFor → fallbackChainForTier
            // when model is unknown; either path needs a non-empty chain or
            // the router hits division-by-zero on `% chain.size()`.
            java.util.List<String[]> openAiChain = new java.util.ArrayList<>();
            openAiChain.add(new String[]{"openai", "gpt-5.4-mini"});
            org.mockito.Mockito.lenient().when(modelRegistry.fallbackChainFor("gpt-5.4-mini")).thenReturn(openAiChain);
            // Default circuit-breaker mock returns false → "OPEN" → router
            // skips. Unstub so it returns true (circuit closed) and the test
            // actually exercises the native-client path.
            org.mockito.Mockito.lenient().when(providerHealthTracker.isHealthy(any())).thenReturn(true);
        }

        @Test
        @DisplayName("OpenAI calls route through OpenAiNativeToolClient, not Spring AI")
        void openAiBypassesSpringAi() {
            // Native client returns a usable ChatResponse so the router doesn't
            // bail on the empty-result check.
            when(openAiNativeToolClient.call(any())).thenReturn(chatResponse);

            var result = router.callWithFallbackTracked(
                "openai", "gpt-5.4-mini",
                new Prompt("test"),
                "run-openai-1", "planner",
                LlmRouter.FallbackMode.NO_FALLBACK, 0, false
            );

            assertThat(result.provider()).isEqualTo("openai");
            assertThat(result.model()).isEqualTo("gpt-5.4-mini");
            // The native client must be the one that was called — Spring AI's
            // OpenAiChatModel must NOT be touched (we never stub ctx.getBean
            // for OpenAiChatModel here, so any attempt would fail).
            org.mockito.Mockito.verify(openAiNativeToolClient, org.mockito.Mockito.times(1)).call(any());
            org.mockito.Mockito.verify(ctx, org.mockito.Mockito.never())
                .getBean(org.springframework.ai.openai.OpenAiChatModel.class);
        }

        @Test
        @DisplayName("OpenAI native client ProviderUnavailableException propagates under NO_FALLBACK")
        void nativeFailureNoFallback() {
            // Throw the router-native exception so it skips the transient
            // retry loop (which has 15s/30s/60s sleeps) and surfaces.
            when(openAiNativeToolClient.call(any()))
                .thenThrow(new LlmRouter.ProviderUnavailableException("openai key missing"));

            assertThatThrownBy(() -> router.callWithFallback(
                "openai", "gpt-5.4-mini",
                new Prompt("test"),
                "run-openai-2", "planner",
                LlmRouter.FallbackMode.NO_FALLBACK, 0, false
            )).isInstanceOf(LlmRouter.ProviderUnavailableException.class);
        }
    }

    @Test
    @DisplayName("ProviderUnavailableException thrown when all providers fail")
    void thrownWhenAllFail() {
        when(ctx.getBean(AnthropicChatModel.class)).thenReturn(anthropicChatModel);
        when(anthropicChatModel.call(any(Prompt.class)))
            .thenThrow(new RuntimeException("service down"));

        assertThatThrownBy(() -> router.callWithFallback(
            "anthropic", "claude-opus-4-5",
            new Prompt("test"),
            "run-x", "planner",
            LlmRouter.FallbackMode.IMMEDIATE, 0, false
        )).isInstanceOf(LlmRouter.ProviderUnavailableException.class);
    }
}

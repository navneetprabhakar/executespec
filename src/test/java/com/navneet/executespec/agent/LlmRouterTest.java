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
    @Mock private io.micrometer.core.instrument.MeterRegistry meterRegistry;
    @Mock private com.navneet.executespec.router.ProviderHealthTracker providerHealthTracker;
    @Mock private com.navneet.executespec.router.ModelRegistry modelRegistry;
    @Mock private com.navneet.executespec.router.RunProviderPinStore pinStore;
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
        when(agentProperties.getTokenBudget()).thenReturn(tokenBudget);

        // RunKeyStore must return resolved keys for the run
        when(runKeyStore.get(any())).thenReturn(ResolvedKeys.SYSTEM_ONLY);

        // ChatResponse chain: response.getResult().getOutput() must be non-null
        when(chatResponse.getResult()).thenReturn(generation);
        when(generation.getOutput()).thenReturn(assistantMessage);
        when(assistantMessage.getText()).thenReturn("test response");

        router = new LlmRouter(ctx, inboxService, runKeyStore, agentProperties, meterRegistry, providerHealthTracker, modelRegistry, pinStore);
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

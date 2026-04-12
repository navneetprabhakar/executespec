package com.navneet.executespec.v2.agent;

import com.navneet.executespec.v2.observability.V2Metrics;
import com.navneet.executespec.v2.tool.ToolRegistryV2;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/**
 * V2 LLM router — native-tool-only, provider-agnostic single-call dispatcher.
 *
 * <p>Phase 2 scope: routes one {@link LlmRequestV2} to the correct provider with native
 * tool calling, returns Spring AI's {@link ChatResponse} uniformly. Tool callbacks are
 * always sourced from {@link ToolRegistryV2#bind(com.navneet.executespec.v2.tool.ExecutionContext)}
 * so concurrent tasks never share state.
 *
 * <p>Provider routing:
 * <ul>
 *   <li><b>anthropic</b>: Spring AI {@link AnthropicChatModel} with native
 *       {@link AnthropicChatOptions#toolCallbacks}. Verified working in Phase 0.</li>
 *   <li><b>gemini</b>: Spring AI {@link GoogleGenAiChatModel} with native
 *       {@link GoogleGenAiChatOptions#toolCallbacks} (requires Spring AI 1.1.4+).
 *       Verified working in Phase 0.</li>
 *   <li><b>openai</b>: {@link OpenAiNativeToolClient} — direct REST POST that
 *       bypasses Spring AI 1.1.4's broken {@code extra_body} serialization. Verified
 *       working in Phase 0.</li>
 * </ul>
 *
 * <p><b>Deferred to Phase 2.x / Phase 9</b>: multi-provider failover,
 * {@code ProviderHealthTracker} circuit breaker, per-run API-key resolution, tier-based
 * model resolution. These are production-canary concerns; the Phase 2 baseline proves
 * routing correctness end-to-end first.
 *
 * <p>{@code internalToolExecutionEnabled(false)} is set on every Spring-AI-routed call so
 * the WorkerAgentV2 loop (Phase 3) keeps control of dispatch — needed for context
 * checkpointing and smoke-test integration. The OpenAI wrapper makes the same
 * assumption (it never executes tools internally).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class LlmRouterV2 {

    private final ToolRegistryV2 toolRegistry;
    private final OpenAiNativeToolClient openAiNativeToolClient;
    private final GeminiNativeToolClient geminiNativeToolClient;
    private final V2Metrics metrics;

    // Auto-configured Spring AI bean for Anthropic (the one provider whose native
    // tool calling works correctly on Spring AI 1.1.4). OpenAI and Gemini both have
    // Spring AI 1.1.4 bugs and go through our own wrappers instead.
    @Autowired(required = false)
    @Qualifier("anthropicChatModel")
    private AnthropicChatModel anthropicModel;

    /**
     * Dispatch a single LLM call.
     *
     * @return Spring AI {@link ChatResponse} (uniform shape regardless of provider)
     */
    public ChatResponse call(LlmRequestV2 request) {
        // If the caller supplied an explicit tool list (e.g. the planner restricting
        // to just submitPlan), honor it; otherwise bind every registered V2 tool.
        List<ToolCallback> tools = (request.toolsOverride() != null)
                ? request.toolsOverride()
                : toolRegistry.bind(request.context());
        long start = System.currentTimeMillis();
        log.info("LlmRouterV2 -> {} {} (tools={}, messages={})",
                request.provider(), request.model(), tools.size(), request.messages().size());
        try {
            ChatResponse resp = dispatch(request, tools);
            long elapsed = System.currentTimeMillis() - start;
            metrics.recordLlmLatency(request.provider(), request.model(), "ok",
                    java.time.Duration.ofMillis(elapsed));
            log.info("LlmRouterV2 <- {} {} ok in {}ms",
                    request.provider(), request.model(), elapsed);
            return resp;
        } catch (RuntimeException e) {
            long elapsed = System.currentTimeMillis() - start;
            metrics.recordLlmLatency(request.provider(), request.model(), "failed",
                    java.time.Duration.ofMillis(elapsed));
            log.warn("LlmRouterV2 <- {} {} FAILED in {}ms: {}",
                    request.provider(), request.model(), elapsed, e.getMessage());
            throw e;
        }
    }

    private ChatResponse dispatch(LlmRequestV2 request, List<ToolCallback> tools) {
        return switch (request.provider().toLowerCase(Locale.ROOT)) {
            case "anthropic" -> callAnthropic(request, tools);
            case "openai" -> callOpenAi(request, tools);
            case "gemini" -> callGemini(request, tools);
            default -> throw new IllegalArgumentException(
                    "Unknown provider: " + request.provider() + " (expected anthropic|openai|gemini)");
        };
    }

    private ChatResponse callAnthropic(LlmRequestV2 r, List<ToolCallback> tools) {
        if (anthropicModel == null) throw new IllegalStateException("Anthropic model not configured");
        var b = AnthropicChatOptions.builder()
                .model(r.model())
                .temperature(r.temperature())
                .toolCallbacks(tools)
                .internalToolExecutionEnabled(false);
        if (r.maxTokens() != null) b.maxTokens(r.maxTokens());
        return anthropicModel.call(new Prompt(r.messages(), b.build()));
    }

    private ChatResponse callGemini(LlmRequestV2 r, List<ToolCallback> tools) {
        // Routes through GeminiNativeToolClient — direct REST POST, bypasses Spring AI
        // 1.1.4's broken multi-turn response parsing (Optional.get() on empty Optional
        // inside GoogleGenAiChatModel.responseCandidateToGeneration).
        //
        // When the caller supplied an explicit toolsOverride (e.g. the planner restricting
        // to submitPlan only), force Gemini to ANY mode — the model MUST emit a function
        // call. This prevents gemini-2.5-flash's "emit Python code as function call"
        // degradation under heavy prompts that otherwise causes MALFORMED_FUNCTION_CALL.
        boolean forceToolCall = r.toolsOverride() != null && !r.toolsOverride().isEmpty();
        return geminiNativeToolClient.call(GeminiNativeToolClient.GeminiToolCallRequest.builder()
                .model(r.model())
                .messages(r.messages())
                .tools(tools)
                .temperature(r.temperature())
                .maxOutputTokens(r.maxTokens())
                .forceToolCall(forceToolCall)
                .build());
    }

    private ChatResponse callOpenAi(LlmRequestV2 r, List<ToolCallback> tools) {
        // Phase 3: pass the full Spring AI message list — wrapper serializes
        // SystemMessage / UserMessage / AssistantMessage(+toolCalls) / ToolResponseMessage
        // to OpenAI's chat-completions wire format, supporting multi-turn agent loops.
        return openAiNativeToolClient.call(OpenAiNativeToolClient.OpenAiToolCallRequest.builder()
                .model(r.model())
                .messages(r.messages())
                .tools(tools)
                .temperature(r.temperature())
                .maxCompletionTokens(r.maxTokens())
                .build());
    }
}

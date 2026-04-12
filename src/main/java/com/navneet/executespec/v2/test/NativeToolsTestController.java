package com.navneet.executespec.v2.test;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * V2 Phase 0 verification endpoint.
 *
 * <p>Calls each provider with a single-tool prompt and asserts that the response
 * contains a structured tool call (not embedded XML/JSON in text). This validates
 * Spring AI 1.1.4's native tool calling support across Anthropic, OpenAI, and
 * Google Gemini before V2 builds the full LlmRouterV2 layer on top.
 *
 * <p>Usage: {@code GET /api/v2/test/native-tools?provider=anthropic|openai|gemini}
 *
 * <p>This controller is intentionally minimal — it exists to prove the foundation,
 * not to be production code. Will be removed once LlmRouterV2 is in place.
 */
@RestController
@RequestMapping("/api/v2/test")
@RequiredArgsConstructor
@Slf4j
public class NativeToolsTestController {

    private final ObjectMapper objectMapper;

    // Inject all three model beans by their default qualifiers (defined in AgentModelConfig).
    // These may be null if the provider's API key is not configured — handled per-request.
    @Autowired(required = false)
    @Qualifier("anthropicChatModel")
    private AnthropicChatModel anthropicModel;

    @Autowired(required = false)
    @Qualifier("openAiChatModel")
    private OpenAiChatModel openAiModel;

    @Autowired(required = false)
    @Qualifier("googleGenAiChatModel")
    private GoogleGenAiChatModel geminiModel;

    @GetMapping("/native-tools")
    public Map<String, Object> testNativeTools(@RequestParam String provider) {
        ToolCallback echo = buildEchoToolCallback();
        List<ToolCallback> callbacks = List.of(echo);
        String userPrompt = "Use the echoMessage tool to echo back the text 'hello-v2'.";

        Map<String, Object> result = new HashMap<>();
        result.put("provider", provider);
        result.put("prompt", userPrompt);
        try {
            ChatResponse resp = switch (provider.toLowerCase()) {
                case "anthropic" -> callAnthropic(userPrompt, callbacks);
                case "openai" -> callOpenAi(userPrompt, callbacks);
                case "gemini" -> callGemini(userPrompt, callbacks);
                default -> throw new IllegalArgumentException("Unknown provider: " + provider);
            };
            return summarizeResponse(result, resp);
        } catch (Exception e) {
            log.error("Native tools test failed for provider {}", provider, e);
            result.put("ok", false);
            result.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
            return result;
        }
    }

    private ChatResponse callAnthropic(String userPrompt, List<ToolCallback> callbacks) {
        if (anthropicModel == null) throw new IllegalStateException("Anthropic model not configured");
        AnthropicChatOptions opts = AnthropicChatOptions.builder()
                .temperature(0.0)
                .maxTokens(512)
                .toolCallbacks(callbacks)
                .internalToolExecutionEnabled(false)
                .build();
        return anthropicModel.call(new Prompt(List.of(new UserMessage(userPrompt)), opts));
    }

    private ChatResponse callOpenAi(String userPrompt, List<ToolCallback> callbacks) {
        if (openAiModel == null) throw new IllegalStateException("OpenAI model not configured");
        OpenAiChatOptions opts = OpenAiChatOptions.builder()
                .temperature(0.0)
                .maxTokens(512)
                .toolCallbacks(callbacks)
                .internalToolExecutionEnabled(false)
                .build();
        return openAiModel.call(new Prompt(List.of(new UserMessage(userPrompt)), opts));
    }

    private ChatResponse callGemini(String userPrompt, List<ToolCallback> callbacks) {
        if (geminiModel == null) throw new IllegalStateException("Gemini model not configured");
        GoogleGenAiChatOptions opts = GoogleGenAiChatOptions.builder()
                .temperature(0.0)
                .maxOutputTokens(512)
                .toolCallbacks(callbacks)
                .internalToolExecutionEnabled(false)
                .build();
        return geminiModel.call(new Prompt(List.of(new UserMessage(userPrompt)), opts));
    }

    private Map<String, Object> summarizeResponse(Map<String, Object> result, ChatResponse resp) {
        var output = resp.getResult().getOutput();
        var toolCalls = output.getToolCalls();
        boolean hasNativeToolCall = toolCalls != null && !toolCalls.isEmpty();
        result.put("ok", hasNativeToolCall);
        result.put("nativeToolCallCount", toolCalls == null ? 0 : toolCalls.size());
        result.put("textContent", output.getText());
        if (hasNativeToolCall) {
            var first = toolCalls.get(0);
            Map<String, Object> tc = new HashMap<>();
            tc.put("name", first.name());
            tc.put("arguments", first.arguments());
            tc.put("id", first.id());
            result.put("firstToolCall", tc);
        }
        result.put("finishReason", resp.getResult().getMetadata().getFinishReason());
        return result;
    }

    /**
     * Builds a no-op {@code echoMessage} tool callback. The tool definition uses a strict
     * JSON schema (additionalProperties: false). The handler is never expected to run because
     * {@code internalToolExecutionEnabled(false)} keeps execution in the harness — we only
     * want to prove the model emits a structured tool call.
     */
    private ToolCallback buildEchoToolCallback() {
        ToolDefinition def = DefaultToolDefinition.builder()
                .name("echoMessage")
                .description("Echoes back the provided message text. Use this when asked to echo something.")
                .inputSchema("""
                        {
                          "type": "object",
                          "properties": {
                            "message": {
                              "type": "string",
                              "description": "The text to echo back"
                            }
                          },
                          "required": ["message"],
                          "additionalProperties": false
                        }
                        """)
                .build();
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return def;
            }

            @Override
            public String call(String toolInput) {
                return "{\"echoed\":" + toolInput + "}";
            }
        };
    }
}

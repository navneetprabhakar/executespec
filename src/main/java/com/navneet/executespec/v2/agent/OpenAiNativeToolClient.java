package com.navneet.executespec.v2.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;

/**
 * V2 OpenAI native tool-calling client — bypasses Spring AI 1.1.4 entirely.
 *
 * <p><b>Why this exists:</b> Spring AI 1.1.4's {@code OpenAiChatModel} adds an {@code extra_body}
 * field to the request body whenever {@code toolCallbacks} is set, regardless of any other
 * option (verified across {@code gpt-4o}, {@code gpt-4o-mini}, {@code gpt-5.4}, and
 * {@code gpt-5.4-mini}, with options stripped down to {@code model + tools} only).
 * The OpenAI Chat Completions endpoint rejects the request with
 * <code>"Unrecognized request argument supplied: extra_body"</code>.
 *
 * <p>This client makes a direct HTTP POST to {@code /v1/chat/completions} with a clean
 * JSON body — no {@code extra_body}, no Spring AI option-merging machinery — and parses
 * the response into Spring AI's {@link ChatResponse} / {@link AssistantMessage} types so
 * downstream V2 code (LlmRouterV2, WorkerAgentV2) can stay provider-agnostic.
 *
 * <p>Anthropic and Gemini native tool calling work fine on Spring AI 1.1.4 — only OpenAI
 * needs this wrapper.
 *
 * <p><b>Scope:</b> Phase 0 supports tool-calling-with-user-message single-turn calls only.
 * Multi-turn (tool result feedback), system messages, image inputs, and streaming will be
 * added incrementally as later V2 phases require them.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class OpenAiNativeToolClient {

    private static final String DEFAULT_BASE_URL = "https://api.openai.com";
    private static final String CHAT_COMPLETIONS_PATH = "/v1/chat/completions";

    private final ObjectMapper objectMapper;

    @Value("${spring.ai.openai.api-key:}")
    private String defaultApiKey;

    @Value("${spring.ai.openai.base-url:" + DEFAULT_BASE_URL + "}")
    private String baseUrl;

    /**
     * Single-turn tool-calling request.
     *
     * @param request fully-built request describing model, prompt, tools, and options
     * @return parsed Spring AI {@link ChatResponse} with tool calls populated on the
     *         first generation's {@link AssistantMessage}
     */
    public ChatResponse call(OpenAiToolCallRequest request) {
        String apiKey = (request.apiKey() != null && !request.apiKey().isBlank())
                ? request.apiKey() : defaultApiKey;
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("OpenAI api-key not configured (set OPENAI_API_KEY)");
        }
        ObjectNode body = buildRequestBody(request);
        log.debug("OpenAI native tool call: model={} tools={}", request.model(),
                request.tools().stream().map(t -> t.getToolDefinition().name()).toList());

        RestClient client = RestClient.builder().baseUrl(baseUrl).build();
        JsonNode response;
        try {
            response = client.post()
                    .uri(CHAT_COMPLETIONS_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + apiKey)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (Exception e) {
            log.error("OpenAI native tool call failed", e);
            throw e;
        }
        if (response == null) {
            throw new IllegalStateException("OpenAI returned empty response body");
        }
        return parseResponse(response, request.model());
    }

    private ObjectNode buildRequestBody(OpenAiToolCallRequest request) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", request.model());

        ArrayNode messages = body.putArray("messages");
        if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
            ObjectNode sys = messages.addObject();
            sys.put("role", "system");
            sys.put("content", request.systemPrompt());
        }
        ObjectNode user = messages.addObject();
        user.put("role", "user");
        user.put("content", request.userPrompt());

        // Tools — { type: "function", function: { name, description, parameters } }
        if (request.tools() != null && !request.tools().isEmpty()) {
            ArrayNode tools = body.putArray("tools");
            for (ToolCallback tc : request.tools()) {
                ObjectNode tool = tools.addObject();
                tool.put("type", "function");
                ObjectNode fn = tool.putObject("function");
                fn.put("name", tc.getToolDefinition().name());
                fn.put("description", tc.getToolDefinition().description());
                try {
                    JsonNode paramSchema = objectMapper.readTree(tc.getToolDefinition().inputSchema());
                    fn.set("parameters", paramSchema);
                } catch (Exception e) {
                    throw new IllegalArgumentException(
                            "Invalid JSON schema for tool '" + tc.getToolDefinition().name() + "'", e);
                }
            }
            body.put("tool_choice", request.toolChoice() == null ? "auto" : request.toolChoice());
        }

        if (request.temperature() != null) body.put("temperature", request.temperature());
        if (request.maxCompletionTokens() != null) body.put("max_completion_tokens", request.maxCompletionTokens());

        return body;
    }

    private ChatResponse parseResponse(JsonNode response, String requestedModel) {
        List<Generation> generations = new ArrayList<>();
        JsonNode choices = response.path("choices");
        if (!choices.isArray()) {
            throw new IllegalStateException("OpenAI response missing 'choices' array: " + response);
        }
        for (JsonNode choice : choices) {
            JsonNode message = choice.path("message");
            String content = message.path("content").isNull() ? "" : message.path("content").asText("");

            List<AssistantMessage.ToolCall> toolCalls = new ArrayList<>();
            JsonNode tcs = message.path("tool_calls");
            if (tcs.isArray()) {
                for (JsonNode tc : tcs) {
                    String id = tc.path("id").asText("");
                    String type = tc.path("type").asText("function");
                    JsonNode fn = tc.path("function");
                    String name = fn.path("name").asText("");
                    String arguments = fn.path("arguments").asText("{}");
                    toolCalls.add(new AssistantMessage.ToolCall(id, type, name, arguments));
                }
            }

            String finishReason = choice.path("finish_reason").asText(null);
            AssistantMessage msg = AssistantMessage.builder()
                    .content(content)
                    .toolCalls(toolCalls)
                    .build();
            ChatGenerationMetadata genMeta = ChatGenerationMetadata.builder()
                    .finishReason(finishReason)
                    .build();
            generations.add(new Generation(msg, genMeta));
        }

        // Response-level metadata
        ChatResponseMetadata.Builder metaBuilder = ChatResponseMetadata.builder()
                .id(response.path("id").asText(""))
                .model(response.path("model").asText(requestedModel));
        JsonNode usage = response.path("usage");
        if (usage.isObject()) {
            int promptTokens = usage.path("prompt_tokens").asInt(0);
            int completionTokens = usage.path("completion_tokens").asInt(0);
            metaBuilder.usage(new DefaultUsage(promptTokens, completionTokens));
        }
        return new ChatResponse(generations, metaBuilder.build());
    }

    /**
     * Request DTO for {@link #call(OpenAiToolCallRequest)}.
     */
    @Builder
    public record OpenAiToolCallRequest(
            String model,
            String userPrompt,
            String systemPrompt,
            List<ToolCallback> tools,
            String toolChoice,
            Double temperature,
            Integer maxCompletionTokens,
            String apiKey) {
    }
}

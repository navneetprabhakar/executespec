package com.navneet.executespec.agent;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * V2 OpenAI native tool-calling client — bypasses Spring AI 1.1.4 entirely.
 *
 * <p><b>Why this exists:</b> Spring AI 1.1.4's {@code OpenAiChatModel} adds an {@code extra_body}
 * field to the request body whenever {@code toolCallbacks} is set, regardless of any other option
 * (verified across {@code gpt-4o}, {@code gpt-4o-mini}, {@code gpt-5.4}, and {@code gpt-5.4-mini},
 * with options stripped down to {@code model + tools} only). The OpenAI Chat Completions endpoint
 * rejects the request with <code>"Unrecognized request argument supplied: extra_body"</code>.
 *
 * <p>This client makes a direct HTTP POST to {@code /v1/chat/completions} with a clean JSON body —
 * no {@code extra_body}, no Spring AI option-merging machinery — and parses the response into
 * Spring AI's {@link ChatResponse} / {@link AssistantMessage} types so downstream V2 code
 * (LlmRouter, WorkerAgent) can stay provider-agnostic.
 *
 * <p>Anthropic and Gemini native tool calling work fine on Spring AI 1.1.4 — only OpenAI needs this
 * wrapper.
 *
 * <p><b>Scope:</b> Phase 0 supports tool-calling-with-user-message single-turn calls only.
 * Multi-turn (tool result feedback), system messages, image inputs, and streaming will be added
 * incrementally as later V2 phases require them.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class OpenAiNativeToolClient {

  /**
   * AssistantMessage metadata key under which we stash DeepSeek's {@code reasoning_content} so the
   * next call's assistant message can echo it back (the DeepSeek API requires this round-trip for
   * V4 thinking-mode models). See {@code parseResponse} + {@code appendMessage}. RL-2026-05-052.
   */
  public static final String REASONING_CONTENT_META_KEY = "deepseek_reasoning_content";

  private static final String DEFAULT_BASE_URL = "https://api.openai.com";
  private static final String CHAT_COMPLETIONS_PATH = "/v1/chat/completions";

  /** Connect timeout for the OpenAI HTTP call. */
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

  /** Read timeout for the OpenAI HTTP call. Generous because tool-emitting calls can be slow. */
  private static final Duration READ_TIMEOUT = Duration.ofSeconds(120);

  private final ObjectMapper objectMapper;

  @Value("${spring.ai.openai.api-key:}")
  private String defaultApiKey;

  @Value("${spring.ai.openai.base-url:" + DEFAULT_BASE_URL + "}")
  private String baseUrl;

  /**
   * Single-turn tool-calling request.
   *
   * @param request fully-built request describing model, prompt, tools, and options
   * @return parsed Spring AI {@link ChatResponse} with tool calls populated on the first
   *     generation's {@link AssistantMessage}
   */
  public ChatResponse call(OpenAiToolCallRequest request) {
    String apiKey =
        (request.apiKey() != null && !request.apiKey().isBlank())
            ? request.apiKey()
            : defaultApiKey;
    if (apiKey == null || apiKey.isBlank()) {
      throw new IllegalStateException("OpenAI api-key not configured (set OPENAI_API_KEY)");
    }
    // Per-call base-URL override lets OpenAI-wire-compatible gateways (e.g. OpenRouter) reuse this
    // exact client; absent an override we use the configured OpenAI default.
    String effectiveBaseUrl =
        (request.baseUrl() != null && !request.baseUrl().isBlank()) ? request.baseUrl() : baseUrl;
    // Some gateways' base-url already ends in `/v1` (e.g. Fireworks:
    // https://api.fireworks.ai/inference/v1); appending the full `/v1/chat/completions` would yield
    // a
    // 404 on `/v1/v1/chat/completions`. Append only the remainder when the base already carries
    // `/v1`.
    String completionsPath =
        effectiveBaseUrl.replaceAll("/+$", "").endsWith("/v1")
            ? "/chat/completions"
            : CHAT_COMPLETIONS_PATH;
    ObjectNode body = buildRequestBody(request);
    log.info(
        "OpenAI-wire POST {} model={} tools={}",
        effectiveBaseUrl + completionsPath,
        request.model(),
        request.tools().stream().map(t -> t.getToolDefinition().name()).toList());

    // Use Java's HttpClient with explicit connect+read timeouts so a hung TCP
    // connection or stalled body never blocks the harness loop indefinitely.
    HttpClient jdkClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(jdkClient);
    factory.setReadTimeout(READ_TIMEOUT);
    RestClient client =
        RestClient.builder().baseUrl(effectiveBaseUrl).requestFactory(factory).build();
    JsonNode response;
    try {
      response =
          client
              .post()
              .uri(completionsPath)
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
    // Two input modes:
    //  (a) Phase 0/2 single-turn: systemPrompt + userPrompt fields
    //  (b) Phase 3 multi-turn: full Spring AI Message list (system, user, assistant
    //      with tool_calls, tool response). When messages() is non-null the simpler
    //      fields are ignored.
    if (request.messages() != null && !request.messages().isEmpty()) {
      for (Message m : request.messages()) {
        appendMessage(messages, m);
      }
    } else {
      if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
        ObjectNode sys = messages.addObject();
        sys.put("role", "system");
        sys.put("content", request.systemPrompt());
      }
      ObjectNode user = messages.addObject();
      user.put("role", "user");
      user.put("content", request.userPrompt() == null ? "" : request.userPrompt());
    }

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
      // RL-2026-06-004 — DeepSeek's thinking-mode models 400 on ANY tool_choice
      // ("Thinking mode does not support this tool_choice"), so NEVER send it on the native
      // DeepSeek
      // base-url. OpenAI/OpenRouter, by contrast, NEED the "auto" default sent when tools are
      // present
      // (omitting it makes gpt-5.x return text instead of calling tools) — so keep the default
      // there.
      boolean isDeepSeek = request.baseUrl() != null && request.baseUrl().contains("deepseek");
      if (!isDeepSeek) {
        body.put("tool_choice", request.toolChoice() == null ? "auto" : request.toolChoice());
      }
    }

    if (request.temperature() != null) body.put("temperature", request.temperature());
    if (request.maxCompletionTokens() != null)
      body.put("max_completion_tokens", request.maxCompletionTokens());
    if (request.extraBody() != null && !request.extraBody().isEmpty()) {
      for (var entry : request.extraBody().entrySet()) {
        if (entry.getKey() != null && entry.getValue() != null) {
          body.set(entry.getKey(), objectMapper.valueToTree(entry.getValue()));
        }
      }
    }

    return body;
  }

  /**
   * Serializes a Spring AI {@link Message} to OpenAI's chat-completions wire format and appends it
   * to the messages array. Supports the four message types V2 needs: SystemMessage, UserMessage,
   * AssistantMessage (with optional tool_calls), and ToolResponseMessage (one or more tool results,
   * each becoming a separate {@code role:"tool"} entry — OpenAI requires one tool message per
   * tool_call_id).
   */
  private void appendMessage(ArrayNode messages, Message m) {
    if (m instanceof SystemMessage sm) {
      ObjectNode n = messages.addObject();
      n.put("role", "system");
      n.put("content", sm.getText());
    } else if (m instanceof UserMessage um) {
      ObjectNode n = messages.addObject();
      n.put("role", "user");
      n.put("content", um.getText());
    } else if (m instanceof AssistantMessage am) {
      ObjectNode n = messages.addObject();
      n.put("role", "assistant");
      String text = am.getText();
      if (text != null && !text.isEmpty()) {
        n.put("content", text);
      } else {
        n.putNull("content");
      }
      // 2026-05-28 (RL-2026-05-052) — DeepSeek thinking-mode round-trip. V4-{Pro,Flash} both return
      // `message.reasoning_content` alongside the tool calls; the API then requires that same value
      // echoed back in the assistant turn of every follow-up call. parseResponse() captures it into
      // the AssistantMessage's `properties` map under key REASONING_CONTENT_META_KEY; we re-emit
      // here. OpenAI's own endpoint ignores extra fields, so this is safe for non-DeepSeek calls.
      if (am.getMetadata() != null) {
        Object rc = am.getMetadata().get(REASONING_CONTENT_META_KEY);
        if (rc instanceof String rcStr && !rcStr.isEmpty()) {
          n.put("reasoning_content", rcStr);
        }
      }
      if (am.getToolCalls() != null && !am.getToolCalls().isEmpty()) {
        ArrayNode tcs = n.putArray("tool_calls");
        for (var tc : am.getToolCalls()) {
          ObjectNode tcn = tcs.addObject();
          // OpenAI requires non-empty IDs to round-trip in the tool response.
          // Stable, collision-free synthetic ID when the upstream tool call lacks one.
          // `System.identityHashCode` is NOT unique across JVMs and can collide within one JVM
          // (rare but real → OpenAI returns 400 "tool_call_id mismatch"). UUIDs are safe.
          // Audit: 2026-05-12 line-by-line review item M #67.
          tcn.put(
              "id",
              (tc.id() == null || tc.id().isBlank())
                  ? "call_" + java.util.UUID.randomUUID().toString().replace("-", "")
                  : tc.id());
          tcn.put("type", "function");
          ObjectNode fn = tcn.putObject("function");
          fn.put("name", tc.name());
          fn.put("arguments", tc.arguments() == null ? "{}" : tc.arguments());
        }
      }
    } else if (m instanceof ToolResponseMessage trm) {
      // OpenAI: one role:"tool" message per ToolResponse, each with its own tool_call_id.
      for (var resp : trm.getResponses()) {
        ObjectNode n = messages.addObject();
        n.put("role", "tool");
        n.put("tool_call_id", resp.id());
        n.put("content", resp.responseData() == null ? "" : resp.responseData());
      }
    } else {
      throw new IllegalArgumentException(
          "Unsupported message type for OpenAI wrapper: " + m.getClass().getName());
    }
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

      // 2026-05-28 (RL-2026-05-052) — capture DeepSeek's reasoning_content into the
      // AssistantMessage metadata so appendMessage() can echo it back on follow-up calls (the
      // DeepSeek API rejects the follow-up otherwise). OpenAI proper doesn't return this field;
      // when absent we skip and the metadata stays empty.
      JsonNode reasoningNode = message.path("reasoning_content");
      java.util.Map<String, Object> properties = null;
      if (reasoningNode.isTextual() && !reasoningNode.asText().isEmpty()) {
        properties = java.util.Map.of(REASONING_CONTENT_META_KEY, reasoningNode.asText());
      }

      AssistantMessage.Builder builder =
          AssistantMessage.builder().content(content).toolCalls(toolCalls);
      if (properties != null) builder.properties(properties);
      AssistantMessage msg = builder.build();

      ChatGenerationMetadata genMeta =
          ChatGenerationMetadata.builder().finishReason(finishReason).build();
      generations.add(new Generation(msg, genMeta));
    }

    // Response-level metadata
    ChatResponseMetadata.Builder metaBuilder =
        ChatResponseMetadata.builder()
            .id(response.path("id").asText(""))
            .model(response.path("model").asText(requestedModel));
    JsonNode usage = response.path("usage");
    if (usage.isObject()) {
      int promptTokens = usage.path("prompt_tokens").asInt(0);
      int completionTokens = usage.path("completion_tokens").asInt(0);
      metaBuilder.usage(new DefaultUsage(promptTokens, completionTokens));

      // 2026-05-04 — billing audit: OpenAI returns cached prompt token count nested at
      // `usage.prompt_tokens_details.cached_tokens`. RunCostTracker reads this via metadata
      // key `cache_read_input_tokens` (its provider-uniform convention). Without this surface,
      // every OpenAI call's cached portion was billed at the full input rate even though
      // OpenAI auto-discounts cached tokens (~50% on gpt-4o family). The cached_tokens count
      // is INCLUDED in prompt_tokens (subset, not addition) — RunCostTracker.priceCall handles
      // the subtraction in its OpenAI branch.
      //
      // 2026-05-08 — set the metadata key UNCONDITIONALLY (even when 0) so the per-call log line
      // in RunCostTracker consistently reports cacheRead. This makes "auto-cache fired 0 times"
      // (real signal that the prefix isn't matching) distinguishable from "OpenAI returned no
      // metadata" (older API path / parser bug). OpenAI auto-caches prefixes ≥1024 tokens, so 0
      // hits on a multi-iteration task is a flag that the prefix is mutating.
      int cachedTokens = usage.path("prompt_tokens_details").path("cached_tokens").asInt(0);
      metaBuilder.keyValue("cache_read_input_tokens", cachedTokens);

      Double providerCost = numberOrNull(usage.path("cost"));
      if (providerCost != null) {
        metaBuilder.keyValue("provider_reported_cost_usd", providerCost);
      }
      Double upstreamCost =
          numberOrNull(usage.path("cost_details").path("upstream_inference_cost"));
      if (upstreamCost != null) {
        metaBuilder.keyValue("provider_upstream_cost_usd", upstreamCost);
      }
    }
    return new ChatResponse(generations, metaBuilder.build());
  }

  private static Double numberOrNull(JsonNode node) {
    if (node == null || node.isMissingNode() || node.isNull()) return null;
    if (node.isNumber()) return node.asDouble();
    if (node.isTextual()) {
      try {
        return Double.parseDouble(node.asText());
      } catch (NumberFormatException ignored) {
        return null;
      }
    }
    return null;
  }

  /** Request DTO for {@link #call(OpenAiToolCallRequest)}. */
  @Builder
  public record OpenAiToolCallRequest(
      String model,
      String userPrompt,
      String systemPrompt,
      List<Message> messages,
      List<ToolCallback> tools,
      String toolChoice,
      Double temperature,
      Integer maxCompletionTokens,
      String apiKey,
      String baseUrl,
      Map<String, Object> extraBody) {}
}

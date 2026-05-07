package com.navneet.executespec.agent;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

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
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * V2 Gemini native tool-calling client — bypasses Spring AI 1.1.4 entirely.
 *
 * <p><b>Why this exists (Spring AI 1.1.4 bug #2):</b> {@code GoogleGenAiChatModel.
 * responseCandidateToGeneration} throws {@link java.util.NoSuchElementException} ("No value
 * present") when parsing a Gemini follow-up response to a tool-result turn. Single-turn tool calls
 * work — verified in Phase 0/2 — but as soon as the harness threads a {@link ToolResponseMessage}
 * back and Gemini replies, Spring AI's response parser calls {@code Optional.get()} on an empty
 * Optional and blows up inside its own retry template.
 *
 * <p>This wrapper POSTs directly to Gemini's {@code generateContent} REST endpoint with a
 * hand-built JSON body and parses the response into Spring AI's {@link ChatResponse}/{@link
 * AssistantMessage} shapes so the rest of V2 stays provider-agnostic (same contract as {@link
 * OpenAiNativeToolClient}).
 *
 * <p>Wire format differences from OpenAI:
 *
 * <ul>
 *   <li>Messages are called {@code contents}; roles are {@code user} / {@code model} (not {@code
 *       assistant})
 *   <li>Tool calls are {@code functionCall:{name, args:{}}} parts inside a model turn
 *   <li>Tool results are {@code functionResponse:{name, response:{}}} parts inside a user turn —
 *       must be a JSON object, not a string
 *   <li>System prompt goes in {@code systemInstruction}, not in contents
 *   <li>Tools use a {@code functionDeclarations} wrapper and only a subset of JSON Schema keywords
 *       is supported — {@code additionalProperties} is rejected by Gemini's schema validator, so we
 *       strip it
 * </ul>
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class GeminiNativeToolClient {

  private static final String DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com";
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
  // 2026-04-26 hardening — was 120s. The HIGH-band Gemini run on
  // 2026-04-26 timed out at 25min after producing only 3.6K tokens; the
  // upstream Spring AI RetryTemplate happily waited 3 × 120s on each
  // stalled call, then re-tried, accumulating ~6 min/call across the
  // worker loop. 60s + the 429-aware retry loop below fail fast on a
  // stalled call instead of letting the eval timeout fire.
  private static final Duration READ_TIMEOUT = Duration.ofSeconds(60);

  /**
   * Max retries on HTTP 429 (rate limit) before bailing — observed in the 2026-04-26 eval run. Each
   * retry sleeps RATE_LIMIT_BACKOFF_MS × attempt.
   */
  private static final int RATE_LIMIT_RETRIES = 3;

  private static final long RATE_LIMIT_BACKOFF_MS = 2000L;

  private final ObjectMapper objectMapper;

  @Value("${spring.ai.google.genai.api-key:}")
  private String defaultApiKey;

  @Value("${spring.ai.google.genai.base-url:" + DEFAULT_BASE_URL + "}")
  private String baseUrl;

  /**
   * Cache of thought signatures keyed by synthetic tool-call ID.
   *
   * <p>Gemini 2.5+ thinking models emit a {@code thoughtSignature} alongside every {@code
   * functionCall} part in their response. The API rejects follow-up requests whose reconstituted
   * {@code functionCall} parts lack the original signature:
   *
   * <pre>
   * 400 Bad Request: "Function call is missing a thought_signature in functionCall parts.
   *                   This is required for tools to work correctly, and missing
   *                   thought_signature may lead to degraded model performance."
   * </pre>
   *
   * Spring AI's {@link AssistantMessage.ToolCall} record doesn't have a field for the signature, so
   * we cache it out-of-band keyed by our synthetic call ID. When the worker threads the
   * AssistantMessage back through the wrapper to make the next request, we look up the cached
   * signature and re-attach it to the serialized {@code functionCall} part.
   *
   * <p>Bounded + TTL to prevent leaks — a task's signatures are only needed for the lifetime of its
   * agent loop, which always completes in well under 30 minutes.
   */
  private final Cache<String, String> thoughtSignatureById =
      Caffeine.newBuilder().maximumSize(10_000).expireAfterWrite(30, TimeUnit.MINUTES).build();

  public ChatResponse call(GeminiToolCallRequest request) {
    String apiKey =
        (request.apiKey() != null && !request.apiKey().isBlank())
            ? request.apiKey()
            : defaultApiKey;
    if (apiKey == null || apiKey.isBlank()) {
      throw new IllegalStateException("Gemini api-key not configured (set GOOGLE_API_KEY)");
    }
    ObjectNode body = buildRequestBody(request);
    String uri = "/v1beta/models/" + request.model() + ":generateContent?key=" + apiKey;
    log.info(
        "Gemini wrapper POST {} model={} tools={}",
        baseUrl + "/v1beta/models/" + request.model() + ":generateContent",
        request.model(),
        request.tools().stream().map(t -> t.getToolDefinition().name()).toList());

    HttpClient jdkClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(jdkClient);
    Duration effectiveReadTimeout =
        request.readTimeoutSeconds() != null && request.readTimeoutSeconds() > 0
            ? Duration.ofSeconds(request.readTimeoutSeconds())
            : READ_TIMEOUT;
    factory.setReadTimeout(effectiveReadTimeout);
    RestClient client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    JsonNode response = postWithRateLimitRetry(client, uri, body);
    if (response == null) throw new IllegalStateException("Gemini returned empty response body");
    // DEBUG: raw request+response logging to diagnose empty-response issues. Temporary.
    try {
      log.info(
          "Gemini wrapper REQUEST body (truncated): {}",
          truncate(objectMapper.writeValueAsString(body), 2000));
      log.info(
          "Gemini wrapper RESPONSE body (truncated): {}",
          truncate(objectMapper.writeValueAsString(response), 2000));
    } catch (Exception ignored) {
    }
    return parseResponse(response, request.model());
  }

  /**
   * POST to Gemini with explicit 429-aware retry. Distinguishes:
   *
   * <ul>
   *   <li><b>429 Too Many Requests</b> — rate limit. Retry up to {@link #RATE_LIMIT_RETRIES} with
   *       linear back-off ({@link #RATE_LIMIT_BACKOFF_MS} × attempt). If the response includes a
   *       {@code Retry-After} header, honour it (capped at 30s to avoid pathological waits).
   *   <li><b>Other 4xx</b> — propagate immediately (validation / safety / auth — no point
   *       retrying).
   *   <li><b>5xx / network</b> — propagate to the upstream Spring RetryTemplate (caller decides
   *       backoff strategy).
   * </ul>
   *
   * 2026-04-26 hardening for the HIGH-band Gemini stall.
   */
  private JsonNode postWithRateLimitRetry(RestClient client, String uri, ObjectNode body) {
    int attempts = 0;
    while (true) {
      attempts++;
      try {
        return client
            .post()
            .uri(uri)
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .body(JsonNode.class);
      } catch (HttpClientErrorException.TooManyRequests e) {
        if (attempts > RATE_LIMIT_RETRIES) {
          log.error(
              "Gemini 429 retry exhausted after {} attempts: {}", attempts, e.getStatusText());
          throw e;
        }
        long sleepMs = computeBackoff(e, attempts);
        log.info(
            "Gemini 429 attempt {}/{} — sleeping {}ms then retrying",
            attempts,
            RATE_LIMIT_RETRIES,
            sleepMs);
        try {
          Thread.sleep(sleepMs);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          throw new RuntimeException("Gemini retry interrupted", ie);
        }
      }
    }
  }

  private long computeBackoff(HttpClientErrorException.TooManyRequests e, int attempt) {
    // Honour Retry-After if present (seconds form is the common one for Gemini).
    String retryAfter =
        e.getResponseHeaders() == null ? null : e.getResponseHeaders().getFirst("Retry-After");
    if (retryAfter != null && !retryAfter.isBlank()) {
      try {
        long s = Long.parseLong(retryAfter.trim());
        return Math.min(30_000L, Math.max(500L, s * 1000L));
      } catch (NumberFormatException ignored) {
        /* fall through to linear back-off */
      }
    }
    return RATE_LIMIT_BACKOFF_MS * attempt;
  }

  private static String truncate(String s, int max) {
    if (s == null) return "";
    return s.length() <= max
        ? s
        : s.substring(0, max) + "...[" + (s.length() - max) + " chars omitted]";
  }

  // ─── Request building ────────────────────────────────────────────────────

  private ObjectNode buildRequestBody(GeminiToolCallRequest request) {
    ObjectNode body = objectMapper.createObjectNode();

    ArrayNode contents = body.putArray("contents");
    String systemText = null;
    for (Message m : request.messages()) {
      if (m instanceof SystemMessage sm) {
        // Gemini: system lives in systemInstruction, not contents.
        if (systemText == null) systemText = sm.getText();
        else systemText = systemText + "\n\n" + sm.getText();
      } else if (m instanceof UserMessage um) {
        ObjectNode turn = contents.addObject();
        turn.put("role", "user");
        ArrayNode parts = turn.putArray("parts");
        parts.addObject().put("text", um.getText());
      } else if (m instanceof AssistantMessage am) {
        ObjectNode turn = contents.addObject();
        turn.put("role", "model");
        ArrayNode parts = turn.putArray("parts");
        String text = am.getText();
        if (text != null && !text.isEmpty()) {
          parts.addObject().put("text", text);
        }
        if (am.getToolCalls() != null) {
          for (var tc : am.getToolCalls()) {
            ObjectNode p = parts.addObject();
            ObjectNode fc = p.putObject("functionCall");
            fc.put("name", tc.name());
            JsonNode argsNode;
            try {
              argsNode =
                  (tc.arguments() == null || tc.arguments().isBlank())
                      ? objectMapper.createObjectNode()
                      : objectMapper.readTree(tc.arguments());
            } catch (Exception e) {
              argsNode = objectMapper.createObjectNode();
            }
            fc.set("args", argsNode);
            // Re-attach the thought signature captured from the original
            // response. Gemini 2.5+ thinking models require this on round-trip.
            String sig = thoughtSignatureById.getIfPresent(tc.id());
            if (sig != null && !sig.isBlank()) {
              p.put("thoughtSignature", sig);
            }
          }
        }
      } else if (m instanceof ToolResponseMessage trm) {
        // Each ToolResponse becomes a functionResponse part inside a single user turn.
        ObjectNode turn = contents.addObject();
        turn.put("role", "user");
        ArrayNode parts = turn.putArray("parts");
        for (var resp : trm.getResponses()) {
          ObjectNode p = parts.addObject();
          ObjectNode fr = p.putObject("functionResponse");
          fr.put("name", resp.name());
          JsonNode respNode;
          try {
            respNode =
                (resp.responseData() == null || resp.responseData().isBlank())
                    ? objectMapper.createObjectNode()
                    : objectMapper.readTree(resp.responseData());
          } catch (Exception e) {
            // Gemini requires an object; wrap non-JSON strings.
            ObjectNode wrap = objectMapper.createObjectNode();
            wrap.put("content", resp.responseData());
            respNode = wrap;
          }
          if (!respNode.isObject()) {
            ObjectNode wrap = objectMapper.createObjectNode();
            wrap.set("content", respNode);
            respNode = wrap;
          }
          fr.set("response", respNode);
        }
      } else {
        throw new IllegalArgumentException(
            "Unsupported message type for Gemini wrapper: " + m.getClass().getName());
      }
    }

    if (systemText != null && !systemText.isBlank()) {
      ObjectNode sys = body.putObject("systemInstruction");
      ArrayNode parts = sys.putArray("parts");
      parts.addObject().put("text", systemText);
    }

    if (request.tools() != null && !request.tools().isEmpty()) {
      ArrayNode toolsArr = body.putArray("tools");
      ObjectNode toolsObj = toolsArr.addObject();
      ArrayNode decls = toolsObj.putArray("functionDeclarations");
      for (ToolCallback tc : request.tools()) {
        ObjectNode decl = decls.addObject();
        decl.put("name", tc.getToolDefinition().name());
        decl.put("description", tc.getToolDefinition().description());
        try {
          JsonNode schema = objectMapper.readTree(tc.getToolDefinition().inputSchema());
          decl.set("parameters", sanitizeSchemaForGemini(schema));
        } catch (Exception e) {
          throw new IllegalArgumentException(
              "Invalid JSON schema for tool '" + tc.getToolDefinition().name() + "'", e);
        }
      }
      // Explicitly constrain Gemini to emit structured function calls only. Without
      // this, Gemini 2.5-flash sometimes emits Python code (e.g. "default_api.writeFile(...)")
      // as a function call attempt, which its own parser rejects as MALFORMED_FUNCTION_CALL
      // and returns an empty candidate.
      //
      // AUTO — model decides (text or function call). Works for multi-turn agents where
      //   the model sometimes legitimately emits plain text.
      // ANY  — model MUST emit a function call from allowedFunctionNames. Server rejects
      //   any other response. Use when the caller expects exactly one function to fire
      //   (e.g. planner's submitPlan); prevents gemini-2.5-flash's Python-code degradation.
      ObjectNode toolConfig = body.putObject("toolConfig");
      ObjectNode fcc = toolConfig.putObject("functionCallingConfig");
      if (Boolean.TRUE.equals(request.forceToolCall())) {
        fcc.put("mode", "ANY");
        ArrayNode allowed = fcc.putArray("allowedFunctionNames");
        for (ToolCallback tc : request.tools()) {
          allowed.add(tc.getToolDefinition().name());
        }
      } else {
        fcc.put("mode", "AUTO");
      }
    }

    ObjectNode gen = body.putObject("generationConfig");
    if (request.temperature() != null) gen.put("temperature", request.temperature());
    if (request.maxOutputTokens() != null) gen.put("maxOutputTokens", request.maxOutputTokens());

    return body;
  }

  /**
   * Gemini's schema validator rejects {@code additionalProperties} (not in the subset it supports).
   * Strip it recursively — semantically we lose strictness but the required-fields check still
   * applies.
   */
  private JsonNode sanitizeSchemaForGemini(JsonNode node) {
    if (node.isObject()) {
      ObjectNode copy = objectMapper.createObjectNode();
      node.fields()
          .forEachRemaining(
              e -> {
                if ("additionalProperties".equals(e.getKey())) return;
                copy.set(e.getKey(), sanitizeSchemaForGemini(e.getValue()));
              });
      return copy;
    }
    if (node.isArray()) {
      ArrayNode copy = objectMapper.createArrayNode();
      node.forEach(el -> copy.add(sanitizeSchemaForGemini(el)));
      return copy;
    }
    return node;
  }

  // ─── Response parsing ────────────────────────────────────────────────────

  private ChatResponse parseResponse(JsonNode response, String requestedModel) {
    List<Generation> generations = new ArrayList<>();
    JsonNode candidates = response.path("candidates");
    if (!candidates.isArray() || candidates.isEmpty()) {
      throw new IllegalStateException("Gemini response missing 'candidates': " + response);
    }
    for (JsonNode candidate : candidates) {
      JsonNode parts = candidate.path("content").path("parts");
      StringBuilder textBuf = new StringBuilder();
      List<AssistantMessage.ToolCall> toolCalls = new ArrayList<>();
      if (parts.isArray()) {
        int tcIdx = 0;
        for (JsonNode part : parts) {
          if (part.has("text")) {
            if (textBuf.length() > 0) textBuf.append("\n");
            textBuf.append(part.get("text").asText(""));
          } else if (part.has("functionCall")) {
            JsonNode fc = part.get("functionCall");
            String name = fc.path("name").asText("");
            JsonNode args = fc.path("args");
            String argsJson = args.isMissingNode() ? "{}" : args.toString();
            // Gemini doesn't always return IDs (2.5 never does; 3-preview sometimes does).
            // Prefer upstream ID if provided, synthesize otherwise.
            String upstreamId = fc.path("id").asText("");
            String id =
                upstreamId.isEmpty() ? "gemini_" + System.nanoTime() + "_" + (tcIdx++) : upstreamId;
            // Cache the thoughtSignature for this call so we can round-trip it
            // on subsequent requests (required by Gemini 2.5+ thinking models).
            String sig = part.path("thoughtSignature").asText(null);
            if (sig != null && !sig.isBlank()) {
              thoughtSignatureById.put(id, sig);
            }
            toolCalls.add(new AssistantMessage.ToolCall(id, "function", name, argsJson));
          }
        }
      }
      String finishReason = candidate.path("finishReason").asText(null);
      // Surface MALFORMED_FUNCTION_CALL (and other abnormal terminations) as a visible
      // error in the text — otherwise the worker sees an empty response and silently
      // exits with STOPPED_WITHOUT_COMPLETE, which masks the real problem.
      if (toolCalls.isEmpty()
          && textBuf.length() == 0
          && finishReason != null
          && !"STOP".equals(finishReason)) {
        String detail = candidate.path("finishMessage").asText("");
        String synthText =
            "[Gemini terminated with finishReason="
                + finishReason
                + (detail.isEmpty() ? "" : "; detail: " + truncate(detail, 400))
                + "]";
        textBuf.append(synthText);
        log.warn(
            "Gemini wrapper: candidate had no parts, finishReason={}, finishMessage={}",
            finishReason,
            truncate(detail, 400));
      }
      AssistantMessage msg =
          AssistantMessage.builder().content(textBuf.toString()).toolCalls(toolCalls).build();
      ChatGenerationMetadata genMeta =
          ChatGenerationMetadata.builder().finishReason(finishReason).build();
      generations.add(new Generation(msg, genMeta));
    }

    ChatResponseMetadata.Builder metaBuilder = ChatResponseMetadata.builder().model(requestedModel);
    JsonNode usage = response.path("usageMetadata");
    if (usage.isObject()) {
      int promptTokens = usage.path("promptTokenCount").asInt(0);
      int completionTokens = usage.path("candidatesTokenCount").asInt(0);
      metaBuilder.usage(new DefaultUsage(promptTokens, completionTokens));

      // 2026-05-04 — billing audit: Gemini's usageMetadata has two more fields the
      // previous parser dropped on the floor:
      //
      //   cachedContentTokenCount — context-cached subset of promptTokenCount (subset, not
      //                             addition). Today this is always 0 because we don't enable
      //                             Gemini context caching (no /v1beta/cachedContents setup) —
      //                             surfaced anyway as a safety net so the day caching IS
      //                             enabled, billing follows automatically.
      //   thoughtsTokenCount      — Gemini 2.5+ reasoning tokens (we use gemini-3-pro-preview
      //                             and gemini-3-flash-preview). Gemini bills these at the
      //                             OUTPUT rate. Previously dropped → under-billing whenever
      //                             thinking was active, which on complex prompts can be 2-5x
      //                             visible-output volume.
      //
      // RunCostTracker reads these via metadata keys `cache_read_input_tokens` (uniform across
      // providers) and `thinking_tokens` (Gemini-only, summed into outTok before pricing).
      int cachedContent = usage.path("cachedContentTokenCount").asInt(0);
      if (cachedContent > 0) {
        metaBuilder.keyValue("cache_read_input_tokens", cachedContent);
      }
      int thoughts = usage.path("thoughtsTokenCount").asInt(0);
      if (thoughts > 0) {
        metaBuilder.keyValue("thinking_tokens", thoughts);
      }
    }
    return new ChatResponse(generations, metaBuilder.build());
  }

  @Builder
  public record GeminiToolCallRequest(
      String model,
      List<Message> messages,
      List<ToolCallback> tools,
      Double temperature,
      Integer maxOutputTokens,
      String apiKey,
      /**
       * If true, sets {@code toolConfig.functionCallingConfig.mode=ANY} with {@code
       * allowedFunctionNames} set to the tool list — the API will reject any non-function-call
       * response at the server, preventing gemini-2.5-flash's "emit Python code as a function call"
       * degraded-performance failure mode. Use when the caller expects exactly one specific
       * function to be called.
       */
      Boolean forceToolCall,
      /**
       * Optional per-call HTTP read-timeout override (seconds). Falls through to the static {@link
       * #READ_TIMEOUT} default when null. Used by {@code OutlinePlannerAgent} to give large
       * single-shot planner calls on gemini-3-pro-preview enough headroom — the default 60s is too
       * tight for a thinking model emitting a multi-task plan, and the call would error with
       * "Request timed out: null" after which RunOrchestrator silently fell back to a 1-task
       * SINGLE_SHOT plan (cmatrix run a0b81542 on 2026-05-07).
       */
      Integer readTimeoutSeconds) {}
}

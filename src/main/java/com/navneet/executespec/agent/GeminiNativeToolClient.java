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
import com.navneet.executespec.config.featureflag.FeatureFlagService;

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
   * Max retries on HTTP 429 (rate limit) before bailing. Gemini 3.5 has a per-minute request cap; a
   * 429 means we must wait for the window to reset, so back-off is EXPONENTIAL and patient: {@code
   * RATE_LIMIT_BACKOFF_MS × 2^(attempt-1)} = 15s → 30s → 60s (per the rate-limit policy — "wait up
   * to 60s and retry"). Total patience ~105s across 3 attempts before propagating.
   */
  private static final int RATE_LIMIT_RETRIES = 3;

  private static final long RATE_LIMIT_BACKOFF_MS = 15_000L;

  private final ObjectMapper objectMapper;
  private final FeatureFlagService featureFlagService;

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

  /**
   * RL-2026-05-059 — EXPLICIT context caching (the "Phase 2" the 2026-05-04 billing-audit comment
   * below flagged as unimplemented). Gemini is the only provider on implicit/best-effort caching;
   * the 2026-05-31 50-instance eval measured its per-call cache-hit at 45.9% vs 88-97% for
   * Anthropic/OpenAI/DeepSeek, driving 668K input-tok/run and $33.98 total (most expensive
   * provider). The worker's [systemInstruction + tools] prefix is byte-identical across every turn
   * of a task (and across tasks sharing the same model + tool set), so we mint a {@code
   * cachedContents} resource for it once, reference it by name on each {@code generateContent}
   * call, and omit the cached parts from the request body — guaranteeing the largest stable chunk
   * is billed at the cached rate instead of relying on opportunistic implicit caching.
   *
   * <p>Keyed by SHA-256(model + systemText + toolsJson). Client TTL (45m) sits under the server TTL
   * (60m) so a live entry is always still valid server-side; a stale/evicted server cache surfaces
   * as a 4xx on generateContent, which we catch + retry inline (fail-safe). Flag-gated (default
   * OFF) — when off, behaviour is byte-identical to the pre-existing implicit-only path.
   */
  private static final String FLAG_EXPLICIT_CACHE = "gemini.explicit-cache.enabled";

  private static final String EXPLICIT_CACHE_TTL = "3600s";

  private final Cache<String, String> explicitCacheNameByPrefix =
      Caffeine.newBuilder().maximumSize(256).expireAfterWrite(45, TimeUnit.MINUTES).build();

  public ChatResponse call(GeminiToolCallRequest request) {
    String apiKey =
        (request.apiKey() != null && !request.apiKey().isBlank())
            ? request.apiKey()
            : defaultApiKey;
    if (apiKey == null || apiKey.isBlank()) {
      throw new IllegalStateException("Gemini api-key not configured (set GOOGLE_API_KEY)");
    }
    HttpClient jdkClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(jdkClient);
    Duration effectiveReadTimeout =
        request.readTimeoutSeconds() != null && request.readTimeoutSeconds() > 0
            ? Duration.ofSeconds(request.readTimeoutSeconds())
            : READ_TIMEOUT;
    factory.setReadTimeout(effectiveReadTimeout);
    RestClient client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();

    // RL-2026-05-059 — resolve (or mint) an explicit cache for the stable system+tools prefix.
    // null = caching disabled / ineligible / mint failed → inline body (today's behaviour).
    String prefixKey = null;
    String cacheName = null;
    if (explicitCacheEligible(request)) {
      prefixKey = explicitCachePrefixKey(request);
      cacheName = explicitCacheNameByPrefix.getIfPresent(prefixKey);
      log.info(
          "Gemini cache lookup key8={} model={} sysChars={} toolN={} hit={} cacheSize={}",
          prefixKey.substring(0, Math.min(8, prefixKey.length())),
          request.model(),
          systemTextOf(request.messages()).length(),
          request.tools() == null ? 0 : request.tools().size(),
          cacheName != null,
          explicitCacheNameByPrefix.estimatedSize());
      if (cacheName == null) {
        cacheName = createExplicitCache(client, request, apiKey);
        if (cacheName != null) explicitCacheNameByPrefix.put(prefixKey, cacheName);
      }
    }

    ObjectNode body = buildRequestBody(request, cacheName);
    String uri = "/v1beta/models/" + request.model() + ":generateContent?key=" + apiKey;
    log.info(
        "Gemini wrapper POST {} model={} tools={} cached={}",
        baseUrl + "/v1beta/models/" + request.model() + ":generateContent",
        request.model(),
        request.tools().stream().map(t -> t.getToolDefinition().name()).toList(),
        cacheName != null);

    JsonNode response;
    try {
      response = postWithRateLimitRetry(client, uri, body);
    } catch (HttpClientErrorException e) {
      // A cached prefix can expire / be GC'd server-side before our 45m client TTL; the call then
      // 4xxs. Evict + retry once inline so the run survives (fail-safe, no model/behaviour change).
      if (cacheName != null && isExplicitCacheError(e)) {
        if (prefixKey != null) explicitCacheNameByPrefix.invalidate(prefixKey);
        log.warn(
            "Gemini explicit-cache call rejected ({}); evicted + retrying inline",
            e.getStatusCode());
        response = postWithRateLimitRetry(client, uri, buildRequestBody(request, null));
      } else {
        throw e;
      }
    }
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
   *       EXPONENTIAL back-off (15s → 30s → 60s). If the response includes a {@code Retry-After}
   *       header, honour it (capped at 60s to avoid pathological waits).
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
        // Honour server's Retry-After, capped at 60s (our max patient wait).
        return Math.min(60_000L, Math.max(500L, s * 1000L));
      } catch (NumberFormatException ignored) {
        /* fall through to exponential back-off */
      }
    }
    // Exponential: 15s, 30s, 60s for attempts 1, 2, 3.
    return RATE_LIMIT_BACKOFF_MS << (attempt - 1);
  }

  private static String truncate(String s, int max) {
    if (s == null) return "";
    return s.length() <= max
        ? s
        : s.substring(0, max) + "...[" + (s.length() - max) + " chars omitted]";
  }

  private ObjectNode buildRequestBody(GeminiToolCallRequest request, String cacheName) {
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
        if (um.getText() != null && !um.getText().isBlank()) {
          parts.addObject().put("text", um.getText());
        }
        if (um.getMedia() != null) {
          for (var media : um.getMedia()) {
            ObjectNode inline = parts.addObject().putObject("inlineData");
            inline.put("mimeType", media.getMimeType().toString());
            inline.put(
                "data", java.util.Base64.getEncoder().encodeToString(media.getDataAsByteArray()));
          }
        }
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

    if (cacheName != null) {
      // RL-2026-05-059 — system_instruction + tools + tool_config ALL live in the cached
      // resource (toolConfig baked in at mint). Gemini 400s if a generateContent request also
      // sets any of them ("move those values to CachedContent"), so send ONLY cachedContent.
      body.put("cachedContent", cacheName);
    } else {
      if (systemText != null && !systemText.isBlank()) {
        ObjectNode sys = body.putObject("systemInstruction");
        ArrayNode parts = sys.putArray("parts");
        parts.addObject().put("text", systemText);
      }

      if (request.tools() != null && !request.tools().isEmpty()) {
        body.set("tools", buildToolsArray(request.tools()));
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
      //                             addition). Two paths populate it:
      //                             (1) implicit caching — auto-fires when the prefix exceeds
      //                                 ~1024 tokens (Flash) / ~4096 tokens (Pro) AND is
      //                                 byte-identical to a recent prior call. We rely on
      //                                 WorkerAgent keeping system + tools + early turns
      //                                 stable across iterations. The 2026-05-08 stagnation-
      //                                 message-as-SystemMessage bug invalidated the prefix;
      //                                 see WorkerAgentStagnationTest for the regression pin.
      //                             (2) explicit caching — caller POSTs to /v1beta/cachedContents
      //                                 to mint a cache resource then references it by ID.
      //                                 Not yet implemented; tracked as Phase 2 of the cache audit.
      //   thoughtsTokenCount      — Gemini 2.5+ reasoning tokens (we use gemini-3-pro-preview
      //                             and gemini-3-flash-preview). Gemini bills these at the
      //                             OUTPUT rate. Previously dropped → under-billing whenever
      //                             thinking was active, which on complex prompts can be 2-5x
      //                             visible-output volume.
      //
      // RunCostTracker reads these via metadata keys `cache_read_input_tokens` (uniform across
      // providers) and `thinking_tokens` (Gemini-only, summed into outTok before pricing). Always
      // set both keys (even at 0) so the per-call log line consistently reports cacheRead — that
      // way operators can distinguish "Gemini reported 0 hits" from "metadata absent entirely".
      int cachedContent = usage.path("cachedContentTokenCount").asInt(0);
      metaBuilder.keyValue("cache_read_input_tokens", cachedContent);
      int thoughts = usage.path("thoughtsTokenCount").asInt(0);
      if (thoughts > 0) {
        metaBuilder.keyValue("thinking_tokens", thoughts);
      }
    }
    return new ChatResponse(generations, metaBuilder.build());
  }

  /** Builds the Gemini {@code tools} value: {@code [{functionDeclarations:[...]}]} (sanitized). */
  private ArrayNode buildToolsArray(List<ToolCallback> tools) {
    ArrayNode toolsArr = objectMapper.createArrayNode();
    ObjectNode toolsObj = toolsArr.addObject();
    ArrayNode decls = toolsObj.putArray("functionDeclarations");
    for (ToolCallback tc : tools) {
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
    return toolsArr;
  }

  /** Concatenate all SystemMessages — the cacheable system prefix. "" when none. */
  private String systemTextOf(List<Message> messages) {
    StringBuilder sb = new StringBuilder();
    for (Message m : messages) {
      if (m instanceof SystemMessage sm) {
        if (sb.length() > 0) sb.append("\n\n");
        sb.append(sm.getText());
      }
    }
    return sb.toString();
  }

  /**
   * Explicit caching applies only when the flag is ON, the call is multi-turn (not a forced-ANY
   * single-shot), and there is both a system prefix and a tool set to cache.
   */
  private boolean explicitCacheEligible(GeminiToolCallRequest request) {
    if (!featureFlagService.isEnabled(FLAG_EXPLICIT_CACHE)) return false;
    if (request.tools() == null || request.tools().isEmpty()) return false;
    // Do NOT exclude forceToolCall: the multi-turn WORKER passes its tools via toolsOverride, which
    // callGemini maps to forceToolCall=true (ANY mode). That worker IS the main token consumer we
    // want cached. The ANY/AUTO mode is a per-request toolConfig that buildRequestBody's cache
    // branch still sends, so caching the prefix doesn't change the mode. (RL-2026-05-059 — the
    // initial cut wrongly excluded this and minted 0 caches.)
    return !systemTextOf(request.messages()).isBlank();
  }

  /** Cache key = SHA-256(model + systemText + toolsJson); an identical prefix shares one cache. */
  private String explicitCachePrefixKey(GeminiToolCallRequest request) {
    String tools;
    try {
      tools = objectMapper.writeValueAsString(buildToolsArray(request.tools()));
    } catch (Exception e) {
      tools = String.valueOf(request.tools().size());
    }
    return sha256(request.model() + "\u0000" + systemTextOf(request.messages()) + "\u0000" + tools);
  }

  /**
   * Mint a {@code cachedContents} resource for {model, systemInstruction, tools}. Returns its
   * server name ({@code cachedContents/...}) or null on ANY failure — most commonly a 400 when the
   * prefix is below the per-model minimum cacheable token count — so the caller falls back inline.
   */
  private String createExplicitCache(
      RestClient client, GeminiToolCallRequest request, String apiKey) {
    try {
      log.info(
          "Gemini explicit cache: attempting mint model={} sysChars={} tools={}",
          request.model(),
          systemTextOf(request.messages()).length(),
          request.tools().size());
      ObjectNode body = objectMapper.createObjectNode();
      body.put("model", "models/" + request.model());
      ObjectNode sys = body.putObject("systemInstruction");
      sys.putArray("parts").addObject().put("text", systemTextOf(request.messages()));
      body.set("tools", buildToolsArray(request.tools()));
      // toolConfig MUST live IN the cache — Gemini 400s if a generateContent request sets
      // system_instruction / tools / tool_config alongside cachedContent ("move those values to
      // CachedContent"). Bake the caller's mode in: ANY (forced) preserves the worker's
      // must-call-a-tool behavior (verified the cached call still emits a forced functionCall);
      // allowedFunctionNames references the cached tool names. (RL-2026-05-059 fix.)
      ObjectNode cacheToolConfig = body.putObject("toolConfig");
      ObjectNode cacheFcc = cacheToolConfig.putObject("functionCallingConfig");
      if (Boolean.TRUE.equals(request.forceToolCall())) {
        cacheFcc.put("mode", "ANY");
        ArrayNode allowed = cacheFcc.putArray("allowedFunctionNames");
        for (ToolCallback tc : request.tools()) {
          allowed.add(tc.getToolDefinition().name());
        }
      } else {
        cacheFcc.put("mode", "AUTO");
      }
      body.put("ttl", EXPLICIT_CACHE_TTL);
      JsonNode resp =
          client
              .post()
              .uri("/v1beta/cachedContents?key=" + apiKey)
              .contentType(MediaType.APPLICATION_JSON)
              .body(body)
              .retrieve()
              .body(JsonNode.class);
      String name = resp == null ? null : resp.path("name").asText(null);
      if (name != null && !name.isBlank()) {
        log.info("Gemini explicit cache minted: {} (model={})", name, request.model());
        return name;
      }
      return null;
    } catch (HttpClientErrorException he) {
      log.warn(
          "Gemini explicit cache mint FAILED http={} body={}",
          he.getStatusCode(),
          truncate(he.getResponseBodyAsString(), 500));
      return null;
    } catch (Exception e) {
      log.warn("Gemini explicit cache mint skipped: {}", e.toString());
      return null;
    }
  }

  /** True for the 4xx classes meaning "the referenced cachedContent is gone / invalid". */
  private boolean isExplicitCacheError(HttpClientErrorException e) {
    int code = e.getStatusCode().value();
    if (code == 403 || code == 404) return true;
    String b = e.getResponseBodyAsString();
    return b != null && b.toLowerCase().contains("cachedcontent");
  }

  private String sha256(String s) {
    try {
      java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
      byte[] h = md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder(h.length * 2);
      for (byte b : h) {
        sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
      }
      return sb.toString();
    } catch (Exception e) {
      return Integer.toHexString(s.hashCode());
    }
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

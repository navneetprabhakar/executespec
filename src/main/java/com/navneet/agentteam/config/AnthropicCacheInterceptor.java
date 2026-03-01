package com.navneet.agentteam.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;

/**
 * HTTP-level interceptor that enables Anthropic prompt caching for every request
 * routed to {@code api.anthropic.com}.
 *
 * <p>Two changes are made per request:
 * <ol>
 *   <li><strong>Beta header</strong> — adds {@code anthropic-beta: prompt-caching-2024-07-31}
 *       to activate the prompt-caching feature on Anthropic's side.</li>
 *   <li><strong>Cache-control block</strong> — Spring AI 1.0 serialises the system prompt
 *       as a plain JSON string: {@code "system": "..."}.  Anthropic's caching API requires
 *       an array of content blocks with an explicit {@code cache_control} marker.
 *       This interceptor transforms the string form into:
 *       <pre>{@code
 *       "system": [{"type":"text","text":"...","cache_control":{"type":"ephemeral"}}]
 *       }</pre>
 *       The marker tells Anthropic to write the system-prompt KV state to its cache on the
 *       first call and reuse it on every subsequent call within the 5-minute TTL window.
 *   </li>
 * </ol>
 *
 * <p><strong>Cache benefit for WorkerAgent:</strong> {@code worker.txt} is ~1 800 tokens.
 * Each task runs 5–15 LLM iterations, all with the same system prompt.  After the first
 * call (cache write), every subsequent call is a cache read: ~85 % lower latency and
 * ~90 % lower cost on the cached prefix.  For a 10-iteration task this typically saves
 * 3–6 minutes of LLM wait time per task.
 *
 * <p><strong>Safety:</strong> Any JSON parse or transform error is swallowed and the
 * original body is sent unmodified — caching failure never blocks a pipeline call.
 *
 * <p>Requests to other hosts (OpenAI, etc.) are passed through without modification.
 */
@Slf4j
public class AnthropicCacheInterceptor implements ClientHttpRequestInterceptor {

    private static final String BETA_HEADER    = "anthropic-beta";
    private static final String BETA_VALUE     = "prompt-caching-2024-07-31";
    private static final String ANTHROPIC_HOST = "anthropic.com";

    // ObjectMapper is thread-safe for concurrent read use
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public ClientHttpResponse intercept(HttpRequest request,
                                        byte[] body,
                                        ClientHttpRequestExecution execution) throws IOException {
        String host = request.getURI().getHost();

        // Only modify requests destined for Anthropic's API
        if (host == null || !host.contains(ANTHROPIC_HOST)) {
            return execution.execute(request, body);
        }

        // 1. Activate prompt-caching beta
        request.getHeaders().add(BETA_HEADER, BETA_VALUE);

        // 2. Transform "system": "text" → "system": [{..., "cache_control": {...}}]
        byte[] modifiedBody = injectCacheControl(body);

        // Spring AI pre-sets Content-Length from the original body. If we grew the body
        // we MUST fix the header, otherwise the HTTP client rejects it with
        // "expected N bytes but received M".
        if (modifiedBody.length != body.length) {
            request.getHeaders().set(HttpHeaders.CONTENT_LENGTH, String.valueOf(modifiedBody.length));
        }

        return execution.execute(request, modifiedBody);
    }

    // ── Body transformation ───────────────────────────────────────────────────

    /**
     * Reads the JSON request body. If {@code system} is a plain string, replaces it
     * with a single-element content-block array containing an {@code ephemeral}
     * cache_control marker. Returns the original body unchanged on any error or
     * when the field is already an array.
     */
    private byte[] injectCacheControl(byte[] body) {
        if (body == null || body.length == 0) return body;

        try {
            JsonNode root = mapper.readTree(body);
            if (!(root instanceof ObjectNode obj)) return body;

            JsonNode systemNode = obj.get("system");

            // Only transform when system is a plain string (Spring AI 1.0 default)
            if (systemNode == null || !systemNode.isTextual()) return body;

            String systemText = systemNode.asText();
            if (systemText.isBlank()) return body;

            // Build the cache_control block
            ObjectNode cacheControl = mapper.createObjectNode();
            cacheControl.put("type", "ephemeral");

            // Build the content block
            ObjectNode textBlock = mapper.createObjectNode();
            textBlock.put("type", "text");
            textBlock.put("text", systemText);
            textBlock.set("cache_control", cacheControl);

            // Wrap in array and replace the field
            ArrayNode systemArray = mapper.createArrayNode();
            systemArray.add(textBlock);
            obj.set("system", systemArray);

            byte[] modified = mapper.writeValueAsBytes(root);
            log.debug("[AnthropicCache] system prompt marked for caching ({} → {} bytes)",
                      body.length, modified.length);
            return modified;

        } catch (Exception e) {
            // Never fail a live pipeline call because of cache injection
            log.warn("[AnthropicCache] body transform failed — passing original body unchanged: {}",
                     e.getMessage());
            return body;
        }
    }
}

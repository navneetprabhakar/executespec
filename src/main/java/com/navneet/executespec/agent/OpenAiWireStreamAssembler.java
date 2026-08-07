package com.navneet.executespec.agent;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Reassembles an OpenAI-wire server-sent-event stream into the SAME response shape a non-streaming
 * {@code chat/completions} call returns.
 *
 * <h2>Design: streaming is a transport concern only</h2>
 *
 * Nothing above this class learns that streaming happened. The assembled object is fed to the
 * existing {@code parseResponse}, so tool-call id synthesis, the abnormal-finish-reason safety
 * gate, DeepSeek {@code reasoning_content} round-tripping and every cache/usage billing field keep
 * their exact current behaviour. That is deliberate: those paths are correctness- and
 * billing-sensitive, and a transport change must not quietly re-implement them.
 *
 * <h2>Wire shape being folded</h2>
 *
 * Chunks arrive as {@code data: {...}} lines terminated by {@code data: [DONE]}. Content arrives as
 * {@code delta.content} fragments. Tool calls arrive as {@code delta.tool_calls[]} entries keyed by
 * {@code index}, where {@code id} and {@code function.name} typically appear once on the first
 * fragment and {@code function.arguments} arrives as a partial JSON string split across many chunks
 * — so arguments MUST be concatenated in arrival order and parsed only at the end.
 *
 * <h2>Usage</h2>
 *
 * With {@code stream_options.include_usage} the provider appends a final chunk whose {@code usage}
 * is populated and whose {@code choices} array is empty. That usage node is copied VERBATIM so
 * nested cache fields ({@code prompt_tokens_details.cached_tokens}, DeepSeek's {@code
 * prompt_cache_hit_tokens}) survive untouched for the cost tracker. OpenAI documents that an
 * interrupted or cancelled stream may never deliver that chunk; {@link #sawUsage()} reports this
 * honestly rather than letting a zeroed usage object look like a measured zero.
 */
final class OpenAiWireStreamAssembler {

  private static final String DATA_PREFIX = "data:";
  private static final String DONE_SENTINEL = "[DONE]";

  private final ObjectMapper mapper;
  private final long maxResponseBytes;

  private final StringBuilder content = new StringBuilder();
  private final StringBuilder reasoning = new StringBuilder();

  /** Keyed by wire {@code index} so out-of-order or interleaved tool-call fragments still join. */
  private final Map<Integer, ToolCallAccumulator> toolCalls = new LinkedHashMap<>();

  private String id = "";
  private String model = "";
  private String finishReason;
  private JsonNode usage;
  private long decodedBytes;
  private boolean sawAnyChunk;

  OpenAiWireStreamAssembler(ObjectMapper mapper, long maxResponseBytes) {
    this.mapper = mapper;
    this.maxResponseBytes = maxResponseBytes;
  }

  /** True once any decodable chunk has arrived — the caller's time-to-first-token signal. */
  boolean sawAnyChunk() {
    return sawAnyChunk;
  }

  /** True when the provider delivered its final usage chunk. */
  boolean sawUsage() {
    return usage != null && usage.isObject();
  }

  /**
   * Consumes the stream to completion (or to {@code [DONE]}), invoking {@code onChunk} after each
   * decoded chunk so the caller can enforce its own liveness deadlines.
   */
  void consume(InputStream body, Runnable onChunk) throws java.io.IOException {
    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.isEmpty()) continue;
        if (!line.startsWith(DATA_PREFIX)) continue; // SSE comments / event: lines
        String payload = line.substring(DATA_PREFIX.length()).trim();
        if (payload.isEmpty()) continue;
        if (DONE_SENTINEL.equals(payload)) break;

        decodedBytes += payload.length();
        if (decodedBytes > maxResponseBytes) {
          throw new IllegalStateException(
              "OpenAI-wire stream exceeded the response byte ceiling before completion");
        }
        JsonNode chunk;
        try {
          chunk = mapper.readTree(payload);
        } catch (Exception malformed) {
          // A single malformed frame is a protocol failure, not something to paper over: the
          // assembled tool arguments would be silently truncated at that point.
          throw new IllegalStateException("OpenAI-wire stream contained a malformed chunk");
        }
        accept(chunk);
        sawAnyChunk = true;
        if (onChunk != null) onChunk.run();
      }
    }
  }

  private void accept(JsonNode chunk) {
    if (id.isEmpty()) id = chunk.path("id").asText("");
    if (model.isEmpty()) model = chunk.path("model").asText("");

    JsonNode usageNode = chunk.path("usage");
    if (usageNode.isObject()) usage = usageNode;

    JsonNode choices = chunk.path("choices");
    if (!choices.isArray()) return;
    for (JsonNode choice : choices) {
      String reason = choice.path("finish_reason").asText(null);
      if (reason != null && !reason.isBlank()) finishReason = reason;

      JsonNode delta = choice.path("delta");
      if (!delta.isObject()) continue;

      JsonNode contentNode = delta.path("content");
      if (contentNode.isTextual()) content.append(contentNode.asText());

      // DeepSeek thinking-mode round-trip; absent on OpenAI proper.
      JsonNode reasoningNode = delta.path("reasoning_content");
      if (reasoningNode.isTextual()) reasoning.append(reasoningNode.asText());

      JsonNode tcs = delta.path("tool_calls");
      if (tcs.isArray()) {
        for (JsonNode tc : tcs) {
          int index = tc.path("index").asInt(0);
          ToolCallAccumulator acc =
              toolCalls.computeIfAbsent(index, k -> new ToolCallAccumulator());
          String tcId = tc.path("id").asText("");
          if (!tcId.isBlank()) acc.id = tcId;
          String type = tc.path("type").asText("");
          if (!type.isBlank()) acc.type = type;
          JsonNode fn = tc.path("function");
          String name = fn.path("name").asText("");
          if (!name.isBlank()) acc.name = name;
          JsonNode args = fn.path("arguments");
          // Arguments are partial JSON fragments; concatenate in arrival order and parse later.
          if (args.isTextual()) acc.arguments.append(args.asText());
        }
      }
    }
  }

  /**
   * Folds everything received into a canonical non-streaming response body.
   *
   * <p>Deliberately does NOT synthesize a choices entry when nothing arrived: {@code parseResponse}
   * already fails closed on an empty choices array, and that existing guard is what stops a
   * content-free response being metered as a paid success.
   */
  ObjectNode toCompletionResponse(String requestedModel) {
    ObjectNode root = mapper.createObjectNode();
    root.put("id", id);
    root.put("model", model.isEmpty() ? requestedModel : model);

    ArrayNode choices = root.putArray("choices");
    boolean hasContent = content.length() > 0;
    if (hasContent || !toolCalls.isEmpty() || finishReason != null) {
      ObjectNode choice = choices.addObject();
      choice.put("index", 0);
      if (finishReason != null) choice.put("finish_reason", finishReason);

      ObjectNode message = choice.putObject("message");
      message.put("role", "assistant");
      message.put("content", content.toString());
      if (reasoning.length() > 0) message.put("reasoning_content", reasoning.toString());

      if (!toolCalls.isEmpty()) {
        ArrayNode tcs = message.putArray("tool_calls");
        for (ToolCallAccumulator acc : toolCalls.values()) {
          ObjectNode tc = tcs.addObject();
          tc.put("id", acc.id);
          tc.put("type", acc.type == null || acc.type.isBlank() ? "function" : acc.type);
          ObjectNode fn = tc.putObject("function");
          fn.put("name", acc.name == null ? "" : acc.name);
          // Empty arguments become "{}" to match the non-streaming default parseResponse applies.
          String args = acc.arguments.toString();
          fn.put("arguments", args.isEmpty() ? "{}" : args);
        }
      }
    }

    if (usage != null && usage.isObject()) root.set("usage", usage);
    return root;
  }

  private static final class ToolCallAccumulator {
    private String id = "";
    private String type;
    private String name;
    private final StringBuilder arguments = new StringBuilder();
  }
}

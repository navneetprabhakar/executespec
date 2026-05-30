package com.navneet.executespec.context;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.navneet.executespec.observability.Metrics;
import com.navneet.executespec.tool.ExecutionContext;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Redis-backed persistence for in-flight V2 worker context.
 *
 * <p><b>Key scheme:</b>
 *
 * <pre>
 * v2:agent-ctx:{runId}:{taskId}  → serialized SavedContext (JSON, 24h TTL)
 * </pre>
 *
 * <p><b>Why hand-rolled serialization:</b> Spring AI's {@code Message} hierarchy (SystemMessage,
 * UserMessage, AssistantMessage, ToolResponseMessage) isn't trivially round-trippable via Jackson —
 * each subclass has its own constructor/builder shape, and the generic deserializer can't
 * reconstruct the right subclass from an arbitrary JSON. We emit a minimal discriminated-union
 * format with a {@code type} tag per message and reconstruct the correct subclass on read.
 *
 * <p><b>Failure policy:</b> all methods are best-effort. If Redis is unavailable, save/load/delete
 * log a WARN and return {@code Optional.empty()} / no-op. The worker never fails because context
 * persistence failed.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ContextStore {

  /** TTL for saved contexts. Matches the spec in {@code v2.context.package-info}. */
  public static final Duration CONTEXT_TTL = Duration.ofHours(24);

  /** Key prefix — namespace the store so it can't collide with V1 or other apps. */
  public static final String KEY_PREFIX = "v2:agent-ctx:";

  // Optional so missing Redis config (e.g. in unit tests) doesn't crash startup.
  @Autowired(required = false)
  private StringRedisTemplate redis;

  private final ObjectMapper objectMapper;
  private final Metrics metrics;

  /** Compose the Redis key for a given ExecutionContext. */
  public static String keyFor(String runId, String taskId) {
    return KEY_PREFIX + runId + ":" + taskId;
  }

  public static String keyFor(ExecutionContext ctx) {
    return keyFor(ctx.runId(), ctx.taskId());
  }

  /**
   * Persist a snapshot. Best-effort: Redis outages are logged but never propagate.
   *
   * @return byte size written (or 0 if skipped / failed)
   */
  public int save(ExecutionContext ctx, List<Message> history, int iteration) {
    if (redis == null) {
      log.debug(
          "ContextStore save SKIPPED (Redis not wired) run={} task={}", ctx.runId(), ctx.taskId());
      metrics.recordContextSave("skipped", 0);
      return 0;
    }
    try {
      String json = serialize(history, iteration, ctx);
      String key = keyFor(ctx);
      redis.opsForValue().set(key, json, CONTEXT_TTL);
      int size = json.getBytes(StandardCharsets.UTF_8).length;
      log.debug(
          "ContextStore save OK run={} task={} iter={} bytes={}",
          ctx.runId(),
          ctx.taskId(),
          iteration,
          size);
      metrics.recordContextSave("ok", size);
      return size;
    } catch (Exception e) {
      log.warn(
          "ContextStore save FAILED run={} task={} iter={}: {}",
          ctx.runId(),
          ctx.taskId(),
          iteration,
          e.getMessage());
      metrics.recordContextSave("failed", 0);
      return 0;
    }
  }

  /** Read a snapshot back. Empty if missing, TTL-expired, or Redis is down. */
  public Optional<SavedContext> load(ExecutionContext ctx) {
    if (redis == null) return Optional.empty();
    try {
      String json = redis.opsForValue().get(keyFor(ctx));
      if (json == null) return Optional.empty();
      return Optional.of(deserialize(json));
    } catch (Exception e) {
      log.warn(
          "ContextStore load FAILED run={} task={}: {}", ctx.runId(), ctx.taskId(), e.getMessage());
      return Optional.empty();
    }
  }

  /** Remove the snapshot for a task — called when the task completes successfully. */
  public void delete(ExecutionContext ctx) {
    if (redis == null) return;
    try {
      redis.delete(keyFor(ctx));
    } catch (Exception e) {
      log.warn(
          "ContextStore delete FAILED run={} task={}: {}",
          ctx.runId(),
          ctx.taskId(),
          e.getMessage());
    }
  }

  /**
   * Remove every snapshot for the given run (all task IDs). Called from {@link
   * com.navneet.executespec.harness.SequentialExecutor#execute} at run-end so per-task snapshots
   * don't squat in Redis for the full 24h TTL after the run has already finished.
   *
   * <p>RL-2026-05-052 (T3.14) — Redis cleanup hygiene. Best-effort: Redis outages logged but never
   * propagate.
   *
   * @return number of keys deleted (or 0 if Redis is down or no keys matched).
   */
  public long deleteAll(String runId) {
    if (redis == null || runId == null || runId.isBlank()) return 0;
    try {
      java.util.Set<String> keys = redis.keys(KEY_PREFIX + runId + ":*");
      if (keys == null || keys.isEmpty()) return 0;
      Long deleted = redis.delete(keys);
      log.info(
          "ContextStore deleteAll run={} keysFound={} keysDeleted={}", runId, keys.size(), deleted);
      return deleted == null ? 0 : deleted;
    } catch (Exception e) {
      log.warn("ContextStore deleteAll FAILED run={}: {}", runId, e.getMessage());
      return 0;
    }
  }

  /** True iff a snapshot exists for this context. Cheap probe used by recovery logic. */
  public boolean exists(ExecutionContext ctx) {
    if (redis == null) return false;
    try {
      return Boolean.TRUE.equals(redis.hasKey(keyFor(ctx)));
    } catch (Exception e) {
      return false;
    }
  }

  private String serialize(List<Message> history, int iteration, ExecutionContext ctx) {
    ObjectNode root = objectMapper.createObjectNode();
    root.put("runId", ctx.runId());
    root.put("taskId", ctx.taskId());
    root.put("iteration", iteration);
    root.put("savedAtEpochMs", System.currentTimeMillis());
    ArrayNode msgs = root.putArray("history");
    for (Message m : history) {
      msgs.add(serializeMessage(m));
    }
    return root.toString();
  }

  private ObjectNode serializeMessage(Message m) {
    ObjectNode n = objectMapper.createObjectNode();
    if (m instanceof SystemMessage sm) {
      n.put("type", "system");
      n.put("text", sm.getText());
    } else if (m instanceof UserMessage um) {
      n.put("type", "user");
      n.put("text", um.getText());
    } else if (m instanceof AssistantMessage am) {
      n.put("type", "assistant");
      n.put("text", am.getText() == null ? "" : am.getText());
      if (am.getToolCalls() != null && !am.getToolCalls().isEmpty()) {
        ArrayNode tcs = n.putArray("toolCalls");
        for (var tc : am.getToolCalls()) {
          ObjectNode t = tcs.addObject();
          t.put("id", tc.id() == null ? "" : tc.id());
          t.put("type", tc.type() == null ? "function" : tc.type());
          t.put("name", tc.name());
          t.put("arguments", tc.arguments() == null ? "{}" : tc.arguments());
        }
      }
    } else if (m instanceof ToolResponseMessage trm) {
      n.put("type", "tool");
      ArrayNode resps = n.putArray("responses");
      for (var resp : trm.getResponses()) {
        ObjectNode r = resps.addObject();
        r.put("id", resp.id());
        r.put("name", resp.name() == null ? "" : resp.name());
        r.put("responseData", resp.responseData() == null ? "" : resp.responseData());
      }
    } else {
      // Unknown message type — store a placeholder so we don't lose the count.
      n.put("type", "unknown");
      n.put("class", m.getClass().getName());
      n.put("text", String.valueOf(m));
    }
    return n;
  }

  private SavedContext deserialize(String json) throws Exception {
    JsonNode root = objectMapper.readTree(json);
    String runId = root.path("runId").asText("");
    String taskId = root.path("taskId").asText("");
    int iteration = root.path("iteration").asInt(0);
    long savedAt = root.path("savedAtEpochMs").asLong(0L);
    List<Message> history = new ArrayList<>();
    JsonNode arr = root.path("history");
    if (arr.isArray()) {
      for (JsonNode n : arr) {
        Message m = deserializeMessage(n);
        if (m != null) history.add(m);
      }
    }
    return SavedContext.builder()
        .runId(runId)
        .taskId(taskId)
        .iteration(iteration)
        .history(history)
        .savedAt(Instant.ofEpochMilli(savedAt))
        .sizeBytes(json.getBytes(StandardCharsets.UTF_8).length)
        .build();
  }

  private Message deserializeMessage(JsonNode n) {
    String type = n.path("type").asText("");
    return switch (type) {
      case "system" -> new SystemMessage(n.path("text").asText(""));
      case "user" -> new UserMessage(n.path("text").asText(""));
      case "assistant" -> {
        List<AssistantMessage.ToolCall> toolCalls = new ArrayList<>();
        JsonNode tcs = n.path("toolCalls");
        if (tcs.isArray()) {
          for (JsonNode tc : tcs) {
            toolCalls.add(
                new AssistantMessage.ToolCall(
                    tc.path("id").asText(""),
                    tc.path("type").asText("function"),
                    tc.path("name").asText(""),
                    tc.path("arguments").asText("{}")));
          }
        }
        yield AssistantMessage.builder()
            .content(n.path("text").asText(""))
            .toolCalls(toolCalls)
            .build();
      }
      case "tool" -> {
        List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
        JsonNode rs = n.path("responses");
        if (rs.isArray()) {
          for (JsonNode r : rs) {
            responses.add(
                new ToolResponseMessage.ToolResponse(
                    r.path("id").asText(""),
                    r.path("name").asText(""),
                    r.path("responseData").asText("")));
          }
        }
        yield ToolResponseMessage.builder().responses(responses).build();
      }
      default -> {
        log.warn("ContextStore unknown message type on load: {}", type);
        yield null;
      }
    };
  }
}

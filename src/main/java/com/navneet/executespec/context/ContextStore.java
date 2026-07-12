package com.navneet.executespec.context;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

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
public class ContextStore {

  /** TTL for saved contexts. Matches the spec in {@code v2.context.package-info}. */
  public static final Duration CONTEXT_TTL = Duration.ofHours(24);

  /** Key prefix — namespace the store so it can't collide with V1 or other apps. */
  public static final String KEY_PREFIX = "v2:agent-ctx:";

  private final StringRedisTemplate redis;
  private final ObjectMapper objectMapper;
  private final Metrics metrics;
  private final boolean diskCheckpointEnabled;

  @Autowired
  public ContextStore(
      ObjectMapper objectMapper,
      Metrics metrics,
      ObjectProvider<StringRedisTemplate> redisProvider,
      @Value("${durability.worker-context.enabled:true}") boolean diskCheckpointEnabled) {
    this(objectMapper, metrics, redisProvider.getIfAvailable(), diskCheckpointEnabled);
  }

  ContextStore(ObjectMapper objectMapper, Metrics metrics, StringRedisTemplate redis) {
    this(objectMapper, metrics, redis, true);
  }

  ContextStore(
      ObjectMapper objectMapper,
      Metrics metrics,
      StringRedisTemplate redis,
      boolean diskCheckpointEnabled) {
    this.objectMapper = objectMapper;
    this.metrics = metrics;
    this.redis = redis;
    this.diskCheckpointEnabled = diskCheckpointEnabled;
  }

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
    if (ctx == null || history == null) return 0;
    String json = serialize(history, iteration, ctx);
    int size = json.getBytes(StandardCharsets.UTF_8).length;
    boolean redisSaved = false;
    try {
      if (redis != null) {
        redis.opsForValue().set(keyFor(ctx), json, CONTEXT_TTL);
        redisSaved = true;
      }
    } catch (Exception e) {
      log.warn(
          "ContextStore Redis save FAILED run={} task={} iter={}: {}",
          ctx.runId(),
          ctx.taskId(),
          iteration,
          e.getMessage());
    }
    boolean diskSaved = writeDiskCheckpoint(ctx, json);
    if (redisSaved || diskSaved) {
      log.debug(
          "ContextStore save OK run={} task={} iter={} bytes={} redis={} disk={}",
          ctx.runId(),
          ctx.taskId(),
          iteration,
          size,
          redisSaved,
          diskSaved);
      metrics.recordContextSave(redisSaved ? "ok" : "disk", size);
      return size;
    }
    metrics.recordContextSave(redis == null ? "skipped" : "failed", 0);
    return 0;
  }

  /** Read a snapshot back. Empty if missing, TTL-expired, or Redis is down. */
  public Optional<SavedContext> load(ExecutionContext ctx) {
    if (ctx == null) return Optional.empty();
    boolean redisRejected = false;
    boolean redisFailed = false;
    if (redis != null) {
      try {
        String json = redis.opsForValue().get(keyFor(ctx));
        if (json != null) {
          Optional<SavedContext> snapshot = deserializeForContext(json, ctx);
          if (snapshot.isPresent()) {
            metrics.recordContextLoad("redis");
            return snapshot;
          }
          redisRejected = true;
          log.warn(
              "ContextStore Redis snapshot identity mismatch run={} task={}; ignoring payload",
              ctx.runId(),
              ctx.taskId());
        }
      } catch (Exception e) {
        redisFailed = true;
        log.warn(
            "ContextStore Redis load FAILED run={} task={}: {}",
            ctx.runId(),
            ctx.taskId(),
            e.getMessage());
      }
    }
    Optional<SavedContext> diskSnapshot = readDiskCheckpoint(ctx);
    if (diskSnapshot.isPresent()) {
      metrics.recordContextLoad("disk");
      return diskSnapshot;
    }
    if (redisRejected) {
      metrics.recordContextLoad("identity_mismatch");
    } else if (redisFailed) {
      metrics.recordContextLoad("redis_error");
    } else {
      metrics.recordContextLoad("miss");
    }
    return Optional.empty();
  }

  /** Remove the snapshot for a task — called when the task completes successfully. */
  public void delete(ExecutionContext ctx) {
    if (ctx == null) return;
    if (redis != null) {
      try {
        redis.delete(keyFor(ctx));
      } catch (Exception e) {
        log.warn(
            "ContextStore Redis delete FAILED run={} task={}: {}",
            ctx.runId(),
            ctx.taskId(),
            e.getMessage());
      }
    }
    if (diskCheckpointEnabled) {
      try {
        Files.deleteIfExists(diskCheckpointPath(ctx));
      } catch (Exception e) {
        log.warn(
            "ContextStore disk delete FAILED run={} task={}: {}",
            ctx.runId(),
            ctx.taskId(),
            e.getMessage());
      }
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
    return deleteAll(runId, null);
  }

  /** Delete Redis snapshots and workspace checkpoints when the run workspace is known. */
  public long deleteAll(String runId, Path workspaceRoot) {
    if (runId == null || runId.isBlank()) return 0;
    long deleted = 0;
    if (redis != null) {
      try {
        java.util.Set<String> keys = redis.keys(KEY_PREFIX + runId + ":*");
        if (keys != null && !keys.isEmpty()) {
          Long redisDeleted = redis.delete(keys);
          deleted += redisDeleted == null ? 0 : redisDeleted;
          log.info(
              "ContextStore deleteAll run={} keysFound={} keysDeleted={}",
              runId,
              keys.size(),
              redisDeleted);
        }
      } catch (Exception e) {
        log.warn("ContextStore Redis deleteAll FAILED run={}: {}", runId, e.getMessage());
      }
    }
    if (diskCheckpointEnabled && workspaceRoot != null) {
      try {
        Path workerDir = workspaceRoot.resolve(".agent-context").resolve("worker");
        if (Files.isDirectory(workerDir)) {
          try (var files = Files.list(workerDir)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
              if (Files.deleteIfExists(file)) deleted++;
            }
          }
          try {
            Files.deleteIfExists(workerDir);
          } catch (Exception ignored) {
            // Keep a non-empty or concurrently-used directory.
          }
        }
      } catch (Exception e) {
        log.warn("ContextStore disk deleteAll FAILED run={}: {}", runId, e.getMessage());
      }
    }
    return deleted;
  }

  /** True iff a snapshot exists for this context. Cheap probe used by recovery logic. */
  public boolean exists(ExecutionContext ctx) {
    if (ctx == null) return false;
    if (redis != null) {
      try {
        if (Boolean.TRUE.equals(redis.hasKey(keyFor(ctx)))) return true;
      } catch (Exception e) {
        log.debug("ContextStore Redis exists failed run={} task={}", ctx.runId(), ctx.taskId());
      }
    }
    return diskCheckpointEnabled && Files.isRegularFile(diskCheckpointPath(ctx));
  }

  /** Workspace-local checkpoint path; the workspace is the durable handoff boundary. */
  static Path diskCheckpointPath(ExecutionContext ctx) {
    String task = safeSegment(ctx.taskId());
    return ctx.workspaceRoot().resolve(".agent-context").resolve("worker").resolve(task + ".json");
  }

  private boolean writeDiskCheckpoint(ExecutionContext ctx, String json) {
    if (!diskCheckpointEnabled) return false;
    Path target = diskCheckpointPath(ctx);
    Path temp = target.resolveSibling(target.getFileName() + ".tmp");
    try {
      Files.createDirectories(target.getParent());
      Files.writeString(
          temp,
          json + "\n",
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE,
          StandardOpenOption.TRUNCATE_EXISTING,
          StandardOpenOption.WRITE);
      try {
        Files.move(
            temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (java.nio.file.AtomicMoveNotSupportedException e) {
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
      }
      return true;
    } catch (Exception e) {
      log.warn(
          "ContextStore disk checkpoint FAILED run={} task={}: {}",
          ctx.runId(),
          ctx.taskId(),
          e.getMessage());
      try {
        Files.deleteIfExists(temp);
      } catch (Exception ignored) {
        // best-effort cleanup
      }
      return false;
    }
  }

  private Optional<SavedContext> readDiskCheckpoint(ExecutionContext ctx) {
    if (!diskCheckpointEnabled) return Optional.empty();
    try {
      Path path = diskCheckpointPath(ctx);
      if (!Files.isRegularFile(path)) return Optional.empty();
      Optional<SavedContext> snapshot =
          deserializeForContext(Files.readString(path, StandardCharsets.UTF_8), ctx);
      if (snapshot.isEmpty()) {
        log.warn(
            "ContextStore disk snapshot identity mismatch run={} task={}; ignoring checkpoint",
            ctx.runId(),
            ctx.taskId());
      }
      return snapshot;
    } catch (Exception e) {
      log.warn(
          "ContextStore disk load FAILED run={} task={}: {}",
          ctx.runId(),
          ctx.taskId(),
          e.getMessage());
      return Optional.empty();
    }
  }

  /**
   * Parse a persisted snapshot only when its embedded identity matches the key/path being read.
   *
   * <p>This is a deliberately fail-closed boundary for restart and provider-handoff recovery: a
   * copied workspace, stale Redis key, or task-id collision must never feed another run's
   * conversation into the current worker. The IDs are present in every snapshot written by this
   * store; an absent/blank identity is therefore treated as invalid rather than as a legacy
   * wildcard.
   */
  private Optional<SavedContext> deserializeForContext(String json, ExecutionContext ctx) {
    if (json == null || ctx == null) return Optional.empty();
    try {
      SavedContext snapshot = deserialize(json);
      if (!java.util.Objects.equals(ctx.runId(), snapshot.runId())
          || !java.util.Objects.equals(ctx.taskId(), snapshot.taskId())
          || snapshot.runId() == null
          || snapshot.runId().isBlank()
          || snapshot.taskId() == null
          || snapshot.taskId().isBlank()) {
        return Optional.empty();
      }
      return Optional.of(snapshot);
    } catch (Exception e) {
      log.warn(
          "ContextStore snapshot parse FAILED run={} task={}: {}",
          ctx.runId(),
          ctx.taskId(),
          e.getMessage());
      return Optional.empty();
    }
  }

  private static String safeSegment(String value) {
    String safe = value == null ? "task" : value.replaceAll("[^A-Za-z0-9_.-]", "_");
    if (safe.isBlank()) safe = "task";
    return safe.length() <= 160 ? safe : safe.substring(0, 160);
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

package com.navneet.executespec.context;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.observability.Metrics;
import com.navneet.executespec.security.SafeWorkspacePath;
import com.navneet.executespec.tool.ExecutionContext;

import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
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

  /**
   * RR-502 — hard ceiling on one serialized snapshot. The worker intentionally keeps full history,
   * so repeated/large tool output can inflate a checkpoint without bound; above this cap the
   * checkpoint is skipped (best-effort durability degrades explicitly) rather than writing an
   * oversized Redis value and duplicating multi-MiB byte arrays across Redis + disk every save.
   */
  static final int MAX_SNAPSHOT_BYTES = 8 * 1024 * 1024;

  /** RR-501 — bounded SCAN/UNLINK batch size so run cleanup never blocks the Redis event loop. */
  private static final int SCAN_BATCH = 256;

  private final StringRedisTemplate redis;
  private final ContextSnapshotCodec codec;
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
    this.codec = new ContextSnapshotCodec(objectMapper);
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
    if (size > MAX_SNAPSHOT_BYTES) {
      // RR-502 — refuse to amplify an oversized snapshot into Redis + disk. Skipping a checkpoint
      // is safe (best-effort durability); the next in-budget save re-establishes recovery state.
      log.warn(
          "ContextStore snapshot OVERSIZE run={} task={} iter={} bytes={} cap={} — checkpoint"
              + " skipped",
          ctx.runId(),
          ctx.taskId(),
          iteration,
          size,
          MAX_SNAPSHOT_BYTES);
      metrics.recordContextSave("skipped_oversize", 0);
      return 0;
    }
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
        Files.deleteIfExists(guardedCheckpointPath(ctx));
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
        long redisDeleted = scanAndUnlink(KEY_PREFIX + runId + ":*");
        deleted += redisDeleted;
        if (redisDeleted > 0) {
          log.info("ContextStore deleteAll run={} keysDeleted={}", runId, redisDeleted);
        }
      } catch (Exception e) {
        log.warn("ContextStore Redis deleteAll FAILED run={}: {}", runId, e.getMessage());
      }
    }
    if (diskCheckpointEnabled && workspaceRoot != null) {
      try {
        // RR-500 — this loop DELETES files. The workspace holds a customer-controlled clone, so
        // `.agent-context` / `.agent-context/worker` may be a symlink to a host directory. Prove
        // containment with no-follow semantics before enumerating, and again per entry, so cleanup
        // can never delete outside the run workspace.
        Path workerDir = SafeWorkspacePath.resolveWithin(workspaceRoot, ".agent-context/worker");
        if (Files.isDirectory(workerDir, LinkOption.NOFOLLOW_LINKS)) {
          try (var files = Files.list(workerDir)) {
            for (Path file :
                files
                    .filter(f -> Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS))
                    .filter(f -> SafeWorkspacePath.isWithin(workspaceRoot, f))
                    .toList()) {
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

  /**
   * RR-501 — enumerate a run's task keys with a bounded, cursor-based SCAN and reclaim them with
   * non-blocking UNLINK, instead of the blocking {@code KEYS} command. {@code KEYS} scans the whole
   * keyspace on the single Redis event loop and materializes every match, so a single run
   * completion could stall auth, SSE, gates, rate limits and unrelated runs as the keyspace grows.
   * SCAN yields bounded batches; UNLINK frees them off the server's critical path.
   */
  private long scanAndUnlink(String matchPattern) {
    long removed = 0;
    List<String> batch = new ArrayList<>(SCAN_BATCH);
    ScanOptions options = ScanOptions.scanOptions().match(matchPattern).count(SCAN_BATCH).build();
    try (Cursor<String> cursor = redis.scan(options)) {
      while (cursor.hasNext()) {
        batch.add(cursor.next());
        if (batch.size() >= SCAN_BATCH) removed += unlinkBatch(batch);
      }
    }
    removed += unlinkBatch(batch);
    return removed;
  }

  private long unlinkBatch(List<String> keys) {
    if (keys.isEmpty()) return 0;
    Long count;
    try {
      count = redis.unlink(keys);
    } catch (Exception unlinkUnsupported) {
      // UNLINK is Redis 4+; fall back to DEL on an older/limited server.
      count = redis.delete(keys);
    }
    keys.clear();
    return count == null ? 0 : count;
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
    if (!diskCheckpointEnabled) return false;
    try {
      return Files.isRegularFile(guardedCheckpointPath(ctx), LinkOption.NOFOLLOW_LINKS);
    } catch (IOException | SecurityException e) {
      log.warn(
          "ContextStore disk exists probe refused run={} task={}: {}",
          ctx.runId(),
          ctx.taskId(),
          e.getMessage());
      return false;
    }
  }

  /** Workspace-local checkpoint path; the workspace is the durable handoff boundary. */
  static Path diskCheckpointPath(ExecutionContext ctx) {
    String task = safeSegment(ctx.taskId());
    return ctx.workspaceRoot().resolve(".agent-context").resolve("worker").resolve(task + ".json");
  }

  /**
   * Same target as {@link #diskCheckpointPath(ExecutionContext)}, but proven to stay inside the run
   * workspace (RR-500). The workspace holds a cloned, customer-controlled repository, so {@code
   * .agent-context} or {@code .agent-context/worker} can be a symlink pointing at a host directory.
   * Without this check the checkpoint write escapes the workspace and — far worse — run cleanup
   * enumerates and DELETES every regular file in the link target.
   *
   * @throws IOException when the workspace root cannot be canonicalized
   * @throws SecurityException when any component is a symlink or escapes the workspace
   */
  private static Path guardedCheckpointPath(ExecutionContext ctx) throws IOException {
    String task = safeSegment(ctx.taskId());
    return SafeWorkspacePath.resolveWithin(
        ctx.workspaceRoot(), ".agent-context/worker/" + task + ".json");
  }

  private boolean writeDiskCheckpoint(ExecutionContext ctx, String json) {
    if (!diskCheckpointEnabled) return false;
    Path target;
    Path temp;
    try {
      target = guardedCheckpointPath(ctx);
      temp = target.resolveSibling(target.getFileName() + ".tmp");
    } catch (IOException | SecurityException e) {
      log.warn(
          "ContextStore disk checkpoint REFUSED run={} task={}: {}",
          ctx.runId(),
          ctx.taskId(),
          e.getMessage());
      return false;
    }
    try {
      Files.createDirectories(target.getParent());
      // Re-prove containment: createDirectories ran against a customer-controlled tree.
      SafeWorkspacePath.requireWithin(ctx.workspaceRoot(), target);
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
      Path path = guardedCheckpointPath(ctx);
      if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
        return Optional.empty();
      }
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
      if (isExpired(snapshot)) {
        log.warn(
            "ContextStore snapshot EXPIRED run={} task={} savedAt={}; ignoring",
            ctx.runId(),
            ctx.taskId(),
            snapshot.savedAt());
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

  /**
   * RR-503 — disk checkpoints can outlive Redis (retained/restored workspaces), so recovery must
   * honour the documented 24h freshness window that only Redis enforced. An unknown or
   * older-than-{@link #CONTEXT_TTL} snapshot is rejected, so a stale conversation is never
   * rehydrated against a newer plan/workspace.
   */
  private static boolean isExpired(SavedContext snapshot) {
    Instant savedAt = snapshot == null ? null : snapshot.savedAt();
    if (savedAt == null || savedAt.toEpochMilli() <= 0L) return true;
    return savedAt.isBefore(Instant.now().minus(CONTEXT_TTL));
  }

  /**
   * RR-504 — derive a collision-resistant checkpoint file name from the FULL UTF-8 task id. The
   * previous scheme replaced every non-{@code [A-Za-z0-9_.-]} character with {@code _} and
   * truncated at 160 chars, so distinct planner task ids (for example {@code a/b} vs {@code a?b},
   * or long ids sharing a 160-char prefix) collided onto one file — one task could then erase
   * another's restart state. A SHA-256 hex digest is unique per distinct id and always
   * filesystem-safe; the embedded run/task identity is still validated on load.
   */
  private static String safeSegment(String value) {
    String source = value == null || value.isBlank() ? "task" : value;
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(source.getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(hash.length * 2);
      for (byte b : hash) {
        hex.append(Character.forDigit((b >> 4) & 0xF, 16));
        hex.append(Character.forDigit(b & 0xF, 16));
      }
      return hex.toString();
    } catch (NoSuchAlgorithmException e) {
      // SHA-256 is guaranteed on every JRE; fall back defensively rather than fail the checkpoint.
      return Integer.toHexString(source.hashCode());
    }
  }

  private String serialize(List<Message> history, int iteration, ExecutionContext ctx) {
    return codec.serialize(history, iteration, ctx.runId(), ctx.taskId());
  }

  private SavedContext deserialize(String json) throws Exception {
    return codec.deserialize(json);
  }
}

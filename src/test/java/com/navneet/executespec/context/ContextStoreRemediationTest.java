package com.navneet.executespec.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.navneet.executespec.observability.Metrics;
import com.navneet.executespec.tool.ExecutionContext;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

@ExtendWith(MockitoExtension.class)
class ContextStoreRemediationTest {

  @Mock private Metrics metrics;
  @Mock private StringRedisTemplate redis;
  @Mock private Cursor<String> cursor;

  @TempDir Path tempDir;

  /** RR-504 — task ids that the old canonicaliser collapsed to one file map to distinct files. */
  @Test
  void distinctTaskIdsThatCollidedUnderTheOldSchemeGetDistinctCheckpointFiles() {
    ContextStore store = new ContextStore(new ObjectMapper(), metrics, (StringRedisTemplate) null);
    ExecutionContext a = new ExecutionContext("run-1", "a/b", tempDir);
    ExecutionContext b = new ExecutionContext("run-1", "a?b", tempDir);

    assertThat(store.save(a, List.of(new UserMessage("belongs to a/b")), 1)).isPositive();
    assertThat(store.save(b, List.of(new UserMessage("belongs to a?b")), 1)).isPositive();

    Path pathA = ContextStore.diskCheckpointPath(a);
    Path pathB = ContextStore.diskCheckpointPath(b);
    assertThat(pathA).isNotEqualTo(pathB); // old scheme collapsed both to "a_b.json"
    assertThat(store.load(a).orElseThrow().history().get(0).getText()).isEqualTo("belongs to a/b");
    assertThat(store.load(b).orElseThrow().history().get(0).getText()).isEqualTo("belongs to a?b");
  }

  /** RR-503 — a disk checkpoint older than the 24h TTL is rejected instead of rehydrated. */
  @Test
  void rejectsDiskCheckpointOlderThanTheContextTtl() throws Exception {
    ContextStore store = new ContextStore(new ObjectMapper(), metrics, (StringRedisTemplate) null);
    ExecutionContext ctx = new ExecutionContext("run-1", "task-1", tempDir);
    store.save(ctx, List.of(new UserMessage("stale")), 3);

    Path checkpoint = ContextStore.diskCheckpointPath(ctx);
    ObjectMapper mapper = new ObjectMapper();
    ObjectNode node = (ObjectNode) mapper.readTree(Files.readString(checkpoint));
    long ancient =
        System.currentTimeMillis() - ContextStore.CONTEXT_TTL.toMillis() - 60_000L; // >24h ago
    node.put("savedAtEpochMs", ancient);
    Files.writeString(checkpoint, node.toString());

    assertThat(store.load(ctx)).isEmpty();
  }

  /** RR-502 — an oversized snapshot is skipped rather than amplified into Redis + disk. */
  @Test
  void skipsOversizedSnapshotWithoutWritingCheckpoint() {
    ContextStore store = new ContextStore(new ObjectMapper(), metrics, (StringRedisTemplate) null);
    ExecutionContext ctx = new ExecutionContext("run-1", "task-1", tempDir);
    String huge = "x".repeat(ContextStore.MAX_SNAPSHOT_BYTES + 1_024);

    assertThat(store.save(ctx, List.of(new UserMessage(huge)), 1)).isZero();
    assertThat(Files.exists(ContextStore.diskCheckpointPath(ctx))).isFalse();
    verify(metrics).recordContextSave("skipped_oversize", 0);
  }

  /** RR-501 — run cleanup enumerates keys with SCAN, never the blocking KEYS command. */
  @Test
  void deleteAllUsesScanNotKeys() {
    ContextStore store = new ContextStore(new ObjectMapper(), metrics, redis);
    when(redis.scan(any(ScanOptions.class))).thenReturn(cursor);
    when(cursor.hasNext()).thenReturn(false);

    store.deleteAll("run-1");

    verify(redis).scan(any(ScanOptions.class));
    verify(redis, never()).keys(any());
  }
}

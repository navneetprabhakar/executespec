package com.navneet.executespec.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.observability.Metrics;
import com.navneet.executespec.tool.ExecutionContext;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class ContextStoreTest {

  @Mock private Metrics metrics;
  @Mock private StringRedisTemplate redis;
  @Mock private ValueOperations<String, String> ops;

  @TempDir Path tempDir;

  private ExecutionContext context;

  @BeforeEach
  void setUp() {
    context = new ExecutionContext("run-1", "task-1", tempDir);
  }

  @Test
  void failsOpenWhenRedisAbsent() {
    ContextStore store = new ContextStore(new ObjectMapper(), metrics, (StringRedisTemplate) null);

    assertThat(store.save(context, List.of(new UserMessage("hello")), 3)).isPositive();
    assertThat(store.load(context)).isPresent();
    assertThat(store.exists(context)).isTrue();
    store.delete(context);
    assertThat(store.load(context)).isEmpty();
    assertThat(store.exists(context)).isFalse();
    assertThat(store.deleteAll("run-1")).isZero();

    verify(metrics).recordContextSave(eq("disk"), anyInt());
    verify(metrics).recordContextLoad("disk");
    verify(metrics).recordContextLoad("miss");
  }

  @Test
  void saveAndLoadRoundTripsMessagesThroughRedis() {
    ContextStore store = new ContextStore(new ObjectMapper(), metrics, redis);
    when(redis.opsForValue()).thenReturn(ops);
    ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);

    int bytes =
        store.save(
            context,
            List.of(new SystemMessage("system prompt"), new UserMessage("user prompt")),
            7);

    assertThat(bytes).isPositive();
    verify(ops).set(eq(ContextStore.keyFor(context)), json.capture(), eq(Duration.ofHours(24)));
    verify(metrics).recordContextSave(eq("ok"), anyInt());

    when(ops.get(ContextStore.keyFor(context))).thenReturn(json.getValue());
    SavedContext saved = store.load(context).orElseThrow();

    assertThat(saved.runId()).isEqualTo("run-1");
    assertThat(saved.taskId()).isEqualTo("task-1");
    assertThat(saved.iteration()).isEqualTo(7);
    assertThat(saved.history()).hasSize(2);
    assertThat(saved.history().get(0)).isInstanceOf(SystemMessage.class);
    assertThat(saved.history().get(0).getText()).isEqualTo("system prompt");
    assertThat(saved.history().get(1)).isInstanceOf(UserMessage.class);
    assertThat(saved.history().get(1).getText()).isEqualTo("user prompt");
    verify(metrics).recordContextLoad("redis");
  }

  @Test
  void loadsWorkspaceCheckpointWhenRedisKeyIsMissingAfterRestart() {
    ContextStore first = new ContextStore(new ObjectMapper(), metrics, redis);
    when(redis.opsForValue()).thenReturn(ops);
    first.save(context, List.of(new UserMessage("survive restart")), 11);

    ContextStore restarted =
        new ContextStore(new ObjectMapper(), metrics, (StringRedisTemplate) null);
    SavedContext saved = restarted.load(context).orElseThrow();

    assertThat(saved.iteration()).isEqualTo(11);
    assertThat(saved.history()).singleElement().isInstanceOf(UserMessage.class);
    assertThat(saved.history().get(0).getText()).isEqualTo("survive restart");
    assertThat(Files.isRegularFile(ContextStore.diskCheckpointPath(context))).isTrue();
    verify(metrics).recordContextLoad("disk");
  }

  @Test
  void rejectsRedisSnapshotWhoseEmbeddedIdentityDoesNotMatchRequestedContext() throws Exception {
    ContextStore store = new ContextStore(new ObjectMapper(), metrics, redis, false);
    when(redis.opsForValue()).thenReturn(ops);
    ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
    store.save(context, List.of(new UserMessage("belongs to run-1")), 2);
    verify(ops).set(eq(ContextStore.keyFor(context)), json.capture(), eq(Duration.ofHours(24)));

    ObjectMapper mapper = new ObjectMapper();
    com.fasterxml.jackson.databind.node.ObjectNode foreign =
        (com.fasterxml.jackson.databind.node.ObjectNode)
            mapper.readTree(json.getValue()).deepCopy();
    foreign.put("runId", "another-run");
    when(ops.get(ContextStore.keyFor(context))).thenReturn(foreign.toString());

    assertThat(store.load(context)).isEmpty();
    verify(metrics).recordContextLoad("identity_mismatch");
  }

  @Test
  void recordsRedisErrorWhenNoDiskCheckpointCanBeRecovered() {
    ContextStore store = new ContextStore(new ObjectMapper(), metrics, redis, false);
    when(redis.opsForValue()).thenThrow(new IllegalStateException("redis unavailable"));

    assertThat(store.load(context)).isEmpty();

    verify(metrics).recordContextLoad("redis_error");
  }

  @Test
  void rejectsDiskSnapshotWhoseEmbeddedTaskIdentityDoesNotMatchPath() throws Exception {
    ContextStore store = new ContextStore(new ObjectMapper(), metrics, (StringRedisTemplate) null);
    store.save(context, List.of(new UserMessage("belongs to task-1")), 2);

    Path checkpoint = ContextStore.diskCheckpointPath(context);
    ObjectMapper mapper = new ObjectMapper();
    com.fasterxml.jackson.databind.node.ObjectNode foreign =
        (com.fasterxml.jackson.databind.node.ObjectNode)
            mapper.readTree(Files.readString(checkpoint)).deepCopy();
    foreign.put("taskId", "another-task");
    Files.writeString(checkpoint, foreign.toString());

    assertThat(store.load(context)).isEmpty();
  }

  @Test
  void deleteAllRemovesWorkspaceCheckpointsAtRunEnd() {
    ContextStore store = new ContextStore(new ObjectMapper(), metrics, (StringRedisTemplate) null);
    store.save(context, List.of(new UserMessage("cleanup me")), 4);

    assertThat(store.deleteAll("run-1", tempDir)).isEqualTo(1);
    assertThat(Files.exists(ContextStore.diskCheckpointPath(context))).isFalse();
    assertThat(store.load(context)).isEmpty();
  }
}

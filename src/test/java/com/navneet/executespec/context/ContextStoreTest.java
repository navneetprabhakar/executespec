package com.navneet.executespec.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.observability.Metrics;
import com.navneet.executespec.tool.ExecutionContext;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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

  @Test
  void failsOpenWhenRedisAbsent() {
    ContextStore store = new ContextStore(new ObjectMapper(), metrics, (StringRedisTemplate) null);
    ExecutionContext ctx = context();

    assertThat(store.save(ctx, List.of(new UserMessage("hello")), 3)).isZero();
    assertThat(store.load(ctx)).isEmpty();
    assertThat(store.exists(ctx)).isFalse();
    assertThat(store.deleteAll("run-1")).isZero();

    verify(metrics).recordContextSave("skipped", 0);
  }

  @Test
  void saveAndLoadRoundTripsMessagesThroughRedis() {
    ContextStore store = new ContextStore(new ObjectMapper(), metrics, redis);
    ExecutionContext ctx = context();
    when(redis.opsForValue()).thenReturn(ops);
    ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);

    int bytes =
        store.save(
            ctx, List.of(new SystemMessage("system prompt"), new UserMessage("user prompt")), 7);

    assertThat(bytes).isPositive();
    verify(ops).set(eq(ContextStore.keyFor(ctx)), json.capture(), eq(Duration.ofHours(24)));
    verify(metrics).recordContextSave(eq("ok"), anyInt());

    when(ops.get(ContextStore.keyFor(ctx))).thenReturn(json.getValue());
    SavedContext saved = store.load(ctx).orElseThrow();

    assertThat(saved.runId()).isEqualTo("run-1");
    assertThat(saved.taskId()).isEqualTo("task-1");
    assertThat(saved.iteration()).isEqualTo(7);
    assertThat(saved.history()).hasSize(2);
    assertThat(saved.history().get(0)).isInstanceOf(SystemMessage.class);
    assertThat(saved.history().get(0).getText()).isEqualTo("system prompt");
    assertThat(saved.history().get(1)).isInstanceOf(UserMessage.class);
    assertThat(saved.history().get(1).getText()).isEqualTo("user prompt");
  }

  private static ExecutionContext context() {
    return new ExecutionContext("run-1", "task-1", Path.of("/tmp/executespec-context-test"));
  }
}

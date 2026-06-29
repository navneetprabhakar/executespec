package com.navneet.executespec.sse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class SseBroadcasterReplayLogTest {

  @Test
  void replayLogPreservesToolResultStatusEventsForLocalHandsAttach() {
    ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    RedisMessageListenerContainer listenerContainer = mock(RedisMessageListenerContainer.class);
    @SuppressWarnings("unchecked")
    ListOperations<String, String> listOps = mock(ListOperations.class);
    AtomicReference<String> storedJson = new AtomicReference<>();

    when(redis.opsForList()).thenReturn(listOps);
    when(listOps.rightPush(eq("sse:replay:run-1"), anyString()))
        .thenAnswer(
            invocation -> {
              storedJson.set(invocation.getArgument(1));
              return 1L;
            });
    when(listOps.range(eq("sse:replay:run-1"), eq(0L), eq(-1L)))
        .thenAnswer(invocation -> List.of("{malformed", storedJson.get()));

    SseBroadcaster broadcaster =
        new SseBroadcaster(
            mapper, publisher, new SimpleMeterRegistry(), redis, listenerContainer);

    broadcaster.emit(
        "run-1",
        RunEvent.toolResultStatus(
            "run-1", "task-1", "call-1", "shell", "REJECTED", "unknown_call"));

    List<Map<String, Object>> replay = broadcaster.getReplayLog("run-1");

    assertThat(replay).hasSize(1);
    Map<String, Object> event = replay.get(0);
    assertThat(event.get("type")).isEqualTo("TOOL_RESULT_STATUS");
    assertThat(event.get("runId")).isEqualTo("run-1");
    assertThat(event.get("taskId")).isEqualTo("task-1");
    assertThat(event.get("payload")).isEqualTo("REJECTED");
    assertThat(event.get("schemaVersion")).isEqualTo(1);
    @SuppressWarnings("unchecked")
    Map<String, Object> data = (Map<String, Object>) event.get("data");
    assertThat(data.get("callId")).isEqualTo("call-1");
    assertThat(data.get("toolName")).isEqualTo("shell");
    assertThat(data.get("status")).isEqualTo("REJECTED");
    assertThat(data.get("reason")).isEqualTo("unknown_call");
    verify(redis).expire(eq("sse:replay:run-1"), any());
  }
}

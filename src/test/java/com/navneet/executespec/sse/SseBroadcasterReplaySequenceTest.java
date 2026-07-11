package com.navneet.executespec.sse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.test.util.ReflectionTestUtils;

import io.micrometer.core.instrument.MeterRegistry;

class SseBroadcasterReplaySequenceTest {

  @Test
  void sequenceDoesNotReuseBoundedReplayListIndexes() throws Exception {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    ValueOperations<String, String> values = mock(ValueOperations.class);
    ListOperations<String, String> lists = mock(ListOperations.class);
    when(redis.opsForValue()).thenReturn(values);
    when(redis.opsForList()).thenReturn(lists);
    when(values.increment(anyString())).thenReturn(1L, 500L, 501L);

    List<String> writes = new ArrayList<>();
    when(lists.rightPush(anyString(), anyString()))
        .thenAnswer(
            invocation -> {
              writes.add(invocation.getArgument(1));
              return (long) writes.size();
            });

    SseBroadcaster broadcaster =
        new SseBroadcaster(
            new ObjectMapper(),
            mock(ApplicationEventPublisher.class),
            mock(MeterRegistry.class),
            redis,
            mock(RedisMessageListenerContainer.class));

    long first = invokeAppend(broadcaster, "run-1", "{\"type\":\"A\"}");
    long trimmed = invokeAppend(broadcaster, "run-1", "{\"type\":\"B\"}");
    long afterTrim = invokeAppend(broadcaster, "run-1", "{\"type\":\"C\"}");

    assertThat(first).isEqualTo(1);
    assertThat(trimmed).isEqualTo(500);
    assertThat(afterTrim).isEqualTo(501);
    JsonNode last = new ObjectMapper().readTree(writes.get(2));
    assertThat(last.path("seq").asLong()).isEqualTo(501);
    assertThat(last.path("payload").asText()).isEqualTo("{\"type\":\"C\"}");

    when(lists.range(anyString(), any(Long.class), any(Long.class))).thenReturn(writes);
    assertThat(broadcaster.getReplayLog("run-1")).hasSize(3);
    assertThat(broadcaster.getReplayLog("run-1").get(2)).containsEntry("type", "C");
  }

  private static long invokeAppend(SseBroadcaster broadcaster, String runId, String payload) {
    return (long)
        ReflectionTestUtils.invokeMethod(broadcaster, "appendToReplayLog", runId, payload);
  }
}

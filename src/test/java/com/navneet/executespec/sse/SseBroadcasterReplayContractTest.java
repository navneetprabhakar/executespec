package com.navneet.executespec.sse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Provider-free contract checks for the server side of the Web/CLI SSE replay protocol.
 *
 * <p>The client-side inclusive-replay dedupe contract lives in the standalone CLI core tests. This
 * class pins the corresponding server invariants without starting Spring, Redis, or an LLM.
 */
class SseBroadcasterReplayContractTest {

  private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
  private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
  private final ValueOperations<String, String> sequenceOps = mock(ValueOperations.class);
  private final ListOperations<String, String> replayOps = mock(ListOperations.class);

  private SseBroadcaster broadcaster() {
    when(redis.opsForValue()).thenReturn(sequenceOps);
    when(redis.opsForList()).thenReturn(replayOps);
    return new SseBroadcaster(
        mapper,
        mock(ApplicationEventPublisher.class),
        mock(MeterRegistry.class),
        redis,
        mock(RedisMessageListenerContainer.class));
  }

  @Test
  void replayCursorIsMonotonicEvenWhenTheBoundedListIsTrimmed() throws Exception {
    when(sequenceOps.increment("sse:sequence:run-1")).thenReturn(501L, 502L);

    Method append =
        SseBroadcaster.class.getDeclaredMethod("appendToReplayLog", String.class, String.class);
    append.setAccessible(true);
    SseBroadcaster service = broadcaster();

    assertThat(append.invoke(service, "run-1", "first")).isEqualTo(501L);
    assertThat(append.invoke(service, "run-1", "second")).isEqualTo(502L);

    var entries = org.mockito.ArgumentCaptor.forClass(String.class);
    org.mockito.Mockito.verify(replayOps, org.mockito.Mockito.times(2))
        .rightPush(org.mockito.ArgumentMatchers.eq("sse:replay:run-1"), entries.capture());
    assertThat(mapper.readTree(entries.getAllValues().get(0)).path("seq").asLong()).isEqualTo(501L);
    assertThat(mapper.readTree(entries.getAllValues().get(1)).path("seq").asLong()).isEqualTo(502L);
    assertThat(mapper.readTree(entries.getAllValues().get(0)).path("payload").asText())
        .isEqualTo("first");
    assertThat(mapper.readTree(entries.getAllValues().get(1)).path("payload").asText())
        .isEqualTo("second");
  }

  @Test
  void replayLogUnwrapsSequenceEnvelopeAndKeepsLegacyEventShape() throws Exception {
    String typed =
        mapper.writeValueAsString(
            RunEvent.goalDecision("run-1", "task-1", "done", Map.of("state", "DONE")));
    when(replayOps.range("sse:replay:run-1", 0, -1))
        .thenReturn(
            List.of(
                mapper.writeValueAsString(Map.of("seq", 42, "payload", typed)),
                mapper.writeValueAsString(RunEvent.log("run-1", "task-1", "legacy"))));

    List<Map<String, Object>> replay = broadcaster().getReplayLog("run-1");

    assertThat(replay).hasSize(2);
    assertThat(replay.get(0))
        .containsEntry("type", "GOAL_DECISION")
        .containsEntry("schemaVersion", 1);
    assertThat(replay.get(0)).containsEntry("payload", "done");
    assertThat(replay.get(0)).containsEntry("data", Map.of("state", "DONE"));
    assertThat(replay.get(1)).containsEntry("type", "LOG").containsEntry("payload", "legacy");
  }
}

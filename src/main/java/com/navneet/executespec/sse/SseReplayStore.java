package com.navneet.executespec.sse;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.data.redis.core.StringRedisTemplate;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** Bounded Redis replay persistence and reconnect projection for SSE run events. */
@Slf4j
@RequiredArgsConstructor
final class SseReplayStore {

  private static final String REPLAY_PREFIX = "sse:replay:";
  private static final String SEQUENCE_PREFIX = "sse:sequence:";
  private static final Duration REPLAY_TTL = Duration.ofHours(24);

  private final ObjectMapper mapper;
  private final StringRedisTemplate redis;

  long append(String runId, String json) {
    try {
      Long sequence = redis.opsForValue().increment(SEQUENCE_PREFIX + runId);
      if (sequence == null) return 0;
      appendEntry(runId, json, sequence);
      return sequence;
    } catch (Exception e) {
      log.debug("[SSE] Redis replay log write failed for run {}: {}", runId, e.getMessage());
      return 0;
    }
  }

  private void appendEntry(String runId, String json, long sequence) {
    if (sequence <= 0) {
      append(runId, json);
      return;
    }
    String key = REPLAY_PREFIX + runId;
    try {
      redis.expire(SEQUENCE_PREFIX + runId, REPLAY_TTL);
      String entry = mapper.writeValueAsString(Map.of("seq", sequence, "payload", json));
      redis.opsForList().rightPush(key, entry);
      redis.opsForList().trim(key, -SseBroadcaster.MAX_REPLAY_EVENTS, -1);
      redis.expire(key, REPLAY_TTL);
    } catch (Exception e) {
      log.debug("[SSE] Redis replay mirror failed for run {}: {}", runId, e.getMessage());
    }
  }

  long currentSequence(String runId) {
    try {
      String stored = redis.opsForValue().get(SEQUENCE_PREFIX + runId);
      return stored == null ? 0 : Math.max(0, Long.parseLong(stored));
    } catch (Exception unavailable) {
      log.debug("[SSE] Could not initialize delivery sequence for run {}", runId);
      return 0;
    }
  }

  boolean containsTerminalEvent(String runId) {
    try {
      List<String> tail = redis.opsForList().range(REPLAY_PREFIX + runId, -32, -1);
      if (tail == null) return false;
      for (int index = tail.size() - 1; index >= 0; index--) {
        JsonNode entry = mapper.readTree(tail.get(index));
        String json =
            entry.has("seq") && entry.has("payload")
                ? entry.path("payload").asText()
                : tail.get(index);
        if (isTerminalEvent(json)) return true;
      }
    } catch (Exception unavailable) {
      log.debug("[SSE] Could not inspect terminal replay state for run {}", runId);
    }
    return false;
  }

  boolean isTerminalEvent(String json) {
    try {
      return "COMPLETE".equals(mapper.readTree(json).path("type").asText());
    } catch (Exception malformed) {
      return false;
    }
  }

  List<SseEmitterDelivery.ReplayFrame> loadMissedEvents(String runId, String lastEventId) {
    try {
      long lastSequence = Long.parseLong(lastEventId);
      List<String> entries = redis.opsForList().range(REPLAY_PREFIX + runId, 0, -1);
      if (entries == null || entries.isEmpty()) return List.of();
      List<SseEmitterDelivery.ReplayFrame> missed = new ArrayList<>();
      for (int index = 0; index < entries.size(); index++) {
        addMissedEntry(entries.get(index), index, lastSequence, missed, runId);
      }
      if (missed.isEmpty()) return List.of();
      missed.sort(java.util.Comparator.comparingLong(SseEmitterDelivery.ReplayFrame::sequence));
      return List.copyOf(missed);
    } catch (NumberFormatException e) {
      log.debug(
          "[SSE] Invalid Last-Event-ID '{}' for run {} — skipping replay", lastEventId, runId);
    } catch (Exception e) {
      log.debug("[SSE] Replay failed for run {}: {}", runId, e.getMessage());
    }
    return List.of();
  }

  private void addMissedEntry(
      String entry,
      int index,
      long lastSequence,
      List<SseEmitterDelivery.ReplayFrame> missed,
      String runId) {
    try {
      JsonNode node = mapper.readTree(entry);
      if (node.has("seq") && node.has("payload")) {
        long sequence = node.path("seq").asLong(-1);
        if (sequence > lastSequence) {
          missed.add(new SseEmitterDelivery.ReplayFrame(node.path("payload").asText(), sequence));
        }
      } else if (index > lastSequence) {
        missed.add(new SseEmitterDelivery.ReplayFrame(entry, index));
      }
    } catch (Exception ignored) {
      log.debug("[SSE] Skipping malformed replay entry for run {}", runId);
    }
  }

  SseBroadcaster.ReplayLog getReplayLog(String runId) {
    try {
      String key = REPLAY_PREFIX + runId;
      List<String> raw = redis.opsForList().range(key, 0, -1);
      Long ttlMs = redis.getExpire(key, TimeUnit.MILLISECONDS);
      if (raw == null || raw.isEmpty()) {
        return new SseBroadcaster.ReplayLog(
            List.of(), false, "EXPIRED_OR_EMPTY", null, null, false);
      }
      List<Map<String, Object>> events = new ArrayList<>(raw.size());
      long firstSequence = 0;
      long lastSequence = 0;
      for (String entry : raw) {
        try {
          JsonNode node = mapper.readTree(entry);
          String json =
              node.has("seq") && node.has("payload") ? node.path("payload").asText() : entry;
          Map<String, Object> event = mapper.readValue(json, new TypeReference<>() {});
          if (node.has("seq")) {
            long sequence = node.path("seq").asLong(0);
            if (firstSequence == 0) firstSequence = sequence;
            lastSequence = sequence;
            event.put("_eventId", String.valueOf(sequence));
          }
          events.add(event);
        } catch (Exception ignored) {
          // Skip one malformed entry without hiding the remaining replay evidence.
        }
      }
      Instant retainedUntil = ttlMs != null && ttlMs > 0 ? Instant.now().plusMillis(ttlMs) : null;
      return new SseBroadcaster.ReplayLog(
          events,
          true,
          "AVAILABLE",
          retainedUntil,
          lastSequence > 0 ? String.valueOf(lastSequence) : null,
          firstSequence > 1);
    } catch (Exception e) {
      log.debug("[SSE] replay-log read failed for run {}: {}", runId, e.getMessage());
      return new SseBroadcaster.ReplayLog(List.of(), false, "STORE_UNAVAILABLE", null, null, false);
    }
  }
}

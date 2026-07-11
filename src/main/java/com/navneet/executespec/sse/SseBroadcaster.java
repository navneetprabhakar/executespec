package com.navneet.executespec.sse;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.annotation.PostConstruct;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * SSE broadcaster with Redis pub/sub for horizontal scalability.
 *
 * <h3>Local delivery</h3>
 *
 * Emitters are in-memory and instance-local. The bounds from Issue #15 apply: max {@link
 * #MAX_EMITTERS_PER_RUN} per run, {@link #MAX_TOTAL_EMITTERS} globally.
 *
 * <h3>Cross-instance delivery</h3>
 *
 * On every {@link #emit}, events are published to Redis channel {@code sse:{runId}}. Every instance
 * subscribes to {@code sse:*} and delivers incoming messages to its locally-connected emitters. A
 * self-origin marker ({@code "from":instanceId}) is embedded in each Redis message to avoid echoing
 * back to the publishing instance.
 *
 * <h3>Event replay on reconnect</h3>
 *
 * Events are appended to a Redis List {@code sse:replay:{runId}} (capped at {@link
 * #MAX_REPLAY_EVENTS}, 24h TTL). Clients reconnecting with {@code Last-Event-ID: N} receive all
 * events from sequence N+1 onward. Sequence numbers are independent of the bounded list index, so
 * trimming old entries never reuses an SSE id.
 */
@Component
@Slf4j
public class SseBroadcaster {

  /** Maximum SSE connections per run. */
  static final int MAX_EMITTERS_PER_RUN = 15;

  /** Global cap on live SSE emitters across all runs. */
  static final int MAX_TOTAL_EMITTERS = 500;

  /** Maximum events to retain per run for replay. */
  static final int MAX_REPLAY_EVENTS = 500;

  private static final String CHANNEL_PREFIX = "sse:";
  private static final String REPLAY_PREFIX = "sse:replay:";
  private static final String SEQUENCE_PREFIX = "sse:sequence:";
  private static final Duration REPLAY_TTL = Duration.ofHours(24);

  /** Unique ID for this JVM instance — used to suppress echo from Redis pub/sub. */
  private static final String INSTANCE_ID =
      java.net.InetAddress.getLoopbackAddress().getHostName() + "-" + ProcessHandle.current().pid();

  private final Map<String, CopyOnWriteArrayList<SseEmitter>> emitters = new ConcurrentHashMap<>();
  private final AtomicInteger totalEmitterCount = new AtomicInteger(0);

  private final ObjectMapper mapper;
  private final ApplicationEventPublisher eventPublisher;
  private final MeterRegistry meterRegistry;
  private final StringRedisTemplate redis;
  private final RedisMessageListenerContainer listenerContainer;

  public SseBroadcaster(
      ObjectMapper mapper,
      ApplicationEventPublisher eventPublisher,
      MeterRegistry meterRegistry,
      StringRedisTemplate redis,
      RedisMessageListenerContainer listenerContainer) {
    this.mapper = mapper;
    this.eventPublisher = eventPublisher;
    this.meterRegistry = meterRegistry;
    this.redis = redis;
    this.listenerContainer = listenerContainer;
  }

  @PostConstruct
  void init() {
    // Gauge for live emitter count
    Gauge.builder("executespec.sse.emitters.active", totalEmitterCount, AtomicInteger::get)
        .description("Total live SSE emitter connections across all runs")
        .register(meterRegistry);

    // Subscribe to all sse:* channels (pattern subscription)
    listenerContainer.addMessageListener(
        new SseRedisListener(), new PatternTopic(CHANNEL_PREFIX + "*"));
    log.info("[SseBroadcaster] Subscribed to Redis pattern sse:* (instanceId={})", INSTANCE_ID);
  }

  /**
   * Create a new SSE emitter for a run.
   *
   * @param runId the pipeline run to subscribe to
   * @param lastEventId optional Last-Event-ID for replay (may be null)
   * @return the SSE emitter (Spring will stream it to the client)
   */
  public SseEmitter subscribe(String runId, String lastEventId) {
    // Global limit
    if (totalEmitterCount.get() >= MAX_TOTAL_EMITTERS) {
      Counter.builder("executespec.sse.emitters.rejected")
          .tag("reason", "global_limit")
          .register(meterRegistry)
          .increment();
      log.warn(
          "SSE global limit ({}) reached — rejecting subscription for run {}",
          MAX_TOTAL_EMITTERS,
          runId);
      throw new ResponseStatusException(
          HttpStatus.TOO_MANY_REQUESTS, "SSE connection limit reached. Try again later.");
    }

    // Per-run limit — prune stale/dead emitters first to reclaim slots before checking
    List<SseEmitter> runEmitters =
        emitters.computeIfAbsent(runId, id -> new CopyOnWriteArrayList<>());
    if (runEmitters.size() >= MAX_EMITTERS_PER_RUN) {
      // Attempt to flush dead connections by sending a keep-alive; removes those that throw
      List<SseEmitter> dead = new ArrayList<>();
      for (SseEmitter e : runEmitters) {
        try {
          e.send(SseEmitter.event().comment("ping"));
        } catch (Throwable t) {
          dead.add(e);
          completeQuietly(e);
        }
      }
      if (!dead.isEmpty()) {
        runEmitters.removeAll(dead);
        totalEmitterCount.addAndGet(-dead.size());
        log.debug("SSE pruned {} dead emitter(s) for run {}", dead.size(), runId);
      }
    }
    if (runEmitters.size() >= MAX_EMITTERS_PER_RUN) {
      Counter.builder("executespec.sse.emitters.rejected")
          .tag("reason", "per_run_limit")
          .register(meterRegistry)
          .increment();
      log.warn(
          "SSE per-run limit ({}) reached for run {} — rejecting", MAX_EMITTERS_PER_RUN, runId);
      throw new ResponseStatusException(
          HttpStatus.TOO_MANY_REQUESTS,
          "Too many SSE connections for this run (max " + MAX_EMITTERS_PER_RUN + ").");
    }

    SseEmitter emitter = new SseEmitter(0L);
    runEmitters.add(emitter);
    totalEmitterCount.incrementAndGet();

    emitter.onCompletion(() -> remove(runId, emitter));
    emitter.onTimeout(() -> remove(runId, emitter));
    emitter.onError(e -> remove(runId, emitter));

    // Replay missed events if Last-Event-ID was provided
    if (lastEventId != null && !lastEventId.isBlank()) {
      replayMissedEvents(runId, lastEventId, emitter);
    }

    return emitter;
  }

  /** Convenience overload for callers that don't support reconnect replay. */
  public SseEmitter subscribe(String runId) {
    return subscribe(runId, null);
  }

  /**
   * Emit an event to all subscribers of the given run.
   *
   * <p>Delivers to local emitters, persists to Redis replay log, and publishes to Redis pub/sub for
   * cross-instance delivery. Also publishes as a Spring event for local listeners (Slack, etc.).
   */
  public void emit(String runId, RunEvent event) {
    String json = serialize(event);
    if (json == null) return;

    // Persist to replay log (before local delivery so reconnecting clients see it)
    long seqNum = appendToReplayLog(runId, json);

    // Deliver to local emitters with SSE id = sequence number
    deliverLocal(runId, json, seqNum);

    // Publish to Redis pub/sub for other instances
    publishToRedis(runId, json, seqNum);

    // Spring event for local listeners (Slack notifications, etc.)
    try {
      eventPublisher.publishEvent(event);
    } catch (Exception e) {
      log.debug("Failed to publish RunEvent for external listeners: {}", e.getMessage());
    }
  }

  /**
   * Emit an SSE event WITHOUT republishing as a Spring event. Used by RunDetailSseService to avoid
   * infinite loop (it listens for RunEvents).
   */
  public void emitDirect(String runId, RunEvent event) {
    String json = serialize(event);
    if (json == null) return;
    long seqNum = appendToReplayLog(runId, json);
    deliverLocal(runId, json, seqNum);
    publishToRedis(runId, json, seqNum);
  }

  /** Complete the SSE stream for a run (run finished or validation complete). */
  public void complete(String runId) {
    List<SseEmitter> clients = emitters.remove(runId);
    if (clients == null) return;
    totalEmitterCount.addAndGet(-clients.size());
    for (SseEmitter emitter : clients) {
      try {
        emitter.complete();
      } catch (Throwable t) {
        log.debug("SSE complete failed for run {} ({})", runId, t.getMessage());
      }
    }
  }

  private void deliverLocal(String runId, String json, long seqNum) {
    List<SseEmitter> clients = emitters.getOrDefault(runId, new CopyOnWriteArrayList<>());
    if (clients.isEmpty()) return;
    List<SseEmitter> dead = new ArrayList<>();
    for (SseEmitter emitter : clients) {
      try {
        emitter.send(SseEmitter.event().id(String.valueOf(seqNum)).data(json));
      } catch (Throwable t) {
        dead.add(emitter);
        // Once send() fails, Tomcat/Spring has already marked the async response unusable.
        // Calling complete() here can re-dispatch a second text/event-stream error through the
        // global exception handler; removing the emitter is enough.
        log.debug("SSE client disconnected for run {} ({})", runId, t.getMessage());
      }
    }
    if (!dead.isEmpty()) {
      clients.removeAll(dead);
      totalEmitterCount.addAndGet(-dead.size());
    }
  }

  /** Appends JSON to the Redis replay list with a monotonic per-run sequence. */
  private long appendToReplayLog(String runId, String json) {
    String key = REPLAY_PREFIX + runId;
    String sequenceKey = SEQUENCE_PREFIX + runId;
    try {
      Long sequence = redis.opsForValue().increment(sequenceKey);
      if (sequence == null) return 0;
      appendReplayEntry(runId, json, sequence);
      return sequence;
    } catch (Exception e) {
      log.debug("[SSE] Redis replay log write failed for run {}: {}", runId, e.getMessage());
      return 0;
    }
  }

  /** Store an event with an origin sequence, used when mirroring Redis pub/sub to this JVM. */
  private void appendReplayEntry(String runId, String json, long sequence) {
    if (sequence <= 0) {
      appendToReplayLog(runId, json);
      return;
    }
    String key = REPLAY_PREFIX + runId;
    try {
      redis.expire(SEQUENCE_PREFIX + runId, REPLAY_TTL);
      String entry = mapper.writeValueAsString(Map.of("seq", sequence, "payload", json));
      redis.opsForList().rightPush(key, entry);
      redis.opsForList().trim(key, -MAX_REPLAY_EVENTS, -1);
      redis.expire(key, REPLAY_TTL);
    } catch (Exception e) {
      log.debug("[SSE] Redis replay mirror failed for run {}: {}", runId, e.getMessage());
    }
  }

  /**
   * RL-2026-05-046 — returns the persisted replay log for a run as parsed event objects, so the
   * run-detail "Logs" tab can render the live log even after the run finished (within the 24h
   * replay TTL). Reuses the same Redis list the reconnect-replay uses — no new persistence. Each
   * entry already carries {@code type} + {@code timestamp} + payload fields (see {@link RunEvent}).
   */
  public List<java.util.Map<String, Object>> getReplayLog(String runId) {
    try {
      List<String> raw = redis.opsForList().range(REPLAY_PREFIX + runId, 0, -1);
      if (raw == null || raw.isEmpty()) return java.util.List.of();
      List<java.util.Map<String, Object>> out = new java.util.ArrayList<>(raw.size());
      for (String entry : raw) {
        try {
          com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(entry);
          // New entries carry a sequence wrapper. Return the original RunEvent shape to keep the
          // run-detail Logs API backward compatible; raw entries are legacy replay rows.
          String json = node.has("payload") ? node.path("payload").asText() : entry;
          out.add(mapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<>() {}));
        } catch (Exception ignore) {
          // skip a single malformed entry; keep the rest of the log
        }
      }
      return out;
    } catch (Exception e) {
      log.debug("[SSE] replay-log read failed for run {}: {}", runId, e.getMessage());
      return java.util.List.of();
    }
  }

  private void publishToRedis(String runId, String json, long sequence) {
    try {
      // Wrap with instance marker to suppress echo on receive
      String msg =
          "{\"from\":\"" + INSTANCE_ID + "\",\"seq\":" + sequence + ",\"data\":" + json + "}";
      redis.convertAndSend(CHANNEL_PREFIX + runId, msg);
    } catch (Exception e) {
      log.debug("[SSE] Redis publish failed for run {}: {}", runId, e.getMessage());
    }
  }

  private void replayMissedEvents(String runId, String lastEventId, SseEmitter emitter) {
    try {
      long lastSequence = Long.parseLong(lastEventId);
      String key = REPLAY_PREFIX + runId;
      List<String> entries = redis.opsForList().range(key, 0, -1);
      if (entries == null || entries.isEmpty()) return;
      List<ReplayEntry> missed = new ArrayList<>();
      for (int i = 0; i < entries.size(); i++) {
        String entry = entries.get(i);
        try {
          com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(entry);
          if (node.has("seq") && node.has("payload")) {
            long sequence = node.path("seq").asLong(-1);
            if (sequence > lastSequence) {
              missed.add(new ReplayEntry(sequence, node.path("payload").asText()));
            }
          } else if (i > lastSequence) {
            // Compatibility with rows written before monotonic sequence IDs were introduced.
            missed.add(new ReplayEntry(i, entry));
          }
        } catch (Exception ignored) {
          log.debug("[SSE] Skipping malformed replay entry for run {}", runId);
        }
      }
      if (missed.isEmpty()) return;
      log.debug(
          "[SSE] Replaying {} missed events for run {} from seq {}",
          missed.size(),
          runId,
          lastSequence + 1);
      for (ReplayEntry replay : missed) {
        try {
          emitter.send(
              SseEmitter.event().id(String.valueOf(replay.sequence())).data(replay.payload()));
        } catch (Exception e) {
          log.debug("[SSE] Replay send failed for run {}: {}", runId, e.getMessage());
          break;
        }
      }
    } catch (NumberFormatException e) {
      log.debug(
          "[SSE] Invalid Last-Event-ID '{}' for run {} — skipping replay", lastEventId, runId);
    } catch (Exception e) {
      log.debug("[SSE] Replay failed for run {}: {}", runId, e.getMessage());
    }
  }

  private record ReplayEntry(long sequence, String payload) {}

  private String serialize(RunEvent event) {
    try {
      return mapper.writeValueAsString(event);
    } catch (JsonProcessingException e) {
      log.warn("SSE serialize failed: {}", e.getMessage());
      return null;
    }
  }

  private void remove(String runId, SseEmitter emitter) {
    List<SseEmitter> clients = emitters.get(runId);
    if (clients != null && clients.remove(emitter)) {
      totalEmitterCount.decrementAndGet();
    }
  }

  private class SseRedisListener implements MessageListener {
    @Override
    public void onMessage(Message message, byte[] pattern) {
      try {
        String raw = new String(message.getBody());
        String channel = new String(message.getChannel());
        String runId = channel.substring(CHANNEL_PREFIX.length());

        // Parse envelope: {"from":"...", "data":{...}}
        com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(raw);
        String from = node.path("from").asText();

        // Suppress echo — this instance already delivered locally on emit()
        if (INSTANCE_ID.equals(from)) return;

        long sequence = node.path("seq").asLong(0);
        String json = mapper.writeValueAsString(node.get("data"));

        // Check if this instance has any local clients for this run
        List<SseEmitter> clients = emitters.get(runId);
        if (clients == null || clients.isEmpty()) return;

        List<SseEmitter> dead = new ArrayList<>();
        for (SseEmitter emitter : clients) {
          try {
            SseEmitter.SseEventBuilder builder = SseEmitter.event().data(json);
            if (sequence > 0) builder.id(String.valueOf(sequence));
            emitter.send(builder);
          } catch (Throwable t) {
            dead.add(emitter);
          }
        }
        if (!dead.isEmpty()) {
          clients.removeAll(dead);
          totalEmitterCount.addAndGet(-dead.size());
        }
      } catch (Exception e) {
        log.debug("[SSE] Redis listener error: {}", e.getMessage());
      }
    }
  }

  private static void completeQuietly(SseEmitter emitter) {
    try {
      emitter.complete();
    } catch (Throwable ignored) {
      // client is already gone
    }
  }
}

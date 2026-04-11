package com.navneet.executespec.sse;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * SSE broadcaster with Redis pub/sub for horizontal scalability.
 *
 * <h3>Local delivery</h3>
 * Emitters are in-memory and instance-local. The bounds from Issue #15 apply:
 * max {@link #MAX_EMITTERS_PER_RUN} per run, {@link #MAX_TOTAL_EMITTERS} globally.
 *
 * <h3>Cross-instance delivery</h3>
 * On every {@link #emit}, events are published to Redis channel {@code sse:{runId}}.
 * Every instance subscribes to {@code sse:*} and delivers incoming messages to its
 * locally-connected emitters. A self-origin marker ({@code "from":instanceId}) is
 * embedded in each Redis message to avoid echoing back to the publishing instance.
 *
 * <h3>Event replay on reconnect</h3>
 * Events are appended to a Redis List {@code sse:replay:{runId}} (capped at
 * {@link #MAX_REPLAY_EVENTS}, 24h TTL). Clients reconnecting with
 * {@code Last-Event-ID: N} receive all events from sequence N+1 onward. The SSE
 * {@code id} field carries the list index (0-based) as the sequence number.
 */
@Component
@Slf4j
public class SseBroadcaster {

    /** Maximum SSE connections per run. */
    static final int MAX_EMITTERS_PER_RUN = 15;
    /** Global cap on live SSE emitters across all runs. */
    static final int MAX_TOTAL_EMITTERS   = 500;
    /** Maximum events to retain per run for replay. */
    static final int MAX_REPLAY_EVENTS    = 500;

    private static final String CHANNEL_PREFIX = "sse:";
    private static final String REPLAY_PREFIX  = "sse:replay:";
    private static final Duration REPLAY_TTL   = Duration.ofHours(24);

    /** Unique ID for this JVM instance — used to suppress echo from Redis pub/sub. */
    private static final String INSTANCE_ID =
            java.net.InetAddress.getLoopbackAddress().getHostName()
            + "-" + ProcessHandle.current().pid();

    private final Map<String, CopyOnWriteArrayList<SseEmitter>> emitters = new ConcurrentHashMap<>();
    private final AtomicInteger totalEmitterCount = new AtomicInteger(0);

    private final ObjectMapper mapper;
    private final ApplicationEventPublisher eventPublisher;
    private final MeterRegistry meterRegistry;
    private final StringRedisTemplate redis;
    private final RedisMessageListenerContainer listenerContainer;

    public SseBroadcaster(ObjectMapper mapper,
                           ApplicationEventPublisher eventPublisher,
                           MeterRegistry meterRegistry,
                           StringRedisTemplate redis,
                           RedisMessageListenerContainer listenerContainer) {
        this.mapper            = mapper;
        this.eventPublisher    = eventPublisher;
        this.meterRegistry     = meterRegistry;
        this.redis             = redis;
        this.listenerContainer = listenerContainer;
    }

    @PostConstruct
    void init() {
        // Gauge for live emitter count
        Gauge.builder("executespec.sse.emitters.active", totalEmitterCount, AtomicInteger::get)
                .description("Total live SSE emitter connections across all runs")
                .register(meterRegistry);

        // Subscribe to all sse:* channels (pattern subscription)
        listenerContainer.addMessageListener(new SseRedisListener(), new PatternTopic(CHANNEL_PREFIX + "*"));
        log.info("[SseBroadcaster] Subscribed to Redis pattern sse:* (instanceId={})", INSTANCE_ID);
    }

    /**
     * Create a new SSE emitter for a run.
     *
     * @param runId       the pipeline run to subscribe to
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
            log.warn("SSE global limit ({}) reached — rejecting subscription for run {}", MAX_TOTAL_EMITTERS, runId);
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "SSE connection limit reached. Try again later.");
        }

        // Per-run limit — prune stale/dead emitters first to reclaim slots before checking
        List<SseEmitter> runEmitters = emitters.computeIfAbsent(runId, id -> new CopyOnWriteArrayList<>());
        if (runEmitters.size() >= MAX_EMITTERS_PER_RUN) {
            // Attempt to flush dead connections by sending a keep-alive; removes those that throw
            List<SseEmitter> dead = new ArrayList<>();
            for (SseEmitter e : runEmitters) {
                try {
                    e.send(SseEmitter.event().comment("ping"));
                } catch (Throwable t) {
                    dead.add(e);
                    try { e.completeWithError(t); } catch (Throwable ignored) {}
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
            log.warn("SSE per-run limit ({}) reached for run {} — rejecting", MAX_EMITTERS_PER_RUN, runId);
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
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
     * <p>Delivers to local emitters, persists to Redis replay log,
     * and publishes to Redis pub/sub for cross-instance delivery.
     * Also publishes as a Spring event for local listeners (Slack, etc.).
     */
    public void emit(String runId, RunEvent event) {
        String json = serialize(event);
        if (json == null) return;

        // Persist to replay log (before local delivery so reconnecting clients see it)
        long seqNum = appendToReplayLog(runId, json);

        // Deliver to local emitters with SSE id = sequence number
        deliverLocal(runId, json, seqNum);

        // Publish to Redis pub/sub for other instances
        publishToRedis(runId, json);

        // Spring event for local listeners (Slack notifications, etc.)
        try {
            eventPublisher.publishEvent(event);
        } catch (Exception e) {
            log.debug("Failed to publish RunEvent for external listeners: {}", e.getMessage());
        }
    }

    /**
     * Emit an SSE event WITHOUT republishing as a Spring event.
     * Used by RunDetailSseService to avoid infinite loop (it listens for RunEvents).
     */
    public void emitDirect(String runId, RunEvent event) {
        String json = serialize(event);
        if (json == null) return;
        long seqNum = appendToReplayLog(runId, json);
        deliverLocal(runId, json, seqNum);
        publishToRedis(runId, json);
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

    // ── Private helpers ───────────────────────────────────────────────────────

    private void deliverLocal(String runId, String json, long seqNum) {
        List<SseEmitter> clients = emitters.getOrDefault(runId, new CopyOnWriteArrayList<>());
        if (clients.isEmpty()) return;
        List<SseEmitter> dead = new ArrayList<>();
        for (SseEmitter emitter : clients) {
            try {
                emitter.send(SseEmitter.event().id(String.valueOf(seqNum)).data(json));
            } catch (Throwable t) {
                dead.add(emitter);
                try { emitter.completeWithError(t); } catch (Throwable ignored) {}
                log.debug("SSE client disconnected for run {} ({})", runId, t.getMessage());
            }
        }
        if (!dead.isEmpty()) {
            clients.removeAll(dead);
            totalEmitterCount.addAndGet(-dead.size());
        }
    }

    /** Appends JSON to the Redis replay list, trims to MAX_REPLAY_EVENTS, returns 0-based index. */
    private long appendToReplayLog(String runId, String json) {
        String key = REPLAY_PREFIX + runId;
        try {
            Long size = redis.opsForList().rightPush(key, json);
            redis.opsForList().trim(key, -MAX_REPLAY_EVENTS, -1);
            redis.expire(key, REPLAY_TTL);
            return size != null ? size - 1 : 0; // 0-based index
        } catch (Exception e) {
            log.debug("[SSE] Redis replay log write failed for run {}: {}", runId, e.getMessage());
            return 0;
        }
    }

    private void publishToRedis(String runId, String json) {
        try {
            // Wrap with instance marker to suppress echo on receive
            String msg = "{\"from\":\"" + INSTANCE_ID + "\",\"data\":" + json + "}";
            redis.convertAndSend(CHANNEL_PREFIX + runId, msg);
        } catch (Exception e) {
            log.debug("[SSE] Redis publish failed for run {}: {}", runId, e.getMessage());
        }
    }

    private void replayMissedEvents(String runId, String lastEventId, SseEmitter emitter) {
        try {
            long fromIndex = Long.parseLong(lastEventId) + 1;
            String key = REPLAY_PREFIX + runId;
            List<String> missed = redis.opsForList().range(key, fromIndex, -1);
            if (missed == null || missed.isEmpty()) return;
            log.debug("[SSE] Replaying {} missed events for run {} from seq {}", missed.size(), runId, fromIndex);
            for (int i = 0; i < missed.size(); i++) {
                long seqNum = fromIndex + i;
                try {
                    emitter.send(SseEmitter.event().id(String.valueOf(seqNum)).data(missed.get(i)));
                } catch (Exception e) {
                    log.debug("[SSE] Replay send failed for run {}: {}", runId, e.getMessage());
                    break;
                }
            }
        } catch (NumberFormatException e) {
            log.debug("[SSE] Invalid Last-Event-ID '{}' for run {} — skipping replay", lastEventId, runId);
        } catch (Exception e) {
            log.debug("[SSE] Replay failed for run {}: {}", runId, e.getMessage());
        }
    }

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

    // ── Redis pub/sub listener ────────────────────────────────────────────────

    private class SseRedisListener implements MessageListener {
        @Override
        public void onMessage(Message message, byte[] pattern) {
            try {
                String raw     = new String(message.getBody());
                String channel = new String(message.getChannel());
                String runId   = channel.substring(CHANNEL_PREFIX.length());

                // Parse envelope: {"from":"...", "data":{...}}
                com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(raw);
                String from = node.path("from").asText();

                // Suppress echo — this instance already delivered locally on emit()
                if (INSTANCE_ID.equals(from)) return;

                // Check if this instance has any local clients for this run
                List<SseEmitter> clients = emitters.get(runId);
                if (clients == null || clients.isEmpty()) return;

                String json = mapper.writeValueAsString(node.get("data"));
                // Deliver without setting an id (cross-instance delivery, id already set on origin)
                List<SseEmitter> dead = new ArrayList<>();
                for (SseEmitter emitter : clients) {
                    try {
                        emitter.send(SseEmitter.event().data(json));
                    } catch (Throwable t) {
                        dead.add(emitter);
                        try { emitter.completeWithError(t); } catch (Throwable ignored) {}
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
}

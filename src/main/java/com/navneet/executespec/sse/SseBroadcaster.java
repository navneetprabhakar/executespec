package com.navneet.executespec.sse;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

@Component
@Slf4j
@RequiredArgsConstructor
public class SseBroadcaster {

    /** Maximum SSE connections per run. Prevents a single run from exhausting heap. */
    static final int MAX_EMITTERS_PER_RUN = 5;

    /** Global cap on live SSE emitters across all runs. */
    static final int MAX_TOTAL_EMITTERS = 500;

    private final Map<String, CopyOnWriteArrayList<SseEmitter>> emitters = new ConcurrentHashMap<>();
    private final AtomicInteger totalEmitterCount = new AtomicInteger(0);
    private final ObjectMapper mapper;
    private final ApplicationEventPublisher eventPublisher;
    private final MeterRegistry meterRegistry;

    /** Registers Micrometer gauges and counters once the bean is created. */
    @jakarta.annotation.PostConstruct
    void initMetrics() {
        Gauge.builder("executespec.sse.emitters.active", totalEmitterCount, AtomicInteger::get)
                .description("Total live SSE emitter connections across all runs")
                .register(meterRegistry);
    }

    public SseEmitter subscribe(String runId) {
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

        // Per-run limit
        List<SseEmitter> runEmitters = emitters.computeIfAbsent(runId, id -> new CopyOnWriteArrayList<>());
        if (runEmitters.size() >= MAX_EMITTERS_PER_RUN) {
            Counter.builder("executespec.sse.emitters.rejected")
                    .tag("reason", "per_run_limit")
                    .register(meterRegistry)
                    .increment();
            log.warn("SSE per-run limit ({}) reached for run {} — rejecting subscription", MAX_EMITTERS_PER_RUN, runId);
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Too many SSE connections for this run (max " + MAX_EMITTERS_PER_RUN + ").");
        }

        SseEmitter emitter = new SseEmitter(0L);  // no timeout
        runEmitters.add(emitter);
        totalEmitterCount.incrementAndGet();

        emitter.onCompletion(() -> remove(runId, emitter));
        emitter.onTimeout(() -> remove(runId, emitter));
        emitter.onError(e -> remove(runId, emitter));
        return emitter;
    }

    public void emit(String runId, RunEvent event) {
        List<SseEmitter> clients = emitters.getOrDefault(runId, new CopyOnWriteArrayList<>());
        List<SseEmitter> dead = new ArrayList<>();
        String json;
        try {
            json = mapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            log.warn("SSE serialize failed for run {}: {}", runId, e.getMessage());
            return;
        }
        for (SseEmitter emitter : clients) {
            try {
                emitter.send(SseEmitter.event().data(json));
            } catch (Throwable t) {
                // Broken pipe / client disconnect — complete the emitter so Spring's
                // async lifecycle is properly terminated (prevents Tomcat ERROR logs).
                dead.add(emitter);
                try {
                    emitter.completeWithError(t);
                } catch (Throwable ignored) {
                    // Already broken — nothing more to do
                }
                log.debug("SSE client disconnected for run {} ({})", runId, t.getMessage());
            }
        }
        if (!dead.isEmpty()) {
            clients.removeAll(dead);
            totalEmitterCount.addAndGet(-dead.size());
        }

        // Publish RunEvent as a Spring event for external listeners (Slack, etc.)
        try {
            eventPublisher.publishEvent(event);
        } catch (Exception e) {
            log.debug("Failed to publish RunEvent for external listeners: {}", e.getMessage());
        }
    }

    /**
     * Emit an SSE event directly to clients WITHOUT republishing as a Spring event.
     * Used by RunDetailSseService to avoid infinite loop (it listens for RunEvents).
     */
    public void emitDirect(String runId, RunEvent event) {
        List<SseEmitter> clients = emitters.getOrDefault(runId, new CopyOnWriteArrayList<>());
        if (clients.isEmpty()) return;
        List<SseEmitter> dead = new ArrayList<>();
        String json;
        try {
            json = mapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            log.warn("SSE serialize failed for RUN_DETAIL on run {}: {}", runId, e.getMessage());
            return;
        }
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
    }

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

    private void remove(String runId, SseEmitter emitter) {
        List<SseEmitter> clients = emitters.get(runId);
        if (clients != null && clients.remove(emitter)) {
            totalEmitterCount.decrementAndGet();
        }
    }
}

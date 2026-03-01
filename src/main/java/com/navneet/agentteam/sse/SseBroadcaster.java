package com.navneet.agentteam.sse;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
@Slf4j
@RequiredArgsConstructor
public class SseBroadcaster {

    private final Map<String, CopyOnWriteArrayList<SseEmitter>> emitters = new ConcurrentHashMap<>();
    private final ObjectMapper mapper;

    public SseEmitter subscribe(String runId) {
        SseEmitter emitter = new SseEmitter(0L);  // no timeout
        emitters.computeIfAbsent(runId, id -> new CopyOnWriteArrayList<>()).add(emitter);
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
                // Broken pipe / client disconnect — prune dead emitter silently
                log.debug("SSE client disconnected for run {} ({})", runId, t.getMessage());
                dead.add(emitter);
            }
        }
        if (!dead.isEmpty()) clients.removeAll(dead);
    }

    public void complete(String runId) {
        List<SseEmitter> clients = emitters.remove(runId);
        if (clients == null) return;
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
        if (clients != null) clients.remove(emitter);
    }
}

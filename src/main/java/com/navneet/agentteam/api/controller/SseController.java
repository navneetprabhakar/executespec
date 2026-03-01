package com.navneet.agentteam.api.controller;

import com.navneet.agentteam.sse.SseBroadcaster;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/runs")
@RequiredArgsConstructor
@Slf4j
public class SseController {

    private final SseBroadcaster broadcaster;

    /**
     * Subscribe to live events for a run.
     * The client receives SSE events until the run completes or the connection drops.
     */
    @GetMapping(value = "/{runId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribe(@PathVariable String runId) {
        log.debug("SSE subscription for run {}", runId);
        return broadcaster.subscribe(runId);
    }
}

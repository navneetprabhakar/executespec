package com.navneet.executespec.api.controller;

import com.navneet.executespec.api.dto.MetricsResponse;
import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.state.StateStore.MetricsData;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/metrics")
@RequiredArgsConstructor
public class MetricsController {

    private final StateStore stateStore;

    @GetMapping
    public ResponseEntity<MetricsResponse> metrics() {
        MetricsData d = stateStore.computeMetrics();
        return ResponseEntity.ok(new MetricsResponse(
            d.totalRuns(), d.completedRuns(), d.failedRuns(),
            d.activeRuns(), d.queuedRuns(),
            d.totalInputTokens(), d.totalOutputTokens()
        ));
    }
}

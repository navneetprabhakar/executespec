package com.navneet.agentteam.api.controller;

import com.navneet.agentteam.deployment.LocalPreviewService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST endpoints for local process-based preview of completed pipeline runs.
 * Starts dev servers directly in the workspace (no Docker).
 */
@RestController
@RequestMapping("/api/runs")
@RequiredArgsConstructor
@Slf4j
public class LocalPreviewController {

    private final LocalPreviewService previewService;

    @GetMapping("/{runId}/preview/status")
    public ResponseEntity<PreviewStatusDto> getStatus(@PathVariable String runId) {
        return ResponseEntity.ok(toDto(previewService.getStatus(runId)));
    }

    @PostMapping("/{runId}/preview/start")
    public ResponseEntity<PreviewStatusDto> start(@PathVariable String runId) {
        log.info("POST /api/runs/{}/preview/start", runId);
        previewService.startAsync(runId);
        return ResponseEntity.ok(toDto(previewService.getStatus(runId)));
    }

    @DeleteMapping("/{runId}/preview/stop")
    public ResponseEntity<Void> stop(@PathVariable String runId) {
        log.info("DELETE /api/runs/{}/preview/stop", runId);
        previewService.stop(runId);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/{runId}/preview/logs")
    public ResponseEntity<PreviewLogsDto> getLogs(
            @PathVariable String runId,
            @RequestParam(defaultValue = "100") int lines) {
        return ResponseEntity.ok(new PreviewLogsDto(runId, previewService.getLogs(runId, lines)));
    }

    // ── DTOs ───────────────────────────────────────────────────────────────────

    private PreviewStatusDto toDto(LocalPreviewService.PreviewState s) {
        return new PreviewStatusDto(
            s.runId(),
            s.status().name(),
            s.url(),
            s.projectType(),
            s.errorMessage(),
            s.backendUrl(),
            s.frontendUrl()
        );
    }

    public record PreviewStatusDto(
        String runId,
        String status,
        String url,
        String projectType,
        String errorMessage,
        String backendUrl,
        String frontendUrl
    ) {}

    public record PreviewLogsDto(
        String runId,
        List<String> lines
    ) {}
}

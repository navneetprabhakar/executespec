package com.navneet.agentteam.api.controller;

import com.navneet.agentteam.deployment.LocalPreviewService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST endpoints for local Docker preview of a completed pipeline run.
 *
 * <pre>
 * GET    /api/runs/{id}/preview          → get current preview status
 * POST   /api/runs/{id}/preview/start    → build + start Docker container (async)
 * DELETE /api/runs/{id}/preview/stop     → stop running container
 * </pre>
 */
@RestController
@RequestMapping("/api/runs")
@RequiredArgsConstructor
@Slf4j
public class LocalPreviewController {

    private final LocalPreviewService previewService;

    @GetMapping("/{runId}/preview")
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

    // ── DTO ───────────────────────────────────────────────────────────────────

    private PreviewStatusDto toDto(LocalPreviewService.PreviewState s) {
        return new PreviewStatusDto(
            s.runId(),
            s.status().name(),
            s.url(),
            s.projectType(),
            s.errorMessage()
        );
    }

    public record PreviewStatusDto(
        String runId,
        String status,
        String url,
        String projectType,
        String errorMessage
    ) {}
}

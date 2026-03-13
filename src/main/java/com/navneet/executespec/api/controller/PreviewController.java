package com.navneet.executespec.api.controller;

import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.auth.security.RunAccessGuard;
import com.navneet.executespec.deployment.preview.PreviewException;
import com.navneet.executespec.deployment.preview.PreviewService;
import com.navneet.executespec.deployment.preview.PreviewSession;
import com.navneet.executespec.deployment.preview.PreviewStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;

@RestController
@RequestMapping("/api/runs")
@RequiredArgsConstructor
@Slf4j
public class PreviewController {

    private final PreviewService previewService;
    private final RunAccessGuard guard;

    // ── DTOs (match frontend LocalPreviewStatus / PreviewLogs) ──────────────

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

    // ── Endpoints ───────────────────────────────────────────────────────────

    @GetMapping("/{runId}/preview/status")
    public ResponseEntity<PreviewStatusDto> getStatus(
            @PathVariable String runId,
            @AuthenticationPrincipal AppUserDetails userDetails) {
        guard.requireRunAccess(runId, userDetails);
        return ResponseEntity.ok(toDto(runId, previewService.getStatus(runId)));
    }

    @PostMapping("/{runId}/preview/start")
    public ResponseEntity<PreviewStatusDto> start(
            @PathVariable String runId,
            @AuthenticationPrincipal AppUserDetails userDetails) {
        guard.requireRunAccess(runId, userDetails);
        log.info("POST /api/runs/{}/preview/start", runId);

        try {
            PreviewSession session = previewService.start(runId, userDetails.getUser());
            return ResponseEntity.ok(toDto(runId, session));
        } catch (PreviewException e) {
            log.warn("Preview start failed for run {}: {}", runId, e.getMessage());
            return ResponseEntity.ok(new PreviewStatusDto(
                    runId, "FAILED", null, null, e.getMessage(), null, null
            ));
        }
    }

    @DeleteMapping("/{runId}/preview/stop")
    public ResponseEntity<Void> stop(
            @PathVariable String runId,
            @AuthenticationPrincipal AppUserDetails userDetails) {
        guard.requireRunAccess(runId, userDetails);
        log.info("DELETE /api/runs/{}/preview/stop", runId);
        previewService.stop(runId);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/{runId}/preview/logs")
    public ResponseEntity<PreviewLogsDto> getLogs(
            @PathVariable String runId,
            @RequestParam(defaultValue = "0") int lines,
            @RequestParam(defaultValue = "all") String filter,
            @AuthenticationPrincipal AppUserDetails userDetails) {
        guard.requireRunAccess(runId, userDetails);
        String raw = previewService.getLogs(runId, lines, filter);
        List<String> logLines = raw.isBlank()
                ? List.of()
                : Arrays.asList(raw.split("\n"));
        return ResponseEntity.ok(new PreviewLogsDto(runId, logLines));
    }

    // ── Mapping ─────────────────────────────────────────────────────────────

    private PreviewStatusDto toDto(String runId, PreviewSession session) {
        if (session == null) {
            return new PreviewStatusDto(runId, "IDLE", null, null, null, null, null);
        }

        // Map BUILDING → STARTING for frontend compatibility
        String status = session.status() == PreviewStatus.BUILDING
                ? "STARTING"
                : session.status().name();

        return new PreviewStatusDto(
                session.runId(),
                status,
                session.url(),
                session.projectType() != null ? session.projectType().name() : null,
                session.errorMessage(),
                null, // backendUrl — reserved for future multi-container support
                null  // frontendUrl — reserved for future multi-container support
        );
    }
}

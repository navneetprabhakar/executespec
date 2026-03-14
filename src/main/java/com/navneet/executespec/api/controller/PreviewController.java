package com.navneet.executespec.api.controller;

import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.auth.security.RunAccessGuard;
import com.navneet.executespec.deployment.preview.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/runs")
@RequiredArgsConstructor
@Slf4j
public class PreviewController {

    private final PreviewService previewService;
    private final RunAccessGuard guard;

    // ── DTOs ─────────────────────────────────────────────────────────────────

    public record PreviewStatusDto(
            String runId,
            String status,
            String url,
            String projectType,
            String databaseType,
            String errorMessage,
            String backendUrl,
            String frontendUrl
    ) {}

    public record PreviewLogsDto(
            String runId,
            List<String> lines
    ) {}

    public record PreviewAnalysisDto(
            String databaseType,
            List<DetectedEnvVarDto> requiredEnvVars
    ) {}

    public record DetectedEnvVarDto(
            String name,
            String category,
            String source,
            String defaultValue
    ) {}

    public record PreviewStartRequest(
            Map<String, String> envVars
    ) {}

    // ── Endpoints ────────────────────────────────────────────────────────────

    /**
     * Analyze workspace for database and environment variable requirements.
     * Should be called before starting preview to determine if user input is needed.
     */
    @GetMapping("/{runId}/preview/analyze")
    public ResponseEntity<PreviewAnalysisDto> analyze(
            @PathVariable String runId,
            @AuthenticationPrincipal AppUserDetails userDetails) {
        guard.requireRunAccess(runId, userDetails);
        log.info("GET /api/runs/{}/preview/analyze", runId);

        var analysis = previewService.analyze(runId);
        List<DetectedEnvVarDto> envVarDtos = analysis.requiredEnvVars().stream()
                .map(v -> new DetectedEnvVarDto(v.name(), v.category().name(), v.source(), v.defaultValue()))
                .toList();

        return ResponseEntity.ok(new PreviewAnalysisDto(
                analysis.databaseType() != null ? analysis.databaseType().name() : null,
                envVarDtos
        ));
    }

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
            @RequestBody(required = false) PreviewStartRequest request,
            @AuthenticationPrincipal AppUserDetails userDetails) {
        guard.requireRunAccess(runId, userDetails);
        log.info("POST /api/runs/{}/preview/start", runId);

        try {
            Map<String, String> envVars = (request != null && request.envVars() != null)
                    ? request.envVars() : Map.of();
            PreviewSession session = previewService.start(runId, userDetails.getUser(), envVars);
            return ResponseEntity.ok(toDto(runId, session));
        } catch (PreviewException e) {
            log.warn("Preview start failed for run {}: {}", runId, e.getMessage());
            return ResponseEntity.ok(new PreviewStatusDto(
                    runId, "FAILED", null, null, null, e.getMessage(), null, null
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

    // ── Mapping ──────────────────────────────────────────────────────────────

    private PreviewStatusDto toDto(String runId, PreviewSession session) {
        if (session == null) {
            return new PreviewStatusDto(runId, "IDLE", null, null, null, null, null, null);
        }

        // Map internal statuses for frontend display
        String status = switch (session.status()) {
            case ANALYZING, BUILDING, DB_STARTING -> "STARTING";
            default -> session.status().name();
        };

        return new PreviewStatusDto(
                session.runId(),
                status,
                session.url(),
                session.projectType() != null ? session.projectType().name() : null,
                session.databaseType() != null ? session.databaseType().name() : null,
                session.errorMessage(),
                null,
                null
        );
    }
}

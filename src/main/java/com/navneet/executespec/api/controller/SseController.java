package com.navneet.executespec.api.controller;

import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.auth.security.JwtUtil;
import com.navneet.executespec.auth.security.RunAccessGuard;
import com.navneet.executespec.sse.SseBroadcaster;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

@RestController
@RequestMapping("/api/runs")
@RequiredArgsConstructor
@Slf4j
public class SseController {

    private final SseBroadcaster broadcaster;
    private final RunAccessGuard guard;
    private final JwtUtil jwtUtil;

    /**
     * Generate a short-lived SSE-scoped token for a specific run.
     * This avoids exposing the full access token in SSE query params.
     */
    @GetMapping("/{runId}/sse-token")
    public ResponseEntity<Map<String, String>> getSseToken(
            @PathVariable String runId,
            @AuthenticationPrincipal AppUserDetails userDetails) {
        guard.requireRunAccess(runId, userDetails);
        String sseToken = jwtUtil.generateSseToken(userDetails.getUser().getId(), runId);
        return ResponseEntity.ok(Map.of("token", sseToken));
    }

    /**
     * Subscribe to live events for a run.
     * The client receives SSE events until the run completes or the connection drops.
     * Access is validated via scoped SSE token (preferred) or full JWT with run access check.
     */
    @GetMapping(value = "/{runId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribe(
            @PathVariable String runId,
            @RequestParam(required = false) String token,
            @AuthenticationPrincipal AppUserDetails userDetails) {
        // Prefer scoped SSE token validation
        if (token != null && !token.isBlank()) {
            String userId = jwtUtil.validateSseToken(token, runId);
            if (userId != null) {
                log.debug("SSE subscription for run {} via scoped token (user={})", runId, userId);
                return broadcaster.subscribe(runId);
            }
            // Fall through to full JWT check if SSE token is invalid
        }

        // Fallback: full JWT (backward compat during frontend migration)
        if (userDetails == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Valid SSE token or JWT required");
        }
        guard.requireRunAccess(runId, userDetails);
        log.debug("SSE subscription for run {} via full JWT", runId);
        return broadcaster.subscribe(runId);
    }
}

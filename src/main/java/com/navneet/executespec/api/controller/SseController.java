package com.navneet.executespec.api.controller;

import java.util.Map;

import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.auth.security.JwtUtil;
import com.navneet.executespec.auth.security.RunAccessGuard;
import com.navneet.executespec.sse.SseBroadcaster;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@RestController
@RequestMapping("/api/runs")
@RequiredArgsConstructor
@Slf4j
public class SseController {

  private final SseBroadcaster broadcaster;
  private final RunAccessGuard guard;
  private final JwtUtil jwtUtil;

  /**
   * Generate a short-lived SSE-scoped token for a specific run. This avoids exposing the full
   * access token in SSE query params.
   */
  @GetMapping("/{runId}/sse-token")
  public ResponseEntity<Map<String, String>> getSseToken(
      @PathVariable String runId, @AuthenticationPrincipal AppUserDetails userDetails) {
    guard.requireRunAccess(runId, userDetails);
    String sseToken = jwtUtil.generateSseToken(userDetails.getUser().getId(), runId);
    return ResponseEntity.ok(Map.of("token", sseToken));
  }

  /**
   * Subscribe to live events for a run. The client receives SSE events until the run completes or
   * the connection drops. Access is validated via scoped SSE token (preferred) or full JWT with run
   * access check.
   */
  @GetMapping(value = "/{runId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter subscribe(
      @PathVariable String runId,
      @RequestParam(required = false) String token,
      @RequestParam(name = "lastEventId", required = false) String lastEventIdParam,
      @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
      @AuthenticationPrincipal AppUserDetails userDetails) {
    // Browser EventSource normally sends Last-Event-ID as a header. Custom reconnect clients
    // (including the CLI's token-refresh path) cannot always preserve that header, so accept the
    // equivalent query parameter as a bounded compatibility path. The header remains authoritative
    // when both are present.
    String effectiveLastEventId =
        lastEventId != null && !lastEventId.isBlank() ? lastEventId : lastEventIdParam;
    // Prefer scoped SSE token validation
    if (token != null && !token.isBlank()) {
      String userId = jwtUtil.validateSseToken(token, runId);
      if (userId != null) {
        log.debug(
            "SSE subscription for run {} via scoped token (user={}, lastEventId={})",
            runId,
            userId,
            effectiveLastEventId);
        return broadcaster.subscribe(runId, effectiveLastEventId);
      }
      // Fall through to full JWT check if SSE token is invalid
    }

    // Fallback: full JWT (backward compat during frontend migration)
    if (userDetails == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Valid SSE token or JWT required");
    }
    guard.requireRunAccess(runId, userDetails);
    log.debug(
        "SSE subscription for run {} via full JWT (lastEventId={})", runId, effectiveLastEventId);
    return broadcaster.subscribe(runId, effectiveLastEventId);
  }
}

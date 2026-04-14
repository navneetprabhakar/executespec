package com.navneet.executespec.api.controller;

import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.auth.security.RunAccessGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * Conversation endpoints retired with V1 deprecation (chat, apply-suggestion,
 * iterate). V2 does not currently support per-run conversation — submit a
 * fresh run instead. Kept as 410 Gone stubs so the existing frontend surfaces
 * a clear "not supported" state until the endpoints are removed from the UI.
 */
@RestController
@RequestMapping("/api/runs")
@RequiredArgsConstructor
@Slf4j
public class RunConversationController {

    private final RunAccessGuard guard;

    @PostMapping("/{runId}/chat")
    public ResponseEntity<Map<String, Object>> sendMessage(
            @PathVariable String runId,
            @RequestBody Map<String, String> body,
            @AuthenticationPrincipal AppUserDetails userDetails) {
        guard.requireRunAccess(runId, userDetails);
        throw new ResponseStatusException(HttpStatus.GONE,
                "Chat / copilot not yet supported in V2.");
    }

    @PostMapping("/{runId}/apply")
    public ResponseEntity<Map<String, Object>> applySuggestion(
            @PathVariable String runId,
            @RequestBody Map<String, String> body,
            @AuthenticationPrincipal AppUserDetails userDetails) {
        guard.requireRunAccess(runId, userDetails);
        throw new ResponseStatusException(HttpStatus.GONE,
                "Apply-suggestion not yet supported in V2.");
    }

    @PostMapping("/{runId}/iterate")
    public ResponseEntity<Map<String, String>> iterateRun(
            @PathVariable String runId,
            @RequestBody Map<String, String> body,
            @AuthenticationPrincipal AppUserDetails userDetails) {
        guard.requireRunAccess(runId, userDetails);
        throw new ResponseStatusException(HttpStatus.GONE,
                "Iterate-from-run not yet supported in V2 — submit a fresh run instead.");
    }
}

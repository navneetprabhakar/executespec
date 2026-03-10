package com.navneet.executespec.api.controller;

import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.auth.security.RunAccessGuard;
import com.navneet.executespec.integration.IntegrationManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Always-available integration status endpoint.
 * The main IntegrationController is gated by @ConditionalOnProperty; this controller
 * provides the status endpoint unconditionally so the UI doesn't receive a 500.
 */
@RestController
@RequestMapping("/api/integrations")
public class IntegrationStatusController {

    @Autowired(required = false)
    private IntegrationManager manager;

    @Autowired
    private RunAccessGuard guard;

    /** Returns which integrations are enabled. Returns empty list when integrations are disabled. */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status(
            @AuthenticationPrincipal AppUserDetails userDetails) {
        guard.requireAuthenticated(userDetails);
        List<String> sources = manager != null ? manager.getEnabledSources() : List.of();
        return ResponseEntity.ok(Map.of("enabledSources", sources));
    }
}

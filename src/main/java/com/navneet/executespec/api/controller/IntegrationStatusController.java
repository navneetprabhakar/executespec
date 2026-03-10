package com.navneet.executespec.api.controller;

import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.auth.security.RunAccessGuard;
import com.navneet.executespec.auth.service.OrgService;
import com.navneet.executespec.integration.IntegrationManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Always-available integration status endpoint.
 * The main IntegrationController is gated by @ConditionalOnProperty; this controller
 * provides the status endpoint unconditionally so the UI doesn't receive a 500.
 *
 * <p>Integration status is determined by org-level credentials. If the user's org
 * has configured JIRA/Confluence credentials, those integrations are reported as enabled.
 * For SUPER_ADMIN users without an org, falls back to IntegrationManager (application.yml).
 */
@RestController
@RequestMapping("/api/integrations")
public class IntegrationStatusController {

    @Autowired(required = false)
    private IntegrationManager manager;

    @Autowired
    private RunAccessGuard guard;

    @Autowired
    private OrgService orgService;

    /** Returns which integrations are enabled for the current user's org. */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status(
            @AuthenticationPrincipal AppUserDetails userDetails) {
        guard.requireAuthenticated(userDetails);

        String orgId = userDetails.getUser().getOrgId();

        // For users with an org: check org-level credentials
        if (orgId != null) {
            List<String> orgIntegrations = orgService.getEnabledIntegrations(orgId);
            return ResponseEntity.ok(Map.of("enabledSources", orgIntegrations));
        }

        // For SUPER_ADMIN without an org: fall back to IntegrationManager (application.yml)
        if ("SUPER_ADMIN".equals(userDetails.getUser().getGlobalRole())) {
            List<String> sources = manager != null ? manager.getEnabledSources() : List.of();
            return ResponseEntity.ok(Map.of("enabledSources", sources));
        }

        // No org, not super admin → no integrations
        return ResponseEntity.ok(Map.of("enabledSources", List.of()));
    }
}

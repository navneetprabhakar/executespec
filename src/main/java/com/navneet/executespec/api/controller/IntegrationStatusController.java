package com.navneet.executespec.api.controller;

import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.auth.security.RunAccessGuard;
import com.navneet.executespec.auth.service.OrgService;
import com.navneet.executespec.integration.IntegrationManager;
import com.navneet.executespec.integration.slack.model.OrgSlackIntegration;
import com.navneet.executespec.integration.slack.model.OrgSlackIntegrationRepository;
import com.navneet.executespec.integration.slack.model.ProjectSlackChannelRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;

/**
 * Always-available integration status endpoint.
 * The main IntegrationController and SlackIntegrationController are gated by
 * @ConditionalOnProperty; this controller provides status endpoints unconditionally
 * so the UI doesn't receive a 500 when those features are disabled.
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

    @Autowired(required = false)
    private OrgSlackIntegrationRepository slackIntegrationRepo;

    @Autowired(required = false)
    private ProjectSlackChannelRepository slackChannelRepo;

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

    /**
     * Returns Slack connection status for the current org.
     * This endpoint is always available — when SlackIntegrationController is disabled
     * (agent.integrations.slack.enabled=false), this fallback returns {connected: false}.
     */
    @GetMapping("/slack/status")
    public ResponseEntity<Map<String, Object>> slackStatus(
            @AuthenticationPrincipal AppUserDetails userDetails) {
        guard.requireAuthenticated(userDetails);
        String orgId = userDetails.getUser().getOrgId();

        if (orgId == null || slackIntegrationRepo == null) {
            return ResponseEntity.ok(Map.of("connected", false));
        }

        Optional<OrgSlackIntegration> opt = slackIntegrationRepo.findByOrgId(orgId);
        if (opt.isEmpty()) {
            return ResponseEntity.ok(Map.of("connected", false));
        }

        OrgSlackIntegration integration = opt.get();
        long channelCount = slackChannelRepo != null ? slackChannelRepo.countByOrgId(orgId) : 0;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("connected", true);
        result.put("teamName", integration.getTeamName());
        result.put("teamId", integration.getTeamId());
        result.put("installedBy", integration.getInstalledBy());
        result.put("channelCount", channelCount);
        result.put("installedAt", integration.getInstalledAt().toString());
        return ResponseEntity.ok(result);
    }
}

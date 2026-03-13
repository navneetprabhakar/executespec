package com.navneet.executespec.deployment.preview;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Manages dynamic reverse proxy routes in Caddy via its admin API (localhost:2019).
 * Only active when deployed on a server with Caddy (preview.proxy.enabled=true).
 *
 * Stub — full implementation in Phase 7.
 */
@Component
@Slf4j
@ConditionalOnProperty(name = "preview.proxy.enabled", havingValue = "true")
public class CaddyProxyManager {

    public void addRoute(String runId, int port) {
        log.warn("CaddyProxyManager.addRoute() called but not yet implemented (Phase 7)");
    }

    public void removeRoute(String runId) {
        log.warn("CaddyProxyManager.removeRoute() called but not yet implemented (Phase 7)");
    }
}

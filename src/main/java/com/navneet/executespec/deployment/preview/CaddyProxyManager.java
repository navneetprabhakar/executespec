package com.navneet.executespec.deployment.preview;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages dynamic reverse proxy routes in Caddy via its admin API (localhost:2019).
 * Only active when deployed on a server with Caddy (preview.proxy.enabled=true).
 *
 * Each route maps preview-{runId-short}.executespec.ai → reverse_proxy localhost:{port}
 */
@Component
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(name = "preview.proxy.enabled", havingValue = "true")
public class CaddyProxyManager {

    private static final String CADDY_ADMIN = "http://localhost:2019";
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final PreviewProperties props;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .build();

    /** Tracks route IDs for cleanup. runId → route index/id in Caddy config. */
    private final ConcurrentHashMap<String, String> routeIds = new ConcurrentHashMap<>();

    /**
     * Add a reverse proxy route: {runId-short}.{previewDomain} → localhost:{port}
     */
    public void addRoute(String runId, int port) {
        String shortId = runId.length() > 8 ? runId.substring(0, 8) : runId;
        String host = "preview-" + shortId + "." + props.getPreviewDomain();

        // Caddy JSON config for a route
        String routeJson = """
            {
                "@id": "preview-%s",
                "match": [{"host": ["%s"]}],
                "handle": [{
                    "handler": "reverse_proxy",
                    "upstreams": [{"dial": "localhost:%d"}]
                }],
                "terminal": true
            }
            """.formatted(runId, host, port);

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(CADDY_ADMIN + "/config/apps/http/servers/preview/routes"))
                    .POST(HttpRequest.BodyPublishers.ofString(routeJson))
                    .header("Content-Type", "application/json")
                    .timeout(TIMEOUT)
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                routeIds.put(runId, "preview-" + runId);
                log.info("Caddy route added: {} → localhost:{}", host, port);
            } else {
                log.warn("Caddy addRoute failed ({}): {}", response.statusCode(), response.body());
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("Failed to add Caddy route for {}: {}", runId, e.getMessage());
        }
    }

    /**
     * Remove the reverse proxy route for a run.
     */
    public void removeRoute(String runId) {
        String routeId = routeIds.remove(runId);
        if (routeId == null) {
            log.debug("No Caddy route to remove for run {}", runId);
            return;
        }

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(CADDY_ADMIN + "/id/" + routeId))
                    .DELETE()
                    .timeout(TIMEOUT)
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                log.info("Caddy route removed for run {}", runId);
            } else {
                log.warn("Caddy removeRoute failed ({}): {}", response.statusCode(), response.body());
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("Failed to remove Caddy route for {}: {}", runId, e.getMessage());
        }
    }

    /**
     * Build the preview URL for a given run.
     */
    public String buildUrl(String runId) {
        String shortId = runId.length() > 8 ? runId.substring(0, 8) : runId;
        return "https://preview-" + shortId + "." + props.getPreviewDomain();
    }
}

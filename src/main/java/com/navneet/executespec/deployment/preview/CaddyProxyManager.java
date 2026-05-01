package com.navneet.executespec.deployment.preview;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.annotation.PostConstruct;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Manages dynamic reverse proxy routes in Caddy via its admin API (localhost:2019). Only active
 * when deployed on a server with Caddy (preview.proxy.enabled=true).
 *
 * <p>Caddy JSON config has a single server "srv0" on :443 with a Cloudflare Origin Certificate (no
 * ACME). This manager prepends preview routes (preview-{shortId}.executespec.ai → localhost:{port})
 * before the catch-all dev.executespec.ai route.
 *
 * <p>Each route gets a unique {@code @id} for removal via {@code DELETE /id/{routeId}}.
 */
@Component
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(name = "preview.proxy.enabled", havingValue = "true")
public class CaddyProxyManager {

  private static final String CADDY_ADMIN = "http://localhost:2019";

  /** Routes are added to srv0 (the single server in config.json). */
  private static final String ROUTES_PATH = "/config/apps/http/servers/srv0/routes";

  private static final Duration TIMEOUT = Duration.ofSeconds(5);

  private final PreviewProperties props;
  private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

  /** Tracks route IDs for cleanup. runId → Caddy route @id. */
  private final ConcurrentHashMap<String, String> routeIds = new ConcurrentHashMap<>();

  @PostConstruct
  void checkCaddyAdmin() {
    try {
      HttpResponse<String> resp =
          httpClient.send(
              HttpRequest.newBuilder()
                  .uri(URI.create(CADDY_ADMIN + "/config/"))
                  .GET()
                  .timeout(TIMEOUT)
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      if (resp.statusCode() == 200) {
        log.info("CaddyProxyManager connected to admin API — routes path: {}", ROUTES_PATH);
      } else {
        log.warn("Caddy admin API returned {}: {}", resp.statusCode(), resp.body());
      }
    } catch (Exception e) {
      log.warn(
          "Caddy admin API not reachable at {} — preview proxy will not work: {}",
          CADDY_ADMIN,
          e.getMessage());
    }
  }

  /**
   * Add a reverse proxy route: preview-{shortId}.{previewDomain} → localhost:{port}. The route is
   * prepended (index 0) so it takes priority over the catch-all route.
   */
  public void addRoute(String runId, int port) {
    String shortId = runId.length() > 8 ? runId.substring(0, 8) : runId;
    String routeId = "preview-" + shortId;
    String host = routeId + "." + props.getPreviewDomain();

    // Caddy JSON route — prepend at index 0 to take priority over catch-all
    String routeJson =
        """
        {
            "@id": "%s",
            "match": [{"host": ["%s"]}],
            "handle": [{
                "handler": "reverse_proxy",
                "upstreams": [{"dial": "localhost:%d"}],
                "transport": {"protocol": "http"}
            }],
            "terminal": true
        }
        """
            .formatted(routeId, host, port);

    try {
      // POST to routes path with index 0 to prepend before catch-all
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(CADDY_ADMIN + ROUTES_PATH + "/0"))
              .method("PUT", HttpRequest.BodyPublishers.ofString(routeJson))
              .header("Content-Type", "application/json")
              .timeout(TIMEOUT)
              .build();

      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());

      if (response.statusCode() >= 200 && response.statusCode() < 300) {
        routeIds.put(runId, routeId);
        log.info("Caddy route added: {} → localhost:{}", host, port);
      } else {
        throw new PreviewException(
            "Caddy addRoute failed ("
                + response.statusCode()
                + "): "
                + response.body()
                + " — route: "
                + host);
      }
    } catch (PreviewException e) {
      throw e; // re-throw our own exceptions
    } catch (IOException | InterruptedException e) {
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
      throw new PreviewException(
          "Failed to add Caddy route for " + runId + " (" + host + "): " + e.getMessage());
    }
  }

  /** Remove the reverse proxy route for a run. */
  public void removeRoute(String runId) {
    String routeId = routeIds.remove(runId);
    if (routeId == null) {
      log.debug("No Caddy route to remove for run {}", runId);
      return;
    }

    try {
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(CADDY_ADMIN + "/id/" + routeId))
              .DELETE()
              .timeout(TIMEOUT)
              .build();

      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());

      if (response.statusCode() >= 200 && response.statusCode() < 300) {
        log.info("Caddy route removed for run {} (id={})", runId, routeId);
      } else {
        log.warn(
            "Caddy removeRoute failed ({}): {} — id={}",
            response.statusCode(),
            response.body(),
            routeId);
      }
    } catch (IOException | InterruptedException e) {
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
      log.warn("Failed to remove Caddy route for {} (id={}): {}", runId, routeId, e.getMessage());
    }
  }

  /** Build the preview URL for a given run. */
  public String buildUrl(String runId) {
    String shortId = runId.length() > 8 ? runId.substring(0, 8) : runId;
    return "https://preview-" + shortId + "." + props.getPreviewDomain();
  }
}

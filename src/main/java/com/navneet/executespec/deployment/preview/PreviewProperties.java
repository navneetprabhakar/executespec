package com.navneet.executespec.deployment.preview;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

@Component
@ConfigurationProperties(prefix = "preview")
@Data
public class PreviewProperties {

  private boolean enabled = true;
  private int totalStartupTimeoutSeconds =
      480; // 8-minute total deadline (Spring Boot cold builds need extra time)
  private int previewTtlMinutes =
      30; // auto-shutdown after N minutes (net usable: ~22 min after 8-min startup)

  /** Preview mode: "docker" (local Docker Compose) or "cloud-run" (GCP Cloud Run). */
  private String mode = "docker";

  // Docker Compose port range
  private int portRangeStart = 10000;
  private int portRangeEnd = 10100;

  /**
   * Use OS ephemeral port allocation (ServerSocket(0)) as primary strategy. Disable for
   * deterministic tests.
   */
  private boolean useEphemeralPorts = true;

  // Preview domain — URLs are: preview-{shortId}.{previewDomain}
  private String previewDomain = "executespec.ai";

  /**
   * Fallback base URL when Caddy proxy is disabled (e.g. "http://dev.executespec.ai"). Used to
   * build preview URLs as {fallbackHost}:{port} so browsers on the dev server can reach the Docker
   * container directly. Null = use http://localhost (local dev only).
   */
  private String fallbackHost;

  private ProxyConfig proxy = new ProxyConfig();

  private ConcurrencyConfig concurrency = new ConcurrencyConfig();

  private CleanupConfig cleanup = new CleanupConfig();

  private InteractiveConfig interactive = new InteractiveConfig();

  /**
   * Route previews through the external preview-engine service instead of running docker-compose
   * locally. When ON + eligible, main app builds the image, pushes to {@link
   * EngineConfig#registryUrl}, and hands off to {@link EngineConfig#baseUrl}'s {@code
   * /api/previews/from-image}. Legacy docker-compose path stays authoritative until the flag is on.
   */
  private EngineConfig engine = new EngineConfig();

  @Data
  public static class ProxyConfig {
    /** Enable Spring Boot preview proxy (PreviewProxyFilter). Set true on server. */
    private boolean enabled = false;
  }

  @Data
  public static class ConcurrencyConfig {
    private int freeMax = 1;
    private int proMax = 3;
    private int enterpriseMax = 10;
  }

  @Data
  public static class CleanupConfig {
    /**
     * Destructive discovery of resources that are absent from this JVM's in-memory map. This is
     * unsafe in a multi-node deployment and therefore stays off until a durable resource registry
     * is authoritative for Docker and Cloud Run previews.
     */
    private boolean orphanDiscoveryEnabled = false;

    /**
     * Tear down previews during JVM shutdown. Disabled by default so rolling deployment does not
     * destroy customer previews; TTL cleanup remains authoritative.
     */
    private boolean teardownOnShutdown = false;
  }

  /**
   * Interactive preview surfaces (RR-391).
   *
   * <p>A preview URL is a bearer capability with no application authentication behind it. That is
   * acceptable for a passive page and not acceptable for a surface that accepts input and executes
   * it, so both interactive modes are opt-in and default OFF.
   */
  @Data
  public static class InteractiveConfig {
    /**
     * Run the CLI terminal preview with {@code ttyd --writable}, i.e. anyone holding the preview
     * URL can type into the generated program's stdin. OFF: the terminal streams output only.
     */
    private boolean writableTerminalEnabled = false;

    /**
     * Serve generated MCP servers through the MCP Inspector, which can invoke the server's tools
     * with no authentication. OFF: the project falls back to the ordinary web preview.
     */
    private boolean mcpInspectorEnabled = false;
  }

  @Data
  public static class EngineConfig {
    /** Route previews through preview-engine when true. */
    private boolean routeVia = false;

    /** Preview-engine control plane base URL (no trailing slash). */
    private String baseUrl = "http://localhost:8090";

    /** Service-to-service API key sent as X-Preview-Api-Key. */
    private String apiKey = "change-me-in-prod";

    /** Image registry preview-engine can pull from. Local dev default = :5500. */
    private String registryUrl = "localhost:5500";

    /** HTTP timeout for calls to preview-engine. */
    private int timeoutSeconds = 300;
  }
}

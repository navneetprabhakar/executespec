package com.navneet.executespec.deployment.preview;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "preview")
@Data
public class PreviewProperties {

    private boolean enabled = true;
    private int totalStartupTimeoutSeconds = 480;  // 8-minute total deadline (Spring Boot cold builds need extra time)
    private int previewTtlMinutes = 10;            // auto-shutdown after N minutes

    /** Preview mode: "docker" (local Docker Compose) or "cloud-run" (GCP Cloud Run). */
    private String mode = "docker";

    // Docker Compose port range
    private int portRangeStart = 10000;
    private int portRangeEnd = 10100;

    // Preview domain — URLs are: preview-{shortId}.{previewDomain}
    private String previewDomain = "executespec.ai";

    /**
     * Fallback base URL when Caddy proxy is disabled (e.g. "http://dev.executespec.ai").
     * Used to build preview URLs as {fallbackHost}:{port} so browsers on the dev server
     * can reach the Docker container directly. Null = use http://localhost (local dev only).
     */
    private String fallbackHost;

    private ProxyConfig proxy = new ProxyConfig();

    private ConcurrencyConfig concurrency = new ConcurrencyConfig();

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
}

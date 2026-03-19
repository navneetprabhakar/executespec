package com.navneet.executespec.deployment.preview;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "preview")
@Data
public class PreviewProperties {

    private boolean enabled = true;
    private int totalStartupTimeoutSeconds = 300;  // 5-minute total deadline for entire preview startup
    private int previewTtlMinutes = 10;            // auto-shutdown after N minutes

    // Docker Compose port range
    private int portRangeStart = 10000;
    private int portRangeEnd = 10100;

    // Caddy reverse proxy domain (wildcard: *.{previewDomain})
    private String previewDomain = "preview.executespec.ai";

    private ConcurrencyConfig concurrency = new ConcurrencyConfig();

    @Data
    public static class ConcurrencyConfig {
        private int freeMax = 1;
        private int proMax = 3;
        private int enterpriseMax = 10;
    }
}

package com.navneet.executespec.deployment.preview;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "preview")
@Data
public class PreviewProperties {

    private boolean enabled = true;
    private int portRangeStart = 10000;
    private int portRangeEnd = 10100;
    private int totalStartupTimeoutSeconds = 300;  // 5-minute total deadline for entire preview startup
    private int buildTimeoutSeconds = 300;
    private int readinessTimeoutSeconds = 120;
    private int readinessPollIntervalMs = 2000;
    private String cpuLimit = "0.5";
    private String memoryLimit = "512m";
    private String springBootCpuLimit = "1.0";
    private String springBootMemoryLimit = "768m";

    private TtlConfig ttl = new TtlConfig();
    private ConcurrencyConfig concurrency = new ConcurrencyConfig();
    private ProxyConfig proxy = new ProxyConfig();

    @Data
    public static class TtlConfig {
        private int freeMinutes = 15;
        private int proMinutes = 60;
        private int enterpriseMinutes = 240;
    }

    @Data
    public static class ConcurrencyConfig {
        private int freeMax = 1;
        private int proMax = 2;
        private int enterpriseMax = 5;
    }

    @Data
    public static class ProxyConfig {
        private boolean enabled = false;
        private String baseUrl = "";
    }
}

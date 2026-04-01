package com.navneet.executespec.ratelimit;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for Redis-backed API rate limiting.
 */
@Data
@ConfigurationProperties(prefix = "executespec.rate-limit")
public class RateLimitProperties {

    /** Master switch. When false, rate limiting is disabled entirely. */
    private boolean enabled = true;
}

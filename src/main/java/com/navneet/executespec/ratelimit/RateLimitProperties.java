package com.navneet.executespec.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Data;

/** Configuration properties for Redis-backed API rate limiting. */
@Data
@ConfigurationProperties(prefix = "executespec.rate-limit")
public class RateLimitProperties {

  /** Master switch. When false, rate limiting is disabled entirely. */
  private boolean enabled = true;
}

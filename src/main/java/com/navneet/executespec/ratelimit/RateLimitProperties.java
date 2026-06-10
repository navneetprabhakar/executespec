package com.navneet.executespec.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Data;

/** Configuration properties for Redis-backed API rate limiting. */
@Data
@ConfigurationProperties(prefix = "executespec.rate-limit")
public class RateLimitProperties {

  /** Master switch. When false, rate limiting is disabled entirely. */
  private boolean enabled = true;

  /**
   * Number of trusted reverse-proxy hops appended at the right side of X-Forwarded-For. The client
   * address is read from the entry immediately to the left of this trusted chain.
   */
  private int trustedProxyCount = 1;
}

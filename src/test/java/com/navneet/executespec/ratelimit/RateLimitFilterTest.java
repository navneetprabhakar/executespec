package com.navneet.executespec.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RateLimitFilterTest {

  @Test
  void extractClientIpUsesEntryBeforeOneTrustedProxyByDefault() {
    assertThat(RateLimitFilter.extractClientIp("203.0.113.10, 10.0.0.20", "10.0.0.20", 1))
        .isEqualTo("203.0.113.10");
  }

  @Test
  void extractClientIpSupportsTwoTrustedProxyHops() {
    assertThat(
            RateLimitFilter.extractClientIp(
                "203.0.113.10, 198.51.100.7, 10.0.0.20", "10.0.0.20", 2))
        .isEqualTo("203.0.113.10");
  }

  @Test
  void extractClientIpFallsBackToRemoteAddressWhenXffMissing() {
    assertThat(RateLimitFilter.extractClientIp(null, "10.0.0.20", 2)).isEqualTo("10.0.0.20");
  }
}

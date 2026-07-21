package com.navneet.executespec.billing.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/**
 * RR-191 — the Razorpay error handler must bound the provider error body it reads so a
 * large/hostile response cannot exhaust heap, and must mark truncation rather than allocate without
 * limit.
 */
class RazorpayConfigBoundedTest {

  @Test
  void readBoundedTruncatesOverLimitErrorBody() throws Exception {
    byte[] big = new byte[RazorpayConfig.MAX_ERROR_BODY_BYTES + 500];
    for (int i = 0; i < big.length; i++) {
      big[i] = 'a';
    }
    String result =
        RazorpayConfig.readBounded(
            new ByteArrayInputStream(big), RazorpayConfig.MAX_ERROR_BODY_BYTES);
    assertThat(result.length())
        .isLessThanOrEqualTo(RazorpayConfig.MAX_ERROR_BODY_BYTES + "…(truncated)".length());
    assertThat(result).endsWith("…(truncated)");
  }

  @Test
  void readBoundedReturnsShortBodyVerbatim() throws Exception {
    String body = "{\"error\":{\"description\":\"Customer already exists\"}}";
    String result =
        RazorpayConfig.readBounded(
            new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)),
            RazorpayConfig.MAX_ERROR_BODY_BYTES);
    // The control-flow marker RazorpayGateway relies on must survive a bounded read.
    assertThat(result).isEqualTo(body);
    assertThat(result).contains("Customer already exists");
  }
}

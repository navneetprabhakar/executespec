package com.navneet.executespec.deployment.preview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/**
 * RR-160 — the preview proxy must not read an untrusted request body into unbounded heap, nor copy
 * an untrusted upstream response without a finite ceiling.
 */
class PreviewProxyFilterBoundsTest {

  @Test
  void readBoundedRejectsOverLimitBody() {
    byte[] tooBig = new byte[64];
    var in = new ByteArrayInputStream(tooBig);
    assertThatThrownBy(() -> PreviewProxyFilter.readBounded(in, 32))
        .isInstanceOf(PreviewProxyFilter.BodyTooLargeException.class);
  }

  @Test
  void readBoundedReturnsBodyAtOrUnderLimit() throws Exception {
    byte[] body = "hello".getBytes(StandardCharsets.UTF_8);
    byte[] read = PreviewProxyFilter.readBounded(new ByteArrayInputStream(body), 32);
    assertThat(read).isEqualTo(body);
  }

  @Test
  void copyBoundedTruncatesOverLimitResponse() throws Exception {
    byte[] upstream = new byte[100];
    for (int i = 0; i < upstream.length; i++) {
      upstream[i] = (byte) 'x';
    }
    var out = new ByteArrayOutputStream();
    PreviewProxyFilter.copyBounded(new ByteArrayInputStream(upstream), out, 40, "upstream");
    // Over-limit body is truncated to the ceiling, never fully buffered/streamed.
    assertThat(out.size()).isEqualTo(40);
  }

  @Test
  void copyBoundedPassesThroughUnderLimitResponse() throws Exception {
    byte[] upstream = "small-body".getBytes(StandardCharsets.UTF_8);
    var out = new ByteArrayOutputStream();
    PreviewProxyFilter.copyBounded(new ByteArrayInputStream(upstream), out, 1024, "upstream");
    assertThat(out.toByteArray()).isEqualTo(upstream);
  }
}

package com.navneet.executespec.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/** RR-1084 — the OpenAI-wire response reader must bound an untrusted body's size. */
class OpenAiNativeToolClientBoundedBodyTest {

  @Test
  void readsSmallBodyIntact() throws Exception {
    byte[] payload = "{\"choices\":[]}".getBytes(StandardCharsets.UTF_8);
    byte[] out = OpenAiNativeToolClient.readBounded(new ByteArrayInputStream(payload));
    assertThat(out).isEqualTo(payload);
  }

  @Test
  void nullStreamYieldsEmpty() throws Exception {
    assertThat(OpenAiNativeToolClient.readBounded(null)).isEmpty();
  }

  @Test
  void oversizedBodyFailsClosed() {
    // A body just over the 32 MiB ceiling must abort as a typed protocol failure rather than
    // buffering the whole thing into heap.
    int overflow = 33 * 1024 * 1024;
    java.io.InputStream endless =
        new java.io.InputStream() {
          private int emitted = 0;

          @Override
          public int read() {
            return emitted++ < overflow ? 'a' : -1;
          }
        };
    assertThatThrownBy(() -> OpenAiNativeToolClient.readBounded(endless))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("ceiling");
  }
}

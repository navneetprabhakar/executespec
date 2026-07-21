package com.navneet.executespec.specchat;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.net.URI;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

/**
 * RR-548. Pins the canonical outbound-fetch policy shared by the chat surface and the local-hands
 * compatibility {@code web_fetch}. Every rejection below happens before any socket is opened, so
 * the test is hermetic; the body-cap cases use the injectable transport seam.
 */
class GuardedWebFetchTest {

  private static GuardedWebFetch.Transport never() {
    return (uri, host, address, maxBytes) -> {
      throw new AssertionError("transport must not be reached for a refused target");
    };
  }

  // ------------------------------------------------------------------ internal targets refused

  @Test
  void refusesLoopbackHostname() {
    GuardedWebFetch.Outcome out = GuardedWebFetch.fetch("http://localhost/admin", 1024, never());
    assertThat(out.ok()).isFalse();
    assertThat(out.error()).isEqualTo("host is not allowed");
  }

  @Test
  void refusesLoopbackLiteral() {
    assertThat(GuardedWebFetch.fetch("http://127.0.0.1/", 1024, never()).ok()).isFalse();
    assertThat(GuardedWebFetch.fetch("http://[::1]/", 1024, never()).ok()).isFalse();
  }

  @Test
  void refusesCloudMetadataAddressAndHostname() {
    // 169.254.169.254 — the AWS/GCP/Azure instance metadata endpoint.
    assertThat(
            GuardedWebFetch.fetch("http://169.254.169.254/latest/meta-data/", 1024, never()).ok())
        .isFalse();
    assertThat(GuardedWebFetch.fetch("http://metadata.google.internal/", 1024, never()).ok())
        .isFalse();
    assertThat(GuardedWebFetch.fetch("http://anything.internal/", 1024, never()).ok()).isFalse();
  }

  @Test
  void refusesPrivateAndCgnatRanges() {
    for (String url :
        new String[] {
          "http://10.0.0.5/",
          "http://192.168.1.1/",
          "http://172.16.4.4/",
          "http://100.64.0.1/",
          "http://0.0.0.0/"
        }) {
      assertThat(GuardedWebFetch.fetch(url, 1024, never()).ok()).as(url).isFalse();
    }
  }

  @Test
  void refusesIpv4MappedInternalAddress() throws Exception {
    // ::ffff:127.0.0.1 and ::ffff:169.254.169.254 must be judged on their IPv4 payload; a check
    // that only calls isLoopbackAddress()/isLinkLocalAddress() on the v6 form can miss these.
    assertThat(GuardedWebFetch.isDisallowedAddress(InetAddress.getByName("::ffff:127.0.0.1")))
        .isTrue();
    assertThat(GuardedWebFetch.isDisallowedAddress(InetAddress.getByName("::ffff:169.254.169.254")))
        .isTrue();
    assertThat(GuardedWebFetch.isDisallowedAddress(InetAddress.getByName("::ffff:8.8.8.8")))
        .isFalse();
  }

  @Test
  void refusesNonDefaultPortsSoInternalServicesAreUnreachable() {
    // Redis / admin / k8s API style targets are addressed by port even on a public-looking host.
    assertThat(GuardedWebFetch.fetch("http://example.com:6379/", 1024, never()).ok()).isFalse();
    assertThat(GuardedWebFetch.fetch("https://example.com:10250/", 1024, never()).ok()).isFalse();
  }

  @Test
  void refusesNonHttpSchemesAndUrlCredentials() {
    assertThat(GuardedWebFetch.fetch("file:///etc/passwd", 1024, never()).ok()).isFalse();
    assertThat(GuardedWebFetch.fetch("gopher://example.com/", 1024, never()).ok()).isFalse();
    assertThat(GuardedWebFetch.fetch("https://user:pass@example.com/", 1024, never()).ok())
        .isFalse();
  }

  // ---------------------------------------------------------------------- pinning + caps

  @Test
  void pinsConnectionToTheValidatedAddressAndPassesTheCapThrough() {
    AtomicReference<InetAddress> pinned = new AtomicReference<>();
    AtomicReference<String> sniHost = new AtomicReference<>();
    AtomicReference<Integer> cap = new AtomicReference<>();
    GuardedWebFetch.Outcome out =
        GuardedWebFetch.fetch(
            "https://example.com/docs",
            4096,
            (URI uri, String host, InetAddress address, int maxBytes) -> {
              pinned.set(address);
              sniHost.set(host);
              cap.set(maxBytes);
              return GuardedWebFetch.Outcome.ok("public docs");
            });
    assertThat(out.ok()).isTrue();
    assertThat(out.body()).isEqualTo("public docs");
    assertThat(sniHost.get()).isEqualTo("example.com");
    assertThat(pinned.get()).isNotNull();
    assertThat(GuardedWebFetch.isDisallowedAddress(pinned.get())).isFalse();
    assertThat(cap.get()).isEqualTo(4096);
  }

  @Test
  void clampsRequestedCapToTheHardCeiling() {
    AtomicReference<Integer> cap = new AtomicReference<>();
    GuardedWebFetch.fetch(
        "https://example.com/",
        Integer.MAX_VALUE,
        (uri, host, address, maxBytes) -> {
          cap.set(maxBytes);
          return GuardedWebFetch.Outcome.ok("ok");
        });
    assertThat(cap.get()).isEqualTo(GuardedWebFetch.MAX_FETCH_BYTES);
  }

  @Test
  void refusesDeclaredContentLengthAboveTheCapBeforeReadingABody() {
    assertThat(GuardedWebFetch.isContentLengthTooLarge("64001", 64_000)).isTrue();
    assertThat(GuardedWebFetch.isContentLengthTooLarge("64000", 64_000)).isFalse();
    // Unparseable length fails closed rather than being treated as "unknown, go ahead".
    assertThat(GuardedWebFetch.isContentLengthTooLarge("not-a-number", 64_000)).isTrue();
  }

  @Test
  void allowsOnlyTextualContentTypes() {
    assertThat(GuardedWebFetch.isAllowedContentType("text/html; charset=utf-8")).isTrue();
    assertThat(GuardedWebFetch.isAllowedContentType("application/problem+json")).isTrue();
    assertThat(GuardedWebFetch.isAllowedContentType("image/png")).isFalse();
    assertThat(GuardedWebFetch.isAllowedContentType("application/octet-stream")).isFalse();
  }
}

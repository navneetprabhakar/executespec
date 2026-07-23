package com.navneet.executespec.auth.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * RR-001 / RR-002 / RR-003 — target policy for the shared credential-validation probe. Every case
 * here must be refused before any socket is opened, so these run without network access.
 */
class ExternalConnectionValidatorTest {

  private final ExternalConnectionValidator validator = new ExternalConnectionValidator();

  @ParameterizedTest
  @ValueSource(
      strings = {
        "http://api.github.com/user", // plaintext
        "ftp://example.com/", // non-HTTP scheme
        "https://127.0.0.1/rest/api/3/myself", // loopback
        "https://127.0.0.1:8443/x", // loopback, explicit port
        "https://localhost/rest/api/3/myself", // loopback by name
        "https://0.0.0.0/", // any-local
        "https://169.254.169.254/latest/meta-data/", // cloud metadata (link-local)
        "https://10.0.0.5/", // private
        "https://192.168.1.10/", // private
        "https://172.16.4.4/", // private
        "https://100.64.0.1/", // carrier-grade NAT
        "https://[::1]/", // IPv6 loopback
        "https://[fd00::1]/", // IPv6 unique-local
        "https://[::ffff:169.254.169.254]/", // IPv4-mapped metadata
        "https://user:pass@example.com/", // credentials embedded in the URL
        "not a url at all",
        "https:///no-host",
      })
  void refusesDisallowedTargetsWithoutContactingThem(String url) {
    ExternalConnectionValidator.ProbeResult result = validator.probe(url, Map.of());
    assertThat(result.status())
        .as(url)
        .isEqualTo(ExternalConnectionValidator.Status.TARGET_NOT_ALLOWED);
    assertThat(result.ok()).isFalse();
  }

  @Test
  void refusesHeaderInjectionThroughUserSuppliedValues() {
    ExternalConnectionValidator.ProbeResult result =
        validator.probe(
            "https://example.invalid/x", Map.of("Authorization", "Basic abc\r\nX-Evil: 1"));
    assertThat(result.status()).isEqualTo(ExternalConnectionValidator.Status.TARGET_NOT_ALLOWED);
  }

  @Test
  void unresolvableHostIsNotFetched() {
    ExternalConnectionValidator.ProbeResult result =
        validator.probe("https://this-host-does-not-exist.invalid/x", Map.of());
    assertThat(result.status()).isEqualTo(ExternalConnectionValidator.Status.TARGET_NOT_ALLOWED);
  }

  @Test
  void addressPolicyClassifiesLiteralsCorrectly() throws Exception {
    assertThat(ExternalConnectionValidator.isDisallowedAddress(InetAddress.getByName("127.0.0.1")))
        .isTrue();
    assertThat(
            ExternalConnectionValidator.isDisallowedAddress(
                InetAddress.getByName("169.254.169.254")))
        .isTrue();
    assertThat(ExternalConnectionValidator.isDisallowedAddress(InetAddress.getByName("10.1.2.3")))
        .isTrue();
    assertThat(
            ExternalConnectionValidator.isDisallowedAddress(InetAddress.getByName("100.100.0.1")))
        .isTrue();
    assertThat(ExternalConnectionValidator.isDisallowedAddress(InetAddress.getByName("0.1.2.3")))
        .isTrue();
    assertThat(ExternalConnectionValidator.isDisallowedAddress(InetAddress.getByName("fd00::1")))
        .isTrue();
    // IPv4-mapped IPv6 must be judged on the embedded IPv4 payload, not on the v6 prefix.
    assertThat(
            ExternalConnectionValidator.isDisallowedAddress(
                InetAddress.getByAddress(
                    new byte[] {
                      0,
                      0,
                      0,
                      0,
                      0,
                      0,
                      0,
                      0,
                      0,
                      0,
                      (byte) 0xff,
                      (byte) 0xff,
                      (byte) 169,
                      (byte) 254,
                      (byte) 169,
                      (byte) 254
                    })))
        .isTrue();
    // A genuine public unicast address stays allowed.
    assertThat(
            ExternalConnectionValidator.isDisallowedAddress(InetAddress.getByName("140.82.121.4")))
        .isFalse();
  }
}
